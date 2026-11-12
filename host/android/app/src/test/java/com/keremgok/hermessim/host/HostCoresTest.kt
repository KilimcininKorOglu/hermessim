// HermesSIM host. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import com.keremgok.hermessim.wire.CallEnd
import com.keremgok.hermessim.wire.CallPhase
import com.keremgok.hermessim.wire.CallState
import com.keremgok.hermessim.wire.Event
import com.keremgok.hermessim.wire.Notice
import com.keremgok.hermessim.wire.NoticeKind
import com.keremgok.hermessim.wire.Packet
import com.keremgok.hermessim.wire.TextIn
import com.keremgok.hermessim.wire.TextPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The parts of texts, pushes, calls and state that need no phone: TextBook, PushCore, CallCore, StateCore. */
class HostCoresTest {
    private val ref = "00112233445566778899aabbccddeeff"

    @Test fun `a text asked for twice with one client ref has one seq`() {
        val book = TextBook()
        assertNull(book.seqOf(ref))
        book.queued(7, ref)
        assertEquals(7L, book.seqOf(ref))
        assertNull(book.seqOf(ref.reversed()))
    }

    @Test fun `phases only move forward, and a late delivery report still counts`() {
        val book = TextBook()
        book.queued(7, ref)
        assertNull(book.advance(7, TextPhase.TEXT_PHASE_QUEUED))
        assertEquals(TextPhase.TEXT_PHASE_SENT, book.advance(7, TextPhase.TEXT_PHASE_SENT)?.phase)
        assertNull(book.advance(7, TextPhase.TEXT_PHASE_FAILED, 1))
        assertEquals(TextPhase.TEXT_PHASE_DELIVERED, book.advance(7, TextPhase.TEXT_PHASE_DELIVERED)?.phase)
        assertNull(book.advance(7, TextPhase.TEXT_PHASE_SENT))

        book.queued(8, "")
        val failed = book.advance(8, TextPhase.TEXT_PHASE_FAILED, 4)
        assertEquals(4, failed?.error)
        assertEquals(TextPhase.TEXT_PHASE_DELIVERED, book.advance(8, TextPhase.TEXT_PHASE_DELIVERED)?.phase)
    }

    @Test fun `a status carries the text's seq and the client's ref`() {
        val book = TextBook()
        book.queued(7, ref)
        val status = book.advance(7, TextPhase.TEXT_PHASE_SENT)
        assertEquals(7L, status?.text)
        assertEquals(ref, status?.clientRef?.let(CallCore::refText))
    }

    @Test fun `a status nobody heard is kept until a peer hears it`() {
        val book = TextBook()
        book.queued(7, ref)
        assertTrue(book.untold().isEmpty())
        book.advance(7, TextPhase.TEXT_PHASE_SENT)
        assertEquals(listOf(7L), book.untold().map { it.text })
        book.told(7)
        assertTrue(book.untold().isEmpty())
    }

    @Test fun `a text of before the host app started again still gets its status`() {
        assertEquals(TextPhase.TEXT_PHASE_DELIVERED, TextBook().advance(3, TextPhase.TEXT_PHASE_DELIVERED)?.phase)
    }

    @Test fun `the book keeps the newest texts only`() {
        val book = TextBook(keep = 2)
        (1L..3L).forEach { book.queued(it, "0$it") }
        assertNull(book.seqOf("01"))
        assertEquals(3L, book.seqOf("03"))
    }

    @Test fun `a push carries the event as a packet, with a long text cut to fit`() {
        val short = Event.newBuilder().setSeq(5).setTextIn(TextIn.newBuilder().setFrom("+90555").setBody("merhaba")).build()
        assertEquals(short, Packet.parseFrom(PushCore.packet(short)).event)
        val long = short.toBuilder().setTextIn(short.textIn.toBuilder().setBody("ş".repeat(2_000))).build()
        val pushed = Packet.parseFrom(PushCore.packet(long)).event
        assertEquals(5L, pushed.seq)
        assertEquals("ş".repeat(750) + "…", pushed.textIn.body)
    }

    @Test fun `a newer notice replaces the older one of its kind and line`() {
        val battery = Notice.newBuilder().setKind(NoticeKind.NOTICE_KIND_BATTERY_LOW).setValue(14).build()
        val locked = Notice.newBuilder().setKind(NoticeKind.NOTICE_KIND_LINE_LOCKED).setLine(0).build()
        assertEquals("notice-1-x", PushCore.noticeId(battery))
        assertEquals("notice-3-0", PushCore.noticeId(locked))
        assertTrue(PushCore.validCollapseId(PushCore.noticeId(locked)))
        assertFalse(PushCore.validCollapseId("a b"))
        assertFalse(PushCore.validCollapseId("x".repeat(65)))
    }

    @Test fun `a call's phase and end come from the phone system's numbers`() {
        assertEquals(CallPhase.CALL_PHASE_RINGING, CallCore.phase(android.telecom.Call.STATE_RINGING))
        assertEquals(CallPhase.CALL_PHASE_HELD, CallCore.phase(android.telecom.Call.STATE_HOLDING))
        assertEquals(CallPhase.CALL_PHASE_ENDED, CallCore.phase(android.telecom.Call.STATE_DISCONNECTING))
        assertEquals(CallPhase.CALL_PHASE_DIALING, CallCore.phase(android.telecom.Call.STATE_CONNECTING))
        assertEquals(CallEnd.CALL_END_MISSED, CallCore.end(android.telecom.DisconnectCause.MISSED))
        assertEquals(CallEnd.CALL_END_UNSPECIFIED, CallCore.end(null))
    }

    @Test fun `a peer hears of a call when its phase or the far end's ringing changes`() {
        val dialing = CallState.newBuilder().setCall(1).setPhase(CallPhase.CALL_PHASE_DIALING).build()
        assertTrue(CallCore.changed(null, dialing))
        assertFalse(CallCore.changed(dialing, dialing.toBuilder().setPhaseSinceMs(9).build()))
        assertTrue(CallCore.changed(dialing, dialing.toBuilder().setFarEndRings(true).build()))
        assertTrue(CallCore.changed(dialing, dialing.toBuilder().setPhase(CallPhase.CALL_PHASE_ACTIVE).build()))
    }

    @Test fun `a client ref keeps its bytes as text, and tones are keypad keys`() {
        assertEquals(ref, CallCore.refText(CallCore.refBytes(ref)))
        assertEquals(0, CallCore.refBytes("").size())
        assertTrue(CallCore.validTones("12*#AD"))
        assertFalse(CallCore.validTones(""))
        assertFalse(CallCore.validTones("12x"))
    }

    @Test fun `a line of the state carries the SIM card's id as 8 bytes`() {
        val line = StateCore.line(SimLine(slot = 1, carrier = "Turkcell", enabled = true, card = "0011223344556677", attemptsLeft = 3))
        assertEquals(8, line.card.size())
        assertEquals(0x77, line.card.byteAt(7).toInt())
        assertFalse(line.hasTriesLeft())                              // the line is not locked
        assertEquals(0, StateCore.card("xyz").size())
        val locked = StateCore.line(SimLine(slot = 0, lock = com.keremgok.hermessim.wire.LineLock.LINE_LOCK_PIN, attemptsLeft = 2))
        assertEquals(2, locked.triesLeft)
    }

    @Test fun `the network group names the line that carries data and the uplink`() {
        val sims = listOf(SimLine(slot = 0, carrier = "A"), SimLine(slot = 1, carrier = "B", defaultData = true, signal = 9))
        val mobile = StateCore.network(true, com.keremgok.hermessim.wire.Uplink.UPLINK_MOBILE, sims, wifiName = "Home")
        assertEquals("B" to 1, mobile.uplinkName to mobile.dataLine)
        val wifi = StateCore.network(false, com.keremgok.hermessim.wire.Uplink.UPLINK_WIFI, emptyList(), wifiName = "Home")
        assertEquals("Home", wifi.uplinkName)
        assertFalse(wifi.hasDataLine())
        assertEquals(listOf(0, 4), StateCore.bars(sims))
    }
}
