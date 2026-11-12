// HermesSIM host. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
package com.keremgok.hermessim.host.wire

import com.keremgok.hermessim.wire.Hello
import com.keremgok.hermessim.wire.Packet
import com.keremgok.hermessim.wire.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the host's end of a channel does after its own RESET (SPEC.md "RESET"). */
class ChannelResetTest {
    private val channel = Channel({ 1_000L }, { 41 }).apply { newLink() }

    private val hello = Packet.newBuilder().setHello(Hello.newBuilder().setMinVersion(2).setMaxVersion(2)).build()
        .let { FrameCodec.encode(Frame(Wire.DATA, it.toByteArray(), stream = Wire.URGENT)) }
    private val request = Packet.newBuilder().setRequest(Request.newBuilder().setId(1)).build()
        .let { FrameCodec.encode(Frame(Wire.DATA, it.toByteArray(), stream = Wire.NORMAL)) }

    /** 1 000 bytes of a message that is not whole yet. */
    private val filler = FrameCodec.encode(Frame(Wire.DATA, ByteArray(1_000), stream = Wire.NORMAL))

    /** The DATA bytes the channel gives for the link now. */
    private fun sentData(): Int {
        val reader = FrameReader()
        var bytes = 0
        while (true) {
            val value = channel.next(180)
            if (value.isEmpty()) return bytes
            bytes += reader.feed(value).filter { it.type == Wire.DATA }.sumOf { it.body.size }
        }
    }

    @Test fun `after a reset the host reads nothing until a value starts with a Hello`() {
        channel.reset(WireFault.NO_SESSION)
        // The rest of a frame the peer was sending, then a whole message of before the reset: both are dropped
        assertEquals(0, channel.feed(byteArrayOf(0x7F, 0x01, 0x02)).size)
        assertEquals(0, channel.feed(request).size)
        assertEquals(0, channel.feed(request + hello).size)
        val arrived = channel.feed(hello + request)
        assertEquals(2, arrived.size)
        assertTrue(Packet.parseFrom(arrived[0].packet).hasHello())
        assertEquals(1, channel.feed(request).size)
    }

    @Test fun `a reset starts the byte totals again in both directions`() {
        repeat(4) { channel.feed(filler) }
        channel.send(ByteArray(4_000), Wire.NORMAL)
        sentData()
        channel.reset(WireFault.BAD_PACKET)
        sentData()
        channel.feed(hello)
        repeat(4) { channel.feed(filler) }                                // 4 000 bytes again, inside the first limit
        channel.send(ByteArray(4_000), Wire.NORMAL)
        assertEquals(4_000, sentData())
    }
}
