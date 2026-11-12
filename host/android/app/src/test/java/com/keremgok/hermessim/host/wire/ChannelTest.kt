// HermesSIM host. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
package com.keremgok.hermessim.host.wire

import com.keremgok.hermessim.wire.CallState
import com.keremgok.hermessim.wire.Code
import com.keremgok.hermessim.wire.Event
import com.keremgok.hermessim.wire.LogPart
import com.keremgok.hermessim.wire.Packet
import com.keremgok.hermessim.wire.Reply
import com.keremgok.hermessim.wire.Request
import com.keremgok.hermessim.wire.State
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelTest {
    private var now = 1_000L
    private var ids = 40
    private val channel = Channel({ now }, { ++ids }).apply { newLink() }

    private fun data(body: ByteArray, stream: Int = Wire.NORMAL) = FrameCodec.encode(Frame(Wire.DATA, body, stream = stream))

    /** Everything the channel gives for the link now, as frames. */
    private fun sent(): List<Frame> {
        val reader = FrameReader()
        val frames = ArrayList<Frame>()
        while (true) {
            val bytes = channel.next(180)
            if (bytes.isEmpty()) return frames
            frames += reader.feed(bytes)
        }
    }

    @Test fun `a new session counts its Hello as message one and a resumed one goes on`() {
        channel.feed(data(byteArrayOf(1)))
        assertFalse(channel.open(resumeId = null))
        assertEquals(41, channel.sessionId)
        assertEquals(1L, channel.received)
        channel.feed(data(byteArrayOf(2)) + data(byteArrayOf(3)))
        channel.newLink()
        channel.feed(data(byteArrayOf(4)))
        assertTrue(channel.open(resumeId = 41))
        assertEquals(4L, channel.received)
        channel.feed(data(byteArrayOf(5)))
        assertFalse(channel.open(resumeId = 7))
        assertEquals(42L to 1L, channel.sessionId.toLong() to channel.received)
    }

    @Test fun `a link starts with a credit and the peer's credit lets more data leave`() {
        channel.send(ByteArray(6_000) { 7 }, Wire.BULK)
        val first = sent()
        assertEquals(Wire.CREDIT, first.first().type)
        assertEquals(0L to 16_384L, FrameCodec.creditOf(first.first()))
        assertEquals(4_096, first.drop(1).sumOf { it.body.size })
        assertFalse(channel.ready())
        channel.feed(FrameCodec.encode(FrameCodec.credit(received = 0, limit = 20_000)))
        assertTrue(channel.ready())
        assertEquals(1_904, sent().sumOf { it.body.size })
    }

    @Test fun `a new link drops what waited and starts the byte totals again`() {
        channel.send(ByteArray(9_000), Wire.BULK)
        sent()
        channel.newLink()
        channel.send(ByteArray(3_000), Wire.NORMAL)
        assertEquals(3_000, sent().filter { it.type == Wire.DATA }.sumOf { it.body.size })
    }

    @Test fun `a credit is due 200 ms after a whole message and once a minute on a quiet link`() {
        sent()
        channel.feed(data(byteArrayOf(1)))
        now += 199
        assertFalse(channel.ready())
        now += 1
        assertArrayEquals(FrameCodec.encode(FrameCodec.credit(1, 16_385)), channel.next(180))
        now += 59_999
        assertFalse(channel.ready())
        now += 1
        assertTrue(channel.ready())
    }

    @Test fun `a peer that sends past its limit breaks the rules and gets a reset`() {
        val fault = runCatching { repeat(5) { channel.feed(data(ByteArray(1_000))) } }.exceptionOrNull() as WireException
        assertEquals(WireFault.LIMIT_PASSED, fault.fault)
        channel.reset(fault.fault)
        assertEquals(listOf(Frame(Wire.RESET, byteArrayOf(4))), sent().filter { it.type == Wire.RESET })
        assertFalse(channel.serving)
    }

    @Test fun `a link is dead after 150 s without a frame`() {
        channel.feed(data(byteArrayOf(1)))
        now += 149_999
        assertFalse(channel.silent())
        now += 1
        assertTrue(channel.silent())
    }

    @Test fun `calls travel urgent, a log part and a long message bulk, the rest normal`() {
        val call = Packet.newBuilder().setEvent(Event.newBuilder().setCall(CallState.newBuilder().setCall(3))).build()
        val reply = Packet.newBuilder().setReply(Reply.newBuilder().setId(2).setCode(Code.CODE_OK)).build()
        val log = Packet.newBuilder().setReply(Reply.newBuilder().setLog(LogPart.newBuilder().setText("x"))).build()
        val state = Packet.newBuilder().setState(State.newBuilder().setSeq(1)).build()
        assertEquals(
            listOf(Wire.URGENT, Wire.NORMAL, Wire.URGENT, Wire.BULK, Wire.NORMAL, Wire.BULK),
            listOf(StreamChoice.of(call, 20), StreamChoice.of(reply, 6), StreamChoice.of(reply, 6, Request.OpCase.CALL_CONTROL),
                StreamChoice.of(log, 9), StreamChoice.of(state, 4_096), StreamChoice.of(state, 4_097)),
        )
    }
}
