// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.util.Log
import com.keremgok.hermessim.host.wire.Stun
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom

/**
 * The host's TURN allocation (RFC 8656) over the path's UDP socket ([PathSocket]): it lets the phone's addresses in,
 * sends to a peer with Send indications until that peer is bound to a channel, then with ChannelData, and keeps the
 * allocation, the permissions and the channel alive. Every answer that carries MESSAGE-INTEGRITY must pass it, and a
 * success must carry it once the credentials are known; others are dropped. Long-term credentials come from /turn.
 */
class Turn(val server: InetSocketAddress, private val user: String, private val pass: String, private val io: PathSocket) {
    private companion object {
        const val TAG = "HermesSimHost"
        const val LIFETIME_S = 600
        const val UDP = 17
        const val FAMILY_V4 = 1
        const val FAMILY_V6 = 2
        const val FIRST_CHANNEL = 0x4000
        const val LAST_CHANNEL = 0x4FFF
        const val ERROR_UNAUTHORIZED = 401
        const val ERROR_STALE_NONCE = 438
        const val TRIES = 3
        val rng = SecureRandom()
    }

    private var realm = ByteArray(0)
    private var nonce = ByteArray(0)
    @Volatile private var key: ByteArray? = null
    @Volatile var relay: InetSocketAddress? = null; private set
    private val permitted = LinkedHashSet<InetAddress>()
    /** The peer bound to [channel], if any. */
    @Volatile private var bound: Pair<InetSocketAddress, Int>? = null

    /** A relay of the family asked for; null when the server gave none (the reason is logged). */
    fun allocate(ipv6: Boolean): InetSocketAddress? {
        val family = (if (ipv6) FAMILY_V6 else FAMILY_V4).toByte()
        val r = request(Stun.ALLOCATE) {
            listOf(Stun.Attr(Stun.TRANSPORT, byteArrayOf(UDP.toByte(), 0, 0, 0)),
                Stun.Attr(Stun.ADDRESS_FAMILY, byteArrayOf(family, 0, 0, 0)), Stun.Attr(Stun.LIFETIME, Stun.u32(LIFETIME_S)))
        }
        if (r?.kind != Stun.SUCCESS) return null.also { Log.w(TAG, "turn allocate failed: ${r?.error() ?: "no answer"}") }
        return r.address(Stun.XOR_RELAYED).also { relay = it }
    }

    /** Lets [peers] send to the relay (permissions last 5 minutes; [refresh] renews them). */
    fun permit(peers: Collection<InetAddress>): Boolean {
        synchronized(permitted) { permitted += peers }
        return createPermission(peers)
    }

    /** Binds [peer] to a channel, so audio to it goes as ChannelData; a new peer gets the next channel. */
    fun bind(peer: InetSocketAddress): Boolean {
        val now = bound
        if (now?.first == peer) return true
        val number = (now?.second ?: FIRST_CHANNEL - 1) + 1
        if (number > LAST_CHANNEL || !channelBind(peer, number)) return false
        bound = peer to number
        return true
    }

    fun boundTo(peer: InetSocketAddress): Boolean = bound?.first == peer

    /** Renews the allocation, the permissions and the channel; false when the allocation is gone. */
    fun refresh(): Boolean {
        val r = request(Stun.REFRESH) { listOf(Stun.Attr(Stun.LIFETIME, Stun.u32(LIFETIME_S))) }
        if (r?.kind != Stun.SUCCESS) return false.also { Log.w(TAG, "turn refresh failed: ${r?.error() ?: "no answer"}") }
        createPermission(synchronized(permitted) { permitted.toList() })
        bound?.let { (peer, number) -> channelBind(peer, number) }
        return true
    }

    /** Gives the allocation back at once (lifetime 0); no answer is awaited. */
    fun release() {
        if (relay == null) return
        val bytes = Stun.encode(Stun.REFRESH, tid(), withAuth(listOf(Stun.Attr(Stun.LIFETIME, Stun.u32(0)))), key)
        io.send(bytes, bytes.size, server)
    }

    /** [len] bytes of [data] to [peer] through the relay. */
    fun send(peer: InetSocketAddress, data: ByteArray, len: Int): Boolean {
        val b = bound
        if (b?.first == peer) {
            val out = ByteArray(4 + len)
            out[0] = (b.second shr 8).toByte(); out[1] = b.second.toByte(); out[2] = (len shr 8).toByte(); out[3] = len.toByte()
            System.arraycopy(data, 0, out, 4, len)
            return io.send(out, out.size, server)
        }
        val tid = tid()
        val bytes = Stun.encode(Stun.SEND or Stun.INDICATION, tid,
            listOf(Stun.Attr(Stun.XOR_PEER, Stun.xorValue(peer, tid)), Stun.Attr(Stun.DATA, data.copyOf(len))))
        return io.send(bytes, bytes.size, server)
    }

    /** A ChannelData from the server: the bound peer and the data's length (it starts at byte 4); null otherwise. */
    fun channelData(buf: ByteArray, n: Int, from: InetSocketAddress): Pair<InetSocketAddress, Int>? {
        val (peer, number) = bound ?: return null
        if (from != server || n < 4) return null
        val got = ((buf[0].toInt() and 0xff) shl 8) or (buf[1].toInt() and 0xff)
        val len = ((buf[2].toInt() and 0xff) shl 8) or (buf[3].toInt() and 0xff)
        return if (got == number && 4 + len <= n) peer to len else null
    }

    /** A Data indication from the server: its peer and data; null for anything else. */
    fun dataIndication(m: Stun.Message, from: InetSocketAddress): Pair<InetSocketAddress, ByteArray>? {
        if (from != server || m.method != Stun.DATA_METHOD) return null
        val peer = m.address(Stun.XOR_PEER) ?: return null
        return peer to (m.attrs[Stun.DATA] ?: return null)
    }

    private fun createPermission(peers: Collection<InetAddress>): Boolean {
        if (peers.isEmpty()) return true
        val r = request(Stun.CREATE_PERMISSION) { tid -> peers.map { Stun.Attr(Stun.XOR_PEER, Stun.xorValue(InetSocketAddress(it, 0), tid)) } }
        if (r?.kind != Stun.SUCCESS) Log.w(TAG, "turn permission failed: ${r?.error() ?: "no answer"}")
        return r?.kind == Stun.SUCCESS
    }

    private fun channelBind(peer: InetSocketAddress, number: Int): Boolean {
        val r = request(Stun.CHANNEL_BIND) { tid ->
            listOf(Stun.Attr(Stun.CHANNEL_NUMBER, byteArrayOf((number shr 8).toByte(), number.toByte(), 0, 0)),
                Stun.Attr(Stun.XOR_PEER, Stun.xorValue(peer, tid)))
        }
        if (r?.kind != Stun.SUCCESS) Log.w(TAG, "turn channel bind failed: ${r?.error() ?: "no answer"}")
        return r?.kind == Stun.SUCCESS
    }

    /**
     * One request with the credentials ([attrs] of its transaction id: XOR values depend on it). A 401 or a 438 brings
     * the realm and a nonce, and the request goes again with them.
     */
    private fun request(method: Int, attrs: (ByteArray) -> List<Stun.Attr>): Stun.Message? {
        repeat(TRIES) {
            val tid = tid()
            val r = io.transact(server, Stun.encode(method, tid, withAuth(attrs(tid)), key), tid, ::trusted) ?: return null
            val code = r.error()
            if (r.kind == Stun.ERROR && (code == ERROR_UNAUTHORIZED || code == ERROR_STALE_NONCE) && learn(r)) return@repeat
            return r
        }
        return null
    }

    private fun withAuth(attrs: List<Stun.Attr>): List<Stun.Attr> {
        if (key == null) return attrs
        return attrs + Stun.Attr(Stun.USERNAME, user.toByteArray()) + Stun.Attr(Stun.REALM, realm) + Stun.Attr(Stun.NONCE, nonce)
    }

    /** An answer to take: one with MESSAGE-INTEGRITY must pass it, and a success must carry it once there is a key. */
    private fun trusted(m: Stun.Message): Boolean {
        val k = key
        return if (m.integrityAt >= 0) k != null && Stun.integrityOk(m, k) else k == null || m.kind == Stun.ERROR
    }

    /** The realm and nonce of a 401 or 438; false when it has none (the request fails). */
    private fun learn(r: Stun.Message): Boolean {
        val n = r.attrs[Stun.NONCE] ?: return false
        realm = r.attrs[Stun.REALM] ?: realm
        nonce = n
        key = Stun.longTermKey(user, realm, pass)
        return true
    }

    private fun tid() = ByteArray(12).also { rng.nextBytes(it) }
}
