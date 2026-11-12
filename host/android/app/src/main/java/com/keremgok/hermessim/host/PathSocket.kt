// HermesSIM host. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import com.keremgok.hermessim.host.wire.AudioPacket
import com.keremgok.hermessim.host.wire.Stun
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The UDP socket of one internet audio path. One reader thread takes everything that comes: STUN answers go to the
 * transaction waiting for them, ChannelData and Data indications through [turn] to [onDatagram] as relayed, and audio
 * datagrams straight from the phone to [onDatagram] as they came. The socket's public address is the host's direct
 * candidate, so probes and audio leave from it too.
 */
class PathSocket(private val onDatagram: (ByteArray, Int, Int, Route) -> Unit) {
    /** Where a datagram came from, and where an answer goes: [to] directly, or to [to] through the host's allocation. */
    data class Route(val to: InetSocketAddress, val viaOurRelay: Boolean)

    private val sock = DatagramSocket(0)
    private val pending = ConcurrentHashMap<String, LinkedBlockingQueue<Stun.Message>>()
    @Volatile var turn: Turn? = null
    @Volatile private var open = true
    /** Sends the socket refused. */
    val sendErrors = AtomicLong()

    init {
        Thread(::read, "audio-path-rx").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }
    }

    /** One datagram to [to]; false when the socket refused it (counted). */
    fun send(bytes: ByteArray, len: Int, to: InetSocketAddress): Boolean =
        runCatching { sock.send(DatagramPacket(bytes, len, to)) }.onFailure { sendErrors.incrementAndGet() }.isSuccess

    /**
     * A STUN request to [to], sent up to 3 times 500 ms apart, and the first answer [accept] takes; null without one.
     * An answer [accept] refuses (its MESSAGE-INTEGRITY failed) is dropped and the wait goes on.
     */
    fun transact(to: InetSocketAddress, bytes: ByteArray, tid: ByteArray, accept: (Stun.Message) -> Boolean = { true }): Stun.Message? {
        val q = LinkedBlockingQueue<Stun.Message>()
        val key = RemoteCrypto.hex(tid)
        pending[key] = q
        try {
            repeat(TRIES) {
                send(bytes, bytes.size, to)
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS)
                while (true) {
                    val left = deadline - System.nanoTime()
                    val m = (if (left > 0) q.poll(left, TimeUnit.NANOSECONDS) else null) ?: break
                    if (accept(m)) return m
                }
            }
        } finally {
            pending.remove(key)
        }
        return null
    }

    fun close() {
        open = false
        sock.close()
    }

    private fun read() {
        val buf = ByteArray(BUFFER)
        val pkt = DatagramPacket(buf, buf.size)
        while (open) {
            pkt.setData(buf)
            if (runCatching { sock.receive(pkt) }.isFailure) break
            if (pkt.length > 0) dispatch(buf, pkt.length, pkt.socketAddress as InetSocketAddress)
        }
    }

    private fun dispatch(buf: ByteArray, n: Int, from: InetSocketAddress) {
        val first = buf[0].toInt() and 0xff
        when {
            first == AudioPacket.AUDIO || first == AudioPacket.PROBE -> onDatagram(buf, 0, n, Route(from, false))
            first < FIRST_CHANNEL -> stun(buf, n, from)
            first < LAST_CHANNEL -> turn?.channelData(buf, n, from)?.let { (peer, len) -> onDatagram(buf, CHANNEL_HEAD, len, Route(peer, true)) }
        }
    }

    private fun stun(buf: ByteArray, n: Int, from: InetSocketAddress) {
        val m = Stun.decode(buf, 0, n) ?: return
        if (m.kind == Stun.INDICATION) {
            turn?.dataIndication(m, from)?.let { (peer, data) -> onDatagram(data, 0, data.size, Route(peer, true)) }
            return
        }
        pending[RemoteCrypto.hex(m.tid)]?.offer(m)
    }

    private companion object {
        const val BUFFER = 2048
        const val TRIES = 3
        const val WAIT_MS = 500L
        const val FIRST_CHANNEL = 0x40
        const val LAST_CHANNEL = 0x80
        const val CHANNEL_HEAD = 4
    }
}
