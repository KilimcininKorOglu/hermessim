// HermesSIM host. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.keremgok.hermessim.host.wire.LanFrames
import com.keremgok.hermessim.host.wire.Tunnel
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * The local network path (protocol/README.md "Local network"): a TCP server on the kept port (LanKeys.port), announced over mDNS as
 * `_hermessim._tcp` under [LanKeys.service]. Each connection names a peer's key, runs the tunnel with that key's
 * auth, and becomes a link once HS3 is right. Sockets run on their own threads; [Relay] gets everything on main.
 */
object LanLink {
    private const val TAG = "HermesSimHost"
    const val SERVICE_TYPE = "_hermessim._tcp"
    private const val READ_BYTES = 8192
    /** A peer that has not named its key and opened the tunnel by then is closed. */
    private const val OPEN_MS = 15_000
    private val rng = SecureRandom()

    /** One TCP connection: its tunnel, and the peer its key belongs to once the KEY frame named it. */
    class Conn(private val socket: Socket) {
        private val writer = Executors.newSingleThreadExecutor()
        @Volatile var peer: String? = null; private set
        private var tunnel: Tunnel? = null
        private val lock = Any()

        /** Seals [frames] and writes them; false once the tunnel is used up or the socket is gone. */
        fun send(frames: ByteArray): Boolean {
            val body = synchronized(lock) { tunnel?.seal(frames) } ?: return false
            write(body)
            return true
        }

        fun close() {
            runCatching { socket.close() }
            writer.shutdown()
        }

        private fun write(body: ByteArray) = writer.execute {
            try { socket.getOutputStream().apply { write(LanFrames.frame(body)); flush() } } catch (e: IOException) {
                log("lan: write failed: ${e.message}")
                close()
            }
        }

        /** The first frame: the peer's key; false when it names none of ours. */
        internal fun named(body: ByteArray): Boolean {
            val found = LanFrames.keyIdOf(body)?.let(find) ?: return false
            peer = found.first
            synchronized(lock) { tunnel = Tunnel(LanFrames.auth(found.second)) { n -> ByteArray(n).also(rng::nextBytes) } }
            return true
        }

        internal fun step(body: ByteArray): Tunnel.Step {
            val step = synchronized(lock) { tunnel?.receive(body) } ?: return Tunnel.Step.Broken("no tunnel")
            if (step is Tunnel.Step.Send) write(step.body)
            return step
        }

        /** An open link may stay quiet for minutes between CREDITs: no read timeout from here on. */
        internal fun keepOpen() { socket.soTimeout = 0 }

        internal fun input(): InputStream = socket.getInputStream()
    }

    /** What [Relay] hears: a peer's tunnel opened, frames arrived, or the connection ended. */
    interface Listener {
        fun opened(conn: Conn)
        fun frames(conn: Conn, bytes: ByteArray)
        fun closed(conn: Conn)
    }

    private var server: ServerSocket? = null
    private var nsd: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null

    // Tests hear the log and find keys without Android's Log and preferences
    internal var log: (String) -> Unit = { Log.i(TAG, it) }
    internal var find: (ByteArray) -> Pair<String, ByteArray>? = LanKeys::find

    fun start(ctx: Context, listener: Listener) {
        val port = listen(listener, LanKeys.port()) ?: return
        announce(ctx, port)
    }

    /** The port the link listens on now; 0 before it listens. */
    @Volatile var port = 0; private set

    /**
     * Takes connections on [wanted] (the kept port), else on a random one when that is taken; the port, or null when
     * there is no socket or one runs already.
     */
    internal fun listen(listener: Listener, wanted: Int = 0): Int? {
        if (server != null) return null
        val s = open(wanted) ?: return null
        server = s
        port = s.localPort
        Thread({ accept(s, listener) }, "lan-accept").apply { isDaemon = true }.start()
        return s.localPort
    }

    private fun open(wanted: Int): ServerSocket? {
        if (wanted > 0) {
            try { return ServerSocket(wanted) } catch (e: IOException) { log("lan: port $wanted taken: ${e.message}") }
        }
        return try { ServerSocket(0) } catch (e: IOException) { log("lan: no server socket: ${e.message}"); null }
    }

    /** Ends the server; its open connections stay until they end. */
    internal fun stop() {
        runCatching { server?.close() }
        server = null
        port = 0
    }

    private fun announce(ctx: Context, port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = LanKeys.service()
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) { Log.i(TAG, "lan: announced on port $port") }
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) { Log.w(TAG, "lan: announcement failed: $code") }
            override fun onServiceUnregistered(i: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) = Unit
        }
        nsd = ctx.getSystemService(NsdManager::class.java)
        registration = listener
        nsd?.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun accept(s: ServerSocket, listener: Listener) {
        while (!s.isClosed) {
            val socket = try { s.accept() } catch (e: IOException) { if (!s.isClosed) log("lan: accept: ${e.message}"); continue }
            Thread({ serve(Conn(socket.apply { tcpNoDelay = true; soTimeout = OPEN_MS }), listener) }, "lan-conn").apply { isDaemon = true }.start()
        }
    }

    /** Reads one connection to its end: KEY, the handshake, then frames. */
    private fun serve(conn: Conn, listener: Listener) {
        val reader = LanFrames.Reader()
        val buf = ByteArray(READ_BYTES)
        var opened = false
        try {
            val input = conn.input()
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                for (body in reader.feed(buf.copyOf(n))) {
                    opened = handle(conn, body, opened, listener) ?: return
                }
            }
        } catch (e: IOException) {
            log("lan: connection ended: ${e.message}")
        } catch (e: IllegalStateException) {
            log("lan: bad stream: ${e.message}")
        } finally {
            conn.close()
            if (opened) listener.closed(conn)
        }
    }

    /** One frame body: whether the tunnel is open after it, or null to end the connection. */
    private fun handle(conn: Conn, body: ByteArray, opened: Boolean, listener: Listener): Boolean? {
        if (conn.peer == null) {
            if (!conn.named(body)) { log("lan: a connection named no key of ours"); return null }
            return false
        }
        return when (val step = conn.step(body)) {
            is Tunnel.Step.Send -> opened
            Tunnel.Step.Opened -> { conn.keepOpen(); listener.opened(conn); true }
            is Tunnel.Step.Frames -> { listener.frames(conn, step.bytes); opened }
            is Tunnel.Step.Broken -> { log("lan: ${step.why}"); null }
        }
    }
}
