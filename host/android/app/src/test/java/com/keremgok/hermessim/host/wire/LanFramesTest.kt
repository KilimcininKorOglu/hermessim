// HermesSIM host. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
package com.keremgok.hermessim.host.wire

import com.keremgok.hermessim.host.wire.Vectors.hex
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The local network stream against protocol/v2/vectors/lan.json (protocol/README.md "Local network"). */
class LanFramesTest {
    private val vectors = JSONObject(File("../../../protocol/v2/vectors/lan.json").readText())
    private val frames = vectors.getJSONObject("frames")
    private val cases = frames.getJSONArray("cases").let { a -> List(a.length(), a::getJSONObject) }

    @Test fun `the key id, the auth key and the KEY frame are the vectors' bytes`() {
        val keys = vectors.getJSONArray("keys").let { a -> List(a.length(), a::getJSONObject) }
        for (case in keys) {
            val key = hex(case.getString("key"))
            assertEquals(case.getString("key_id"), LanFrames.keyId(key).hex())
            assertEquals(case.getString("auth"), LanFrames.auth(key).hex())
            val keyFrame = LanFrames.frame(byteArrayOf(LanFrames.KEY.toByte()) + LanFrames.keyId(key))
            assertEquals(case.getString("key_frame"), keyFrame.hex())
            assertArrayEquals(LanFrames.keyId(key), LanFrames.keyIdOf(keyFrame.copyOfRange(4, keyFrame.size)))
        }
    }

    @Test fun `whole frames come out as their bodies and a frame is written as the stream`() {
        assertEquals(LanFrames.MAX_BODY, frames.getInt("max_body"))
        for (case in cases.filter { it.has("body") }) {
            assertEquals(case.getString("name"), case.getString("stream"), LanFrames.frame(hex(case.getString("body"))).hex())
            val out = LanFrames.Reader().feed(hex(case.getString("stream")))
            assertEquals(case.getString("name"), listOf(case.getString("body")), out.map { it.hex() })
        }
    }

    @Test fun `a frame of length 0 or over the limit ends the stream`() {
        for (case in cases.filter { it.optBoolean("error") }) {
            assertThrows(case.getString("name"), IllegalStateException::class.java) { LanFrames.Reader().feed(hex(case.getString("stream"))) }
        }
        assertThrows(IllegalArgumentException::class.java) { LanFrames.frame(ByteArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { LanFrames.frame(ByteArray(LanFrames.MAX_BODY + 1)) }
    }

    @Test fun `a cut frame waits for the rest, and bytes split anywhere give the same bodies`() {
        for (case in cases.filter { it.optBoolean("partial") }) {
            assertTrue(LanFrames.Reader().feed(hex(case.getString("stream"))).isEmpty())
        }
        val stream = LanFrames.frame(byteArrayOf(1)) + LanFrames.frame(ByteArray(300) { it.toByte() })
        val reader = LanFrames.Reader()
        val got = stream.toList().chunked(7).flatMap { reader.feed(it.toByteArray()) }
        assertEquals(listOf(1, 300), got.map { it.size })
    }

    @Test fun `only a KEY frame of the right size names a key`() {
        assertNull(LanFrames.keyIdOf(byteArrayOf(0, 1, 2)))
        assertNull(LanFrames.keyIdOf(byteArrayOf(1) + ByteArray(8)))
    }
}
