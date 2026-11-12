package com.keremgok.hermessim.host

import com.keremgok.hermessim.protocol.v1.Envelope
import com.keremgok.hermessim.protocol.v1.SmsReceived
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunksTest {
    // A 3 000-character text in 3-byte UTF-8: about 9 KB, which no single 4 KB frame carries.
    private val longText = Envelope.newBuilder().setSeq(7)
        .setSmsReceived(SmsReceived.newBuilder().setId("m1").setBody("ş".repeat(1_500) + "€".repeat(1_500)))
        .build().toByteArray()

    @Test
    fun aLongTextArrivesWholeInAnyOrderAndEveryPieceFitsAFrame() {
        val chunks = Chunks.split(longText, transferId = 7)
        assertTrue(chunks.size > 1)
        chunks.forEach { chunk ->
            val wrapped = Envelope.newBuilder().setChunk(chunk).build().toByteArray()
            assertTrue("a chunk envelope is ${wrapped.size} bytes", wrapped.size <= Framing.MAX_FRAME)
        }
        val joiner = ChunkJoiner(now = { 0L })
        val out = chunks.reversed().mapNotNull { joiner.add(it) }
        assertEquals(1, out.size)
        assertArrayEquals(longText, out.single())
        assertEquals(3_000, Envelope.parseFrom(out.single()).smsReceived.body.length)
    }

    @Test
    fun aTransferMissingAPieceIsDroppedAfterThirtySeconds() {
        var clock = 0L
        val joiner = ChunkJoiner(now = { clock })
        val chunks = Chunks.split(longText, transferId = 7)
        chunks.drop(1).forEach { assertNull(joiner.add(it)) }
        clock = 30_001
        // The first piece comes too late: the rest is gone, so nothing completes.
        assertNull(joiner.add(chunks.first()))
    }

    @Test
    fun aChunkOutsideTheLimitsIsRefused() {
        val joiner = ChunkJoiner(now = { 0L })
        val tooMany = Chunks.split(ByteArray(65 * 10), transferId = 1, slice = 10)
        assertNull(joiner.add(tooMany.first()))
        assertNotNull(joiner.refused)
    }

    @Test
    fun aDuplicatedPieceDoesNotCompleteATransferEarly() {
        val joiner = ChunkJoiner(now = { 0L })
        val chunks = Chunks.split(longText, transferId = 9)
        assertNull(joiner.add(chunks.first()))
        assertNull(joiner.add(chunks.first()))
        val out = chunks.drop(1).mapNotNull { joiner.add(it) }
        assertArrayEquals(longText, out.single())
    }
}
