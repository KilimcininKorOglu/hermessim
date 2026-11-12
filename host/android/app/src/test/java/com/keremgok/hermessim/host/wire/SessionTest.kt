package com.keremgok.hermessim.host.wire

import com.keremgok.hermessim.host.wire.Vectors.hex
import com.keremgok.hermessim.wire.Code
import com.keremgok.hermessim.wire.Dial
import com.keremgok.hermessim.wire.Packet
import com.keremgok.hermessim.wire.Reply
import com.keremgok.hermessim.wire.Request
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTest {
    private class MemoryStore : EventStore {
        val values = HashMap<String, String>()
        override fun read(key: String) = values[key]
        override fun write(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
        override fun keys(prefix: String) = values.keys.filter { it.startsWith(prefix) }
    }

    @Test
    fun theHighestVersionBothSpeakIsUsedAndNoCommonVersionIsRefused() {
        val cases = listOf(Triple(2, 2, 2), Triple(1, 2, 2), Triple(1, 3, 3), Triple(2, 5, 3), Triple(4, 5, null), Triple(1, 1, null))
        assertEquals(cases.map { it.third }, cases.map { (min, max, _) -> commonVersion(min, max, hostMin = 2, hostMax = 3) })
    }

    @Test
    fun aRequestAnsweredBeforeGetsTheSameReplyAndTheOldestReplyLeavesFirst() {
        val cache = ReplyCache(size = 2)
        cache.keep(1, byteArrayOf(1)); cache.keep(2, byteArrayOf(2))
        assertArrayEquals(byteArrayOf(1), cache.find(1))
        cache.keep(3, byteArrayOf(3))
        assertNull(cache.find(1))
        assertArrayEquals(byteArrayOf(3), cache.find(3))
    }

    @Test
    fun aHelloGoesOnWithTheSessionItNamesAndAnyOtherStartsANewOne() {
        var n = 40
        val desk = SessionDesk { ++n }
        assertFalse(desk.open(resumeId = null))
        assertEquals(41, desk.id)
        assertTrue(desk.open(resumeId = 41))
        assertFalse(desk.open(resumeId = 9))
        assertEquals(42, desk.id)
    }

    @Test
    fun textsKeepTheirNumbersAcrossARestartUntilAPeerStoredThem() {
        val store = MemoryStore()
        val log = EventLog(store)
        val seqs = List(3) { log.addText { seq -> byteArrayOf(seq.toByte()) }.first }
        assertEquals(listOf(1L, 2L, 3L), seqs)
        val restarted = EventLog(store)
        assertEquals(listOf(2L, 3L), restarted.textsAfter(cursor = 1).map { it.first })
        restarted.ack(upTo = 2)
        assertEquals(listOf(3L), restarted.textsAfter(cursor = 0).map { it.first })
        assertEquals(4L, restarted.addText { byteArrayOf(0) }.first)
        assertEquals(listOf(4L, 1L, 2L), listOf(restarted.textCursor, restarted.nextCall(), EventLog(store).nextCall()))
    }

    @Test
    fun everyPacketVectorParsesAndSerializesToTheSameBytes() {
        for (case in Vectors.cases("packets")) {
            val bytes = hex(case.getString("bytes"))
            assertEquals(case.getString("name"), case.getString("bytes"), Packet.parseFrom(bytes).toByteArray().hex())
        }
    }

    @Test
    fun packetsBuiltFromTheirFieldsGiveTheVectorBytes() {
        val byName = Vectors.cases("packets").associate { it.getString("name") to it.getString("bytes") }
        val dialLineZero = Packet.newBuilder().setRequest(Request.newBuilder().setId(5).setDial(Dial.newBuilder().setNumber("112").setLine(0))).build()
        val wrongPin = Packet.newBuilder().setReply(Reply.newBuilder().setId(6).setCode(Code.CODE_WRONG_PIN).addArgs(2)).build()
        val negative = Packet.newBuilder().setReply(Reply.newBuilder().setId(7).setCode(Code.CODE_FAILED).addArgs(-3)).build()
        assertEquals(byName["dial on line 0"], dialLineZero.toByteArray().hex())
        assertEquals(byName["wrong PIN, two tries left"], wrongPin.toByteArray().hex())
        assertEquals(byName["a negative argument"], negative.toByteArray().hex())
    }
}
