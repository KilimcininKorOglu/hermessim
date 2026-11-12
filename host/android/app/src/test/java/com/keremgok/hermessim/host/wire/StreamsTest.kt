package com.keremgok.hermessim.host.wire

import com.keremgok.hermessim.host.wire.Vectors.hex
import com.keremgok.hermessim.host.wire.Vectors.list
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamsTest {
    private fun drain(outbox: Outbox, step: Int = 185): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        while (true) out.write(outbox.take(step).takeIf { it.isNotEmpty() } ?: break)
        return out.toByteArray()
    }

    private fun read(bytes: ByteArray): List<Arrived> {
        val assembler = Assembler()
        return FrameReader().feed(bytes).mapNotNull { assembler.add(it) }
    }

    @Test
    fun everyMessageVectorIsCutIntoItsFramesAndJoinsBack() {
        for (case in Vectors.cases("messages")) {
            val name = case.getString("name")
            val outbox = Outbox(case.getInt("max_body")).apply { limit = Long.MAX_VALUE }
            outbox.queue(hex(case.getString("packet")), case.getInt("stream"))
            val bytes = drain(outbox)
            assertEquals(name, case.getString("bytes"), bytes.hex())
            assertEquals(name, listOf(case.getString("packet")), read(bytes).map { it.packet.hex() })
        }
    }

    @Test
    fun messagesOfOtherStreamsCompleteWhileABulkMessageIsInProgress() {
        for (case in Vectors.cases("interleave")) {
            val arrived = read(hex(case.getString("bytes")))
            val wanted = case.list("messages")
            assertEquals(wanted.map { it.getInt("stream") to it.getString("packet") }, arrived.map { it.stream to it.packet.hex() })
            assertEquals((1L..wanted.size).toList(), arrived.map { it.number })
        }
    }

    /** The target of the rewrite: a call event never waits behind a long transfer. */
    @Test
    fun anUrgentMessageLeavesInTheFrameAfterTheOneBeingSent() {
        val outbox = Outbox().apply { limit = Long.MAX_VALUE }
        outbox.queue(ByteArray(200_000) { 1 }, Wire.BULK, tag = "log")
        val sent = ArrayList<Any?>()
        outbox.onSent = { tag, _ -> sent += tag }
        val reader = FrameReader()
        assertTrue(reader.feed(outbox.take(100)).isEmpty())            // the first bulk frame has started
        outbox.queue(byteArrayOf(7, 7), Wire.URGENT, tag = "call")
        val frames = reader.feed(outbox.take(4_000))
        assertEquals(listOf(Wire.BULK, Wire.URGENT, Wire.BULK), frames.take(3).map { it.stream })
        assertEquals(listOf<Any?>("call"), sent)
    }

    @Test
    fun dataWaitsForCreditAndControlFramesDoNot() {
        val outbox = Outbox()
        outbox.queue(ByteArray(6_000) { 2 }, Wire.BULK)
        val before = FrameReader().feed(drain(outbox))
        assertEquals(4_096, before.sumOf { it.body.size })
        assertTrue(outbox.blocked())
        outbox.queueControl(FrameCodec.credit(1, 9_000))
        assertEquals(listOf(Wire.CREDIT), FrameReader().feed(drain(outbox)).map { it.type })
        outbox.limit = 10_000
        assertFalse(outbox.blocked())
        assertEquals(6_000 - 4_096, FrameReader().feed(drain(outbox)).sumOf { it.body.size })
        assertEquals(1L, outbox.sentMessages)
        assertTrue(outbox.isEmpty())
    }

    @Test
    fun aGrantIsDueAtHalfTheWindowAndASenderPastTheLimitIsRefused() {
        val grant = Grant(window = 8_000)
        assertEquals(1L to 8_000L, FrameCodec.creditOf(grant.frame(received = 1, now = 0)))
        grant.arrived(3_000, whole = false)
        assertFalse(grant.due(now = 100))
        grant.arrived(1_000, whole = false)
        assertTrue(grant.due(now = 100))
        assertEquals(2L to 12_000L, FrameCodec.creditOf(grant.frame(received = 2, now = 100)))
        assertEquals(WireFault.LIMIT_PASSED, assertThrows(WireException::class.java) { grant.arrived(8_001, whole = false) }.fault)
    }

    @Test
    fun aWholeMessageIsAcknowledgedAfterTheQuietTimeAndAnIdleLinkOnceAMinute() {
        val grant = Grant()
        grant.frame(received = 0, now = 0)
        assertFalse(grant.due(now = 59_999))
        assertTrue(grant.due(now = 60_000))
        grant.frame(received = 0, now = 60_000)
        grant.arrived(10, whole = true)
        assertFalse(grant.due(now = 60_100))
        assertTrue(grant.due(now = 60_200))
    }
}
