// HermesSIM host. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
package com.keremgok.hermessim.host.wire

import com.keremgok.hermessim.host.RemoteCrypto
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * The local network stream (protocol/README.md "Local network"): `[u32 BE length][body]` frames, length 1 to
 * [MAX_BODY]. A peer's first frame names its key (`[0][key_id]`); the tunnel runs in the frames after it. Pure.
 */
object LanFrames {
    const val MAX_BODY = 16_448
    const val KEY = 0
    const val KEY_ID_BYTES = 8

    /** [body] as one frame of the stream. */
    fun frame(body: ByteArray): ByteArray {
        require(body.isNotEmpty() && body.size <= MAX_BODY) { "a frame of ${body.size} bytes" }
        return ByteBuffer.allocate(4 + body.size).putInt(body.size).put(body).array()
    }

    /** The first 8 bytes of sha256 of a peer's key: what its KEY frame names. */
    fun keyId(key: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(key).copyOf(KEY_ID_BYTES)

    /** Proves the key in the tunnel handshake. */
    fun auth(key: ByteArray): ByteArray = RemoteCrypto.hkdf(key, "lan-auth")

    /** The key id a KEY frame names, or null when [body] is not one. */
    fun keyIdOf(body: ByteArray): ByteArray? =
        if (body.size == 1 + KEY_ID_BYTES && body[0].toInt() == KEY) body.copyOfRange(1, body.size) else null

    /** Splits a byte stream into frame bodies; a frame of length 0 or over [MAX_BODY] ends the stream. */
    class Reader {
        private val held = ByteArrayOutputStream()

        /** The bodies [bytes] completed, in order; throws [IllegalStateException] on a bad length. */
        fun feed(bytes: ByteArray): List<ByteArray> {
            held.write(bytes)
            val all = held.toByteArray()
            val out = ArrayList<ByteArray>()
            var at = 0
            while (all.size - at >= 4) {
                val length = ByteBuffer.wrap(all, at, 4).int
                check(length in 1..MAX_BODY) { "a frame of $length bytes" }
                if (all.size - at - 4 < length) break
                out.add(all.copyOfRange(at + 4, at + 4 + length))
                at += 4 + length
            }
            held.reset()
            held.write(all, at, all.size - at)
            return out
        }
    }
}
