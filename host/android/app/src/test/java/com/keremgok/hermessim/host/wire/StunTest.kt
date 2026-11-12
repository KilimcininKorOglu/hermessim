package com.keremgok.hermessim.host.wire

import com.keremgok.hermessim.host.wire.Vectors.hex
import com.keremgok.hermessim.host.wire.Vectors.list
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress

/** STUN messages against protocol/v2/vectors/stun.json (RFC 5769). */
class StunTest {
    private val stun = Vectors.cases("stun").single()

    @Test fun everyMessageDecodesAndItsIntegrityIsChecked() = stun.list("messages").forEach { case ->
        val name = case.getString("name")
        val m = Stun.decode(hex(case.getString("bytes")))!!
        assertEquals(name, case.getInt("type"), m.type)
        assertEquals(name, case.getString("tid"), m.tid.hex())
        assertEquals(name, case.getBoolean("integrity"), Stun.integrityOk(m, hex(case.getString("key"))))
        case.optJSONObject("xor_mapped")?.let { want ->
            val got = m.address(Stun.XOR_MAPPED)!!
            assertEquals(name, want.getString("address"), got.address.address.hex())
            assertEquals(name, want.getInt("port"), got.port)
        }
    }

    @Test fun theLongTermKeyIsTheRfcKey() {
        val case = stun.list("messages").first { it.getString("name") == "RFC 5769 request with a long-term key" }
        val key = Stun.longTermKey("マトリックス", "example.org".toByteArray(), "TheMatrIX")
        assertEquals(case.getString("key"), key.hex())
    }

    @Test fun addressesEncodeAndDecodeBothFamilies() = stun.list("addresses").forEach { case ->
        val tid = hex(case.getString("tid"))
        val a = InetSocketAddress(InetAddress.getByAddress(hex(case.getString("address"))), case.getInt("port"))
        assertEquals(case.getString("name"), case.getString("value"), Stun.xorValue(a, tid).hex())
        assertEquals(case.getString("name"), a, Stun.xorAddress(hex(case.getString("value")), tid))
    }

    @Test fun aMessageWeSealCarriesIntegrityTheOtherSideChecks() {
        val key = "secret".toByteArray()
        val tid = ByteArray(12) { it.toByte() }
        val bytes = Stun.encode(Stun.ALLOCATE, tid, listOf(Stun.Attr(Stun.TRANSPORT, byteArrayOf(17, 0, 0, 0))), key)
        val m = Stun.decode(bytes)!!
        assertTrue(Stun.integrityOk(m, key))
        assertFalse(Stun.integrityOk(m, "other".toByteArray()))
        assertArrayEquals(byteArrayOf(17, 0, 0, 0), m.attrs[Stun.TRANSPORT])
        assertEquals(Stun.ALLOCATE, m.method)
    }

    @Test fun audioAndChannelDataAreNotStun() {
        assertNull(Stun.decode(ByteArray(24).also { it[0] = 0xA6.toByte() }))
        assertNull(Stun.decode(ByteArray(24).also { it[0] = 0x40 }))
    }
}
