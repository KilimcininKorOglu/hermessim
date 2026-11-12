package com.keremgok.hermessim.host.wire

import com.keremgok.hermessim.host.wire.Vectors.hex
import com.keremgok.hermessim.host.wire.Vectors.list
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FramesTest {
    private fun frames(case: org.json.JSONObject): List<Frame> = case.list("frames").map {
        Frame(it.getInt("type"), hex(it.getString("body")), it.getBoolean("more"), it.getInt("stream"))
    }

    @Test
    fun everyVectorEncodesToItsBytesAndReadsBackWholeOrByteByByte() {
        for (case in Vectors.cases("frames")) {
            val name = case.getString("name")
            val bytes = hex(case.getString("bytes"))
            assertEquals(name, case.getString("bytes"), frames(case).joinToString("") { FrameCodec.encode(it).hex() })
            assertEquals(name, frames(case), FrameReader().feed(bytes))
            val reader = FrameReader()
            assertEquals(name, frames(case), bytes.flatMap { reader.feed(byteArrayOf(it)) })
        }
    }

    @Test
    fun aHeaderOrLengthOutsideTheRulesResetsTheLink() {
        val bad = listOf(
            byteArrayOf(0x01, 0x00) to WireFault.BAD_HEADER,                    // reserved bit
            byteArrayOf(0xC0.toByte(), 0x00) to WireFault.BAD_HEADER,           // type 3
            byteArrayOf(0x18, 0x00) to WireFault.BAD_HEADER,                    // stream 3
            byteArrayOf(0x08, 0x81.toByte(), 0x08) to WireFault.BAD_LENGTH,     // 1025 bytes
        )
        for ((bytes, fault) in bad) {
            assertEquals(fault, assertThrows(WireException::class.java) { FrameReader().feed(bytes) }.fault)
        }
    }

    @Test
    fun aCreditFrameCarriesTheMessagesReceivedAndTheLimit() {
        assertEquals(5L to 20_000L, FrameCodec.creditOf(FrameCodec.credit(received = 5, limit = 20_000)))
        assertEquals("800102", FrameCodec.encode(WireFault.BAD_LENGTH.frame).hex())   // the RESET vector
        assertEquals(listOf(127L, 128L, 262_144L), listOf(127L, 128L, 262_144L).map { Leb128.decode(Leb128.encode(it), 0)!!.first })
    }
}
