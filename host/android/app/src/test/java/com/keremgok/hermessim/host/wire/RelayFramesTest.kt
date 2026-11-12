// HermesSIM host. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
package com.keremgok.hermessim.host.wire

import com.keremgok.hermessim.host.wire.Vectors.hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The relay socket's messages against protocol/v2/vectors/relay.json. */
class RelayFramesTest {
    @Test fun `every vector parses and encodes to its bytes`() {
        for (case in Vectors.cases("relay")) {
            val bytes = hex(case.getString("bytes"))
            val frame = RelayFrame.parse(bytes)!!
            assertEquals(case.getInt("type"), frame.type)
            assertEquals(case.getInt("slot"), frame.slot)
            assertArrayEquals(hex(case.getString("body")), frame.body)
            assertArrayEquals(bytes, frame.bytes())
        }
        assertNull(RelayFrame.parse(byteArrayOf(5)))
    }

    @Test fun `the host builds the vectors' join and push`() {
        val cases = Vectors.cases("relay").associateBy { it.getString("name") }
        val token = ByteArray(32) { it.toByte() }
        assertEquals(cases.getValue("a host joins room slot 3").getString("bytes"),
            RelayFrames.join(3, RelayFrames.ROLE_HOST, token).bytes().hex())
        val push = RelayFrame.parse(hex(cases.getValue("a text push").getString("bytes")))!!.body
        val collapse = String(push, 36, push[35].toInt())
        val sealed = push.copyOfRange(36 + collapse.length, push.size)
        assertEquals(cases.getValue("a text push").getString("bytes"),
            RelayFrames.push(7, token, RelayFrames.PUSH_KINDS.getValue("sms"), collapse, sealed).bytes().hex())
    }

    @Test fun `a push result names its request and outcome`() {
        val frame = RelayFrame.parse(hex(Vectors.cases("relay").first { it.getString("name") == "no phone registered for the push" }.getString("bytes")))!!
        assertEquals(8, frame.code())
        assertEquals(1, frame.pushResult())
        assertEquals(-1, RelayFrame(RelayFrames.PEER, 0, byteArrayOf(1)).pushResult())
        assertEquals(1, RelayFrame(RelayFrames.PEER, 0, byteArrayOf(1)).flag())
    }
}
