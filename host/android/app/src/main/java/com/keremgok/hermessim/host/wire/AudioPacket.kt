// HermesSIM host. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
package com.keremgok.hermessim.host.wire

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Call audio on the wire (protocol/README.md "Call audio"): the packet is `[u16 tick LE][opus]`; Bluetooth carries a
 * stream of `[0xA5][length][packet]`, the internet datagrams `[type][u32 counter BE][sealed]`. Pure.
 */
object AudioPacket {
    const val MARK = 0xA5
    const val AUDIO = 0xA6
    const val PROBE = 0xA7
    /** The shortest packet: a tick and one Opus byte. */
    const val MIN = 3
    /** The longest packet: a tick and 250 Opus bytes. */
    const val MAX = 252
    const val PROBE_ASK = 1
    const val PROBE_ANSWER = 2
    /** A probe's plaintext: its kind and a u32 id. */
    const val PROBE_SIZE = 5
    /** A datagram's type and counter, before the sealed part. */
    const val HEAD = 5
    const val TAG = 16

    /** Writes [packet]'s first [len] bytes as one stream packet into [out] at [at]; returns the bytes written. */
    fun frame(packet: ByteArray, len: Int, out: ByteArray, at: Int): Int {
        require(len in MIN..MAX) { "audio packet of $len bytes" }
        out[at] = MARK.toByte()
        out[at + 1] = len.toByte()
        System.arraycopy(packet, 0, out, at + 2, len)
        return len + 2
    }

    /** A probe's plaintext: ask or answer, and its id. */
    fun probe(kind: Int, id: Int): ByteArray {
        val p = ByteArray(PROBE_SIZE)
        p[0] = kind.toByte()
        ByteBuffer.wrap(p, 1, 4).putInt(id)
        return p
    }
}

/**
 * The receiving side of the Bluetooth stream: it takes the bytes as they come, in any cut, and hands over each whole
 * packet. A byte that is not a mark, or a mark with a length below [AudioPacket.MIN], is skipped and counted; the
 * channel stays open. One thread.
 */
class AudioStream(private val onPacket: (ByteArray, Int, Int) -> Unit) {
    private val buf = ByteArray(BUFFER)
    private var have = 0
    /** Bytes skipped to find the next packet. */
    var skipped = 0L
        private set

    fun feed(data: ByteArray, off: Int, len: Int) {
        var at = off
        val end = off + len
        while (at < end) {
            val n = minOf(end - at, buf.size - have)
            System.arraycopy(data, at, buf, have, n)
            have += n
            at += n
            parse()
        }
    }

    /** The bytes that wait for the rest of a packet. */
    fun rest(): ByteArray = buf.copyOf(have)

    private fun parse() {
        var at = 0
        while (have - at >= 2) {
            val length = buf[at + 1].toInt() and 0xff
            if ((buf[at].toInt() and 0xff) != AudioPacket.MARK || length < AudioPacket.MIN) {
                at++
                skipped++
                continue
            }
            if (have - at < 2 + length) break
            onPacket(buf, at + 2, length)
            at += 2 + length
        }
        System.arraycopy(buf, at, buf, 0, have - at)
        have -= at
    }

    private companion object {
        const val BUFFER = 4096
    }
}

/**
 * A receiver's counters: the highest taken and a bit for each of the 64 below it (the push window's rule). Each
 * counter is taken once; one 64 or more below the highest is refused.
 */
class CounterWindow {
    private var top = -1L
    private var bits = 0L

    @Synchronized
    fun take(counter: Long): Boolean {
        if (counter > top) {
            val shift = counter - top
            bits = if (top >= 0 && shift < WIDTH) (bits shl shift.toInt()) or 1L else 1L
            top = counter
            return true
        }
        val back = top - counter
        if (back >= WIDTH || (bits ushr back.toInt()) and 1L == 1L) return false
        bits = bits or (1L shl back.toInt())
        return true
    }

    private companion object {
        const val WIDTH = 64
    }
}

/** The two keys of an audio path (protocol/README.md "Path keys"). */
class AudioKeys(val p2h: ByteArray, val h2p: ByteArray) {
    companion object {
        private val P2H = "audio p2h".toByteArray() + 1
        private val H2P = "audio h2p".toByteArray() + 1

        fun derive(audioId: ByteArray, shared: ByteArray): AudioKeys {
            val prk = Tunnel.hmac(audioId, shared)
            return AudioKeys(Tunnel.hmac(prk, P2H), Tunnel.hmac(prk, H2P))
        }
    }
}

/**
 * One side of a path's datagrams: it seals with [sendKey] and a counter that runs across audio and probes, and opens
 * with [receiveKey] and a [CounterWindow]. Any thread; each thread keeps its own Cipher.
 */
class AudioSeal(sendKey: ByteArray, receiveKey: ByteArray) {
    class Opened(val type: Int, val counter: Long, val plain: ByteArray)

    private val out = SecretKeySpec(sendKey, "ChaCha20")
    private val inKey = SecretKeySpec(receiveKey, "ChaCha20")
    private val next = AtomicLong()
    private val window = CounterWindow()
    private val ciphers = ThreadLocal.withInitial { Cipher.getInstance("ChaCha20-Poly1305") }

    /** The datagram for [plain]'s first [len] bytes; null once the counter is used up (the phone opens a new path). */
    fun seal(type: Int, plain: ByteArray, len: Int = plain.size): ByteArray? {
        val counter = next.getAndIncrement()
        if (counter >= LIMIT) return null
        return seal(ciphers.get()!!, out, type, counter, plain, len)
    }

    /** The datagram's type and plaintext; null when it does not authenticate or its counter was taken or is too old. */
    fun open(datagram: ByteArray, off: Int, len: Int): Opened? {
        if (len < AudioPacket.HEAD + AudioPacket.TAG) return null
        val type = datagram[off].toInt() and 0xff
        if (type != AudioPacket.AUDIO && type != AudioPacket.PROBE) return null
        val counter = ByteBuffer.wrap(datagram, off + 1, 4).int.toLong() and 0xffffffffL
        val c = ciphers.get()!!
        val plain = try {
            c.init(Cipher.DECRYPT_MODE, inKey, nonce(counter))
            c.updateAAD(datagram, off, AudioPacket.HEAD)
            c.doFinal(datagram, off + AudioPacket.HEAD, len - AudioPacket.HEAD)
        } catch (_: AEADBadTagException) {
            return null
        }
        return if (window.take(counter)) Opened(type, counter, plain) else null
    }

    companion object {
        /** A direction's counters stop before 2^32. */
        const val LIMIT = 1L shl 32

        /** The datagram of [plain] under [key] with [counter]: the vectors' form. */
        fun seal(key: ByteArray, type: Int, counter: Long, plain: ByteArray): ByteArray =
            seal(Cipher.getInstance("ChaCha20-Poly1305"), SecretKeySpec(key, "ChaCha20"), type, counter, plain, plain.size)

        private fun seal(c: Cipher, key: SecretKeySpec, type: Int, counter: Long, plain: ByteArray, len: Int): ByteArray {
            val out = ByteArray(AudioPacket.HEAD + len + AudioPacket.TAG)
            out[0] = type.toByte()
            ByteBuffer.wrap(out, 1, 4).putInt(counter.toInt())
            c.init(Cipher.ENCRYPT_MODE, key, nonce(counter))
            c.updateAAD(out, 0, AudioPacket.HEAD)
            c.doFinal(plain, 0, len, out, AudioPacket.HEAD)
            return out
        }

        private fun nonce(counter: Long) = IvParameterSpec(ByteBuffer.allocate(12).putLong(4, counter).array())
    }
}
