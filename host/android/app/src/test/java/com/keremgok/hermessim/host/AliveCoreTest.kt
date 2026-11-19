package com.keremgok.hermessim.host

import com.keremgok.hermessim.wire.NoticeKind
import com.keremgok.hermessim.wire.Packet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Required behaviour: protocol/README.md "Alive push" (15 minutes, an unreachable notice, the shared collapse name). */
class AliveCoreTest {
    @Test
    fun `the ready push is an unreachable notice stamped with the request time`() {
        val event = Packet.parseFrom(AliveCore.packet(1_700_000_000_000L)).event
        assertEquals(NoticeKind.NOTICE_KIND_UNREACHABLE, event.notice.kind)
        assertEquals(1_700_000_000_000L, event.notice.atMs)
        assertFalse(event.notice.hasLine())
    }

    @Test
    fun `the host says it is on every 15 minutes`() = assertEquals(15 * 60 * 1000L, AliveCore.EVERY_MS)

    @Test
    fun `the push replaces the phone's own unreachable notification`() =
        assertEquals("notice-unreachable", AliveCore.COLLAPSE_NAME)
}
