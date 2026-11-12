// HermesSIM host. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
package com.keremgok.hermessim.host.wire

import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The host's end of the internet tunnel (protocol/README.md "Tunnel"). The phone opens it: HS1 brings its fresh
 * X25519 key, HS2 answers with ours and a MAC under the room's auth key, HS3 proves the phone holds the room key too.
 * After that each direction seals under its own key with a counter as the nonce; only the next counter is taken, so
 * a repeat or a gap breaks the tunnel and the phone opens a new one. No clock takes part. Pure; one thread.
 */
class Tunnel(private val auth: ByteArray, private val random: (Int) -> ByteArray) {
    sealed interface Step {
        /** Send this DATA body to the phone. */
        class Send(val body: ByteArray) : Step
        /** HS3 checked: the phone is present and the protocol 2 session starts. */
        data object Opened : Step
        /** Frames of the protocol 2 stream. */
        class Frames(val bytes: ByteArray) : Step
        /** The tunnel is shut; the phone must open a new one. */
        class Broken(val why: String) : Step
    }

    private class Pending(val phoneKey: ByteArray, val hostKey: ByteArray, val p2h: ByteArray, val h2p: ByteArray)

    private var pending: Pending? = null
    private var inKey: SecretKeySpec? = null
    private var outKey: SecretKeySpec? = null
    private var inNext = 0L
    private var outNext = 0L

    val open: Boolean get() = inKey != null

    /** Forgets every key: the phone left, or the room changed. */
    fun reset() {
        pending = null; inKey = null; outKey = null; inNext = 0; outNext = 0
    }

    fun receive(body: ByteArray): Step {
        if (body.isEmpty()) return broken("an empty message")
        return when (body[0].toInt()) {
            HS1 -> hello(body)
            HS3 -> proof(body)
            SEAL -> unseal(body)
            else -> broken("message type ${body[0]}")
        }
    }

    /** The DATA body that carries [frames] to the phone; null while the tunnel is not open or used up. */
    fun seal(frames: ByteArray): ByteArray? {
        val key = outKey ?: return null
        if (outNext >= COUNTER_LIMIT) { reset(); return null }
        val counter = outNext++
        val header = ByteBuffer.allocate(9).put(SEAL.toByte()).putLong(counter).array()
        return header + cipher(Cipher.ENCRYPT_MODE, key, counter).doFinal(frames)
    }

    private fun hello(body: ByteArray): Step {
        if (body.size != 1 + KEY) return broken("HS1 of ${body.size} bytes")
        reset()
        val phoneKey = body.copyOfRange(1, body.size)
        val secret = random(KEY)
        val hostKey = X25519.publicKey(secret)
        val prk = hmac(auth, X25519.shared(secret, phoneKey))
        pending = Pending(phoneKey, hostKey, hmac(prk, P2H), hmac(prk, H2P))
        return Step.Send(byteArrayOf(HS2.toByte()) + hostKey + hmac(auth, byteArrayOf('H'.code.toByte()), phoneKey, hostKey))
    }

    private fun proof(body: ByteArray): Step {
        val p = pending ?: return broken("HS3 without HS1")
        pending = null
        val expected = hmac(auth, byteArrayOf('P'.code.toByte()), p.phoneKey, p.hostKey)
        if (body.size != 1 + MAC || !MessageDigest.isEqual(expected, body.copyOfRange(1, body.size))) {
            return broken("HS3 does not prove the room key")
        }
        inKey = SecretKeySpec(p.p2h, "ChaCha20")
        outKey = SecretKeySpec(p.h2p, "ChaCha20")
        return Step.Opened
    }

    private fun unseal(body: ByteArray): Step {
        val key = inKey ?: return broken("SEAL before the tunnel opened")
        if (body.size < 9 + TAG) return broken("SEAL of ${body.size} bytes")
        val counter = ByteBuffer.wrap(body, 1, 8).long
        if (counter != inNext) return broken("counter $counter, expected $inNext")
        val plain = runCatching { cipher(Cipher.DECRYPT_MODE, key, counter).doFinal(body, 9, body.size - 9) }.getOrNull()
            ?: return broken("SEAL $counter does not authenticate")
        inNext++
        return Step.Frames(plain)
    }

    private fun broken(why: String): Step {
        reset()
        return Step.Broken(why)
    }

    companion object {
        const val HS1 = 1
        const val HS2 = 2
        const val HS3 = 3
        const val SEAL = 4
        private const val KEY = 32
        private const val MAC = 32
        private const val TAG = 16
        /** A direction is opened again before its counter reaches 2^32. */
        const val COUNTER_LIMIT = 1L shl 32
        private val P2H = "p2h".toByteArray() + 1
        private val H2P = "h2p".toByteArray() + 1

        fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray = mac("HmacSHA256", key, *parts)

        /** The HMAC named [algorithm] of [parts] in a row. */
        fun mac(algorithm: String, key: ByteArray, vararg parts: ByteArray): ByteArray =
            Mac.getInstance(algorithm).apply {
                init(SecretKeySpec(key, algorithm))
                parts.forEach { update(it) }
            }.doFinal()

        private fun cipher(mode: Int, key: SecretKeySpec, counter: Long): Cipher =
            Cipher.getInstance("ChaCha20-Poly1305").apply {
                init(mode, key, IvParameterSpec(ByteBuffer.allocate(12).putInt(0).putLong(counter).array()))
            }
    }
}
