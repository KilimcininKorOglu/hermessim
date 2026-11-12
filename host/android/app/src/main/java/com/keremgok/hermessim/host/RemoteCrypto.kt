// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import com.keremgok.hermessim.host.wire.Tunnel
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The keys derived from a room key or a push key (protocol/README.md "Room key", "Push"): HKDF-SHA256 with salt
 * "hermes-remote-v1". A push seals with ChaCha20-Poly1305 as nonce12 || ct; the control path runs in [Tunnel], call audio
 * under keys of its own path (wire/AudioPacket.kt).
 */
class RemoteCrypto(key: ByteArray) {
    companion object {
        private val SALT = "hermes-remote-v1".toByteArray()
        val H2C = "h2c".toByteArray()
        private val rng = SecureRandom()
        private const val COLLAPSE_HEX = 32

        fun hkdf(ikm: ByteArray, info: String, len: Int = 32): ByteArray {
            val prk = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(SALT, "HmacSHA256")) }.doFinal(ikm)
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
            val out = ByteArray(len)
            var t = ByteArray(0)
            var i = 0
            var counter = 1
            while (i < len) {
                mac.update(t); mac.update(info.toByteArray()); mac.update(counter.toByte())
                t = mac.doFinal()
                val n = minOf(t.size, len - i)
                System.arraycopy(t, 0, out, i, n)
                i += n; counter++
            }
            return out
        }

        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    }

    val roomToken: ByteArray = hkdf(key, "room-token")
    val roomId: String = hex(MessageDigest.getInstance("SHA-256").digest(roomToken))
    /** Proves the room key in the tunnel handshake; it encrypts nothing. */
    val auth: ByteArray = hkdf(key, "auth")
    private val push = SecretKeySpec(hkdf(key, "push"), "ChaCha20")
    private val collapse = hkdf(key, "collapse")

    /** Push plaintext = [u64 BE counter][u64 BE unix ms][Packet], under its own key (a notification extension holds only it). */
    fun sealPush(counter: Long, sentMs: Long, packet: ByteArray, nonce: ByteArray = random12()): ByteArray =
        seal(push, ByteBuffer.allocate(16 + packet.size).putLong(counter).putLong(sentMs).put(packet).array(), H2C, nonce)

    /** The collapse id of an event's notification: neither the server nor the push service learns the kind or the line. */
    fun collapseId(name: String): String = hex(Tunnel.hmac(collapse, name.toByteArray())).take(COLLAPSE_HEX)

    private fun random12() = ByteArray(12).also { rng.nextBytes(it) }

    private fun seal(k: SecretKeySpec, plain: ByteArray, aad: ByteArray, nonce: ByteArray = random12()): ByteArray {
        val c = Cipher.getInstance("ChaCha20-Poly1305")
        c.init(Cipher.ENCRYPT_MODE, k, IvParameterSpec(nonce))
        c.updateAAD(aad)
        return nonce + c.doFinal(plain)
    }
}
