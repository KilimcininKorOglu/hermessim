// HermesSIM host. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.content.Context
import android.util.Log
import com.google.protobuf.ByteString
import com.keremgok.hermessim.host.AudioPathCore.Via
import com.keremgok.hermessim.host.wire.AudioKeys
import com.keremgok.hermessim.host.wire.AudioPacket
import com.keremgok.hermessim.host.wire.AudioSeal
import com.keremgok.hermessim.host.wire.Stun
import com.keremgok.hermessim.host.wire.X25519
import com.keremgok.hermessim.wire.AudioPath
import com.keremgok.hermessim.wire.Candidate
import com.keremgok.hermessim.wire.CandidateKind
import com.keremgok.hermessim.wire.Code
import com.keremgok.hermessim.wire.OpenAudioPath
import org.json.JSONObject
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The call's internet audio path (protocol/README.md "Call audio"): the host's candidates (its public address from
 * the TURN server's STUN, and a relay of its own when the phone brought none), the path keys, probes to the phone's
 * candidates, and sealed datagrams both ways. The phone may open it while Bluetooth still carries the call; the
 * path then waits ready and [AudioRouter] switches to it when the phone's audio comes this way.
 *
 * One path at a time: setups run on one thread and carry a generation, so a close (or a newer open) during setup wins.
 */
object InternetPath {
    private const val TAG = "HermesSimHost"
    private const val PROBE_MS = 50L
    private const val PROBES = 20
    private const val IDLE_MS = 5_000L
    private const val REFRESH_S = 240L
    private const val KEY = 32
    private val setup = Bg.single("audio-path")
    private val timers = Executors.newSingleThreadScheduledExecutor()
    private val gen = AtomicInteger()
    private val rng = SecureRandom()
    @Volatile private var path: Path? = null

    private class Path(val socket: PathSocket, val seal: AudioSeal, val phone: List<InetSocketAddress>, val phoneRelay: InetSocketAddress?) {
        @Volatile var turn: Turn? = null
        /** The way the phone's latest audio came, and the best way a probe came (direct over relayed). */
        @Volatile var audioRoute: PathSocket.Route? = null
        @Volatile var probeRoute: PathSocket.Route? = null
        @Volatile var lastSent = 0L
        @Volatile var rx = 0
        @Volatile var tx = 0
        @Volatile var bad = 0
        val tasks = ArrayList<ScheduledFuture<*>>()
        val binding = AtomicBoolean()

        fun relayed(r: PathSocket.Route) = r.viaOurRelay || r.to == phoneRelay

        fun close() {
            synchronized(tasks) { tasks.forEach { it.cancel(false) } }
            val t = turn
            Thread { runCatching { t?.release() }; socket.close() }.start()
        }
    }

    private var creds: JSONObject? = null
    private var credsUntil = 0L

    /** TURN REST credentials, reused until an hour before they expire (saves an HTTPS round trip per call). */
    private fun credentials(ctx: Context): JSONObject {
        creds?.let { if (System.currentTimeMillis() < credsUntil) return it }
        val body = JSONObject().put("install_id", Api.installId(ctx))
        RemoteLink.crypto?.let { body.put("room_token", RemoteCrypto.hex(it.roomToken)) }
        val c = Api.postJson("/turn", body) ?: error("no TURN credentials")
        creds = c
        credsUntil = System.currentTimeMillis() + (c.optLong("ttl", 3600) - 3600).coerceAtLeast(60) * 1000
        return c
    }

    /**
     * The phone's OpenAudioPath (already checked by [AudioPathCore.refusal]): set up the host's side and answer with
     * its key and candidates ([opened], off main), or with why not. [closed] (off main) runs when the path later ends
     * by itself (its allocation was lost).
     */
    fun open(ctx: Context, o: OpenAudioPath, opened: (Code, AudioPath?) -> Unit, closed: () -> Unit) {
        close("a new path")
        val my = gen.incrementAndGet()
        setup.execute {
            val holder = arrayOfNulls<Path>(1)
            try {
                val t0 = System.currentTimeMillis()
                val secret = ByteArray(KEY).also { rng.nextBytes(it) }
                val keys = AudioKeys.derive(o.audioId.toByteArray(), X25519.shared(secret, o.key.toByteArray()))
                val phone = o.candidatesList.map(::address)
                val phoneRelay = o.candidatesList.firstOrNull { it.kind == CandidateKind.CANDIDATE_KIND_RELAY }?.let(::address)
                val socket = PathSocket { b, off, len, route -> holder[0]?.let { received(it, b, off, len, route) } }
                val p = Path(socket, AudioSeal(keys.h2p, keys.p2h), phone, phoneRelay)
                holder[0] = p
                val ours = candidates(ctx, p, o.candidatesList)
                synchronized(this) {
                    check(gen.get() == my) { "superseded during setup" }
                    path = p
                }
                startTimers(p, closed)
                AudioRouter.attach(Via.INTERNET) { b, n -> send(p, b, n) }
                Log.i(TAG, "audio path ready in ${System.currentTimeMillis() - t0} ms: ${ours.size} candidates, phone ${phone.size}")
                opened(Code.CODE_OK, AudioPath.newBuilder().setKey(ByteString.copyFrom(X25519.publicKey(secret))).addAllCandidates(ours).build())
            } catch (e: Exception) {
                Log.e(TAG, "audio path failed: ${e.message ?: e}")
                holder[0]?.close()
                opened(Code.CODE_FAILED, null)                // every request is answered, a superseded one too
            }
        }
    }

    /** The host's candidates: its public addresses, and its relay when the phone brought none. */
    private fun candidates(ctx: Context, p: Path, phone: List<Candidate>): List<Candidate> {
        val c = credentials(ctx)
        val server = turnServer(c)
        val addresses = InetAddress.getAllByName(server.hostString)
        val ours = ArrayList<Candidate>()
        listOfNotNull(addresses.firstOrNull { it is Inet4Address }, addresses.firstOrNull { it is Inet6Address }).forEach { a ->
            reflexive(p.socket, InetSocketAddress(a, server.port))?.let { ours += candidate(CandidateKind.CANDIDATE_KIND_DIRECT, it) }
        }
        if (AudioPathCore.hostRelays(phone)) {
            val ipv6 = AudioPathCore.wantsIpv6(phone)
            val to = addresses.firstOrNull { it is Inet4Address } ?: addresses.first()
            val turn = Turn(InetSocketAddress(to, server.port), c.getString("username"), c.getString("credential"), p.socket)
            p.socket.turn = turn
            turn.allocate(ipv6)?.let { relay ->
                p.turn = turn
                turn.permit(p.phone.map { it.address }.filter { (it is Inet6Address) == ipv6 }.distinct())
                ours += candidate(CandidateKind.CANDIDATE_KIND_RELAY, relay)
            }
        }
        check(ours.isNotEmpty()) { "no candidate: the TURN server did not answer" }
        return ours
    }

    /** The first `turn:` URL over UDP of the server's list, as host and port. */
    private fun turnServer(c: JSONObject): InetSocketAddress {
        val urls = c.getJSONArray("urls").let { a -> List(a.length(), a::getString) }
        val url = urls.first { it.startsWith("turn:") && (!it.contains("transport=") || it.contains("transport=udp")) }
        val uri = URI(url.substringBefore('?').replaceFirst("turn:", "turn://"))
        return InetSocketAddress.createUnresolved(uri.host, if (uri.port > 0) uri.port else 3478)
    }

    /** The socket's public address as the STUN server at [server] saw it; null without an answer. */
    private fun reflexive(socket: PathSocket, server: InetSocketAddress): InetSocketAddress? {
        val tid = ByteArray(12).also { rng.nextBytes(it) }
        val r = socket.transact(server, Stun.encode(Stun.BINDING, tid, emptyList()), tid) ?: return null
        return if (r.kind == Stun.SUCCESS) r.address(Stun.XOR_MAPPED) else null
    }

    private fun startTimers(p: Path, closed: () -> Unit) {
        val sent = AtomicInteger()
        val probes = timers.scheduleWithFixedDelay({
            if (sent.incrementAndGet() <= PROBES) p.phone.forEach { probe(p, PathSocket.Route(it, false)) }
        }, 0, PROBE_MS, TimeUnit.MILLISECONDS)
        // An unused path keeps its NAT bindings and permissions alive with a probe every 5 s.
        val idle = timers.scheduleWithFixedDelay({
            if (System.currentTimeMillis() - p.lastSent < IDLE_MS) return@scheduleWithFixedDelay
            val r = p.audioRoute ?: p.probeRoute
            if (r != null) probe(p, r) else p.phone.forEach { probe(p, PathSocket.Route(it, false)) }
        }, IDLE_MS, IDLE_MS, TimeUnit.MILLISECONDS)
        val refresh = timers.scheduleWithFixedDelay({
            val turn = p.turn ?: return@scheduleWithFixedDelay
            if (path === p && !turn.refresh()) { close("its allocation was lost"); closed() }
        }, REFRESH_S, REFRESH_S, TimeUnit.SECONDS)
        synchronized(p.tasks) { p.tasks += listOf(probes, idle, refresh) }
    }

    private fun probe(p: Path, route: PathSocket.Route) {
        val d = p.seal.seal(AudioPacket.PROBE, AudioPacket.probe(AudioPacket.PROBE_ASK, rng.nextInt())) ?: return
        transmit(p, d, route)
    }

    /** One caller packet of the daemon's to the phone, on the way its latest audio came (or the best probe's). */
    private fun send(p: Path, b: ByteArray, n: Int) {
        val route = p.audioRoute ?: p.probeRoute ?: return
        val d = p.seal.seal(AudioPacket.AUDIO, b, n) ?: return
        if (transmit(p, d, route)) p.tx++ else AudioRouter.sendErrors.incrementAndGet()
    }

    private fun transmit(p: Path, d: ByteArray, route: PathSocket.Route): Boolean {
        p.lastSent = System.currentTimeMillis()
        val turn = p.turn
        return if (route.viaOurRelay && turn != null) turn.send(route.to, d, d.size) else p.socket.send(d, d.size, route.to)
    }

    /** A datagram from the phone, directly or through the host's relay. Reader thread. */
    private fun received(p: Path, b: ByteArray, off: Int, len: Int, route: PathSocket.Route) {
        val o = p.seal.open(b, off, len) ?: run { p.bad++; return }
        if (route.viaOurRelay) bindLater(p, route)
        if (o.type == AudioPacket.AUDIO) {
            p.rx++
            p.audioRoute = route
            AudioRouter.fromPhone(Via.INTERNET, o.plain, 0, o.plain.size)
            return
        }
        // A probe: a direct way wins over a relayed one; an ask is answered on the way it came.
        val best = p.probeRoute
        if (best == null || (p.relayed(best) && !p.relayed(route))) p.probeRoute = route
        if (o.plain.size == AudioPacket.PROBE_SIZE && o.plain[0].toInt() == AudioPacket.PROBE_ASK) {
            val answer = o.plain.copyOf().also { it[0] = AudioPacket.PROBE_ANSWER.toByte() }
            p.seal.seal(AudioPacket.PROBE, answer)?.let { transmit(p, it, route) }
        }
    }

    /** The peer's real address is known: bind it to a channel (off the reader thread, one bind at a time). */
    private fun bindLater(p: Path, route: PathSocket.Route) {
        val turn = p.turn ?: return
        if (turn.boundTo(route.to) || !p.binding.compareAndSet(false, true)) return
        timers.execute { try { turn.bind(route.to) } finally { p.binding.set(false) } }
    }

    private fun address(c: Candidate) = InetSocketAddress(InetAddress.getByAddress(c.address.toByteArray()), c.port)

    private fun candidate(kind: CandidateKind, a: InetSocketAddress) = AudioPathCore.candidate(kind, a.address.address, a.port)

    /** Ends the path, and any setup still running. */
    fun close(why: String) {
        gen.incrementAndGet()
        val p = synchronized(this) { path.also { path = null } } ?: return
        AudioRouter.detach(Via.INTERNET)
        p.close()
        Log.i(TAG, "audio path closed ($why): sent=${p.tx} recv=${p.rx} bad=${p.bad}")
    }
}
