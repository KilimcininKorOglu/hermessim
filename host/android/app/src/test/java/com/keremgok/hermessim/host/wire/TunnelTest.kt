// HermesSIM host. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
package com.keremgok.hermessim.host.wire

import com.keremgok.hermessim.host.wire.Vectors.hex
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The host's end of the tunnel against protocol/v2/vectors/tunnel.json, and the counter rule. */
class TunnelTest {
    private fun tunnel(case: JSONObject) = Tunnel(hex(case.getString("auth"))) { hex(case.getString("host_secret")) }

    /** Runs HS1 to HS3 of the vector and returns the open tunnel. */
    private fun opened(case: JSONObject): Tunnel {
        val t = tunnel(case)
        val hs2 = t.receive(hex(case.getString("hs1")))
        assertTrue(hs2 is Tunnel.Step.Send)
        assertEquals(case.getString("hs2"), (hs2 as Tunnel.Step.Send).body.hex())
        assertEquals(Tunnel.Step.Opened, t.receive(hex(case.getString("hs3"))))
        assertTrue(t.open)
        return t
    }

    @Test fun `the handshake and the sealed messages are the vectors' bytes`() {
        for (case in Vectors.cases("tunnel")) {
            val t = opened(case)
            assertEquals(case.getString("host_seal_0"), t.seal(hex(case.getString("host_plain")))!!.hex())
            for (name in listOf("phone_seal_0", "phone_seal_1")) {
                val step = t.receive(hex(case.getString(name)))
                assertArrayEquals(hex(case.getString("phone_plain")), (step as Tunnel.Step.Frames).bytes)
            }
        }
    }

    @Test fun `two tunnels of one room seal the same frames differently`() {
        val (first, second) = Vectors.cases("tunnel").map { opened(it).seal(byteArrayOf(1, 2, 3))!!.hex() }
        assertNotEquals(first, second)
    }

    @Test fun `a repeated or skipped counter breaks the tunnel`() {
        val case = Vectors.cases("tunnel").first()
        val repeat = opened(case)
        repeat.receive(hex(case.getString("phone_seal_0")))
        assertTrue(repeat.receive(hex(case.getString("phone_seal_0"))) is Tunnel.Step.Broken)
        assertNull(repeat.seal(byteArrayOf(1)))

        val gap = opened(case)
        assertTrue(gap.receive(hex(case.getString("phone_seal_1"))) is Tunnel.Step.Broken)
        assertTrue(!gap.open)
    }

    /** Someone who has the room token but not the room key reaches the room, never the tunnel. */
    @Test fun `a wrong proof keeps the tunnel shut`() {
        val case = Vectors.cases("tunnel").first()
        val t = tunnel(case)
        t.receive(hex(case.getString("hs1")))
        val forged = hex(case.getString("hs3")).also { it[5] = (it[5].toInt() xor 1).toByte() }
        assertTrue(t.receive(forged) is Tunnel.Step.Broken)
        assertTrue(t.receive(hex(case.getString("phone_seal_0"))) is Tunnel.Step.Broken)
        assertTrue(t.receive(hex(case.getString("hs3"))) is Tunnel.Step.Broken)
        assertNull(t.seal(byteArrayOf(1)))
    }

    @Test fun `a sealed message before the handshake or of another kind is refused`() {
        val case = Vectors.cases("tunnel").first()
        assertTrue(tunnel(case).receive(hex(case.getString("phone_seal_0"))) is Tunnel.Step.Broken)
        assertTrue(tunnel(case).receive(byteArrayOf(9)) is Tunnel.Step.Broken)
        assertTrue(tunnel(case).receive(ByteArray(0)) is Tunnel.Step.Broken)
        assertTrue(tunnel(case).receive(byteArrayOf(1, 2)) is Tunnel.Step.Broken)
    }

    @Test fun `a new HS1 replaces an open tunnel`() {
        val case = Vectors.cases("tunnel").first()
        val t = opened(case)
        assertTrue(t.receive(hex(case.getString("hs1"))) is Tunnel.Step.Send)
        assertTrue(!t.open)
        assertEquals(Tunnel.Step.Opened, t.receive(hex(case.getString("hs3"))))
        val step = t.receive(hex(case.getString("phone_seal_0")))
        assertTrue(step is Tunnel.Step.Frames)
    }
}
