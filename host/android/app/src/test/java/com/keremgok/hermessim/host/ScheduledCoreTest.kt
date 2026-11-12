package com.keremgok.hermessim.host

import com.google.protobuf.ByteString
import com.keremgok.hermessim.host.wire.StateBook
import com.keremgok.hermessim.wire.CancelScheduled
import com.keremgok.hermessim.wire.Code
import com.keremgok.hermessim.wire.PeerGrants
import com.keremgok.hermessim.wire.Request
import com.keremgok.hermessim.wire.ScheduleText
import com.keremgok.hermessim.wire.SendText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Scheduled texts: what the host takes, keeps and sends when (SPEC.md "Requests", ScheduleText). */
class ScheduledCoreTest {
    private val now = 1_000_000_000L
    private val hour = 3_600_000L

    private fun ref(n: Int): ByteString = ByteString.copyFrom(ByteArray(16) { n.toByte() })

    private fun req(n: Int, at: Long, to: String = "+905321112233", body: String = "hi", line: Int? = null): ScheduleText {
        val text = SendText.newBuilder().setClientRef(ref(n)).setTo(to).setBody(body)
        if (line != null) text.line = line
        return ScheduleText.newBuilder().setText(text).setSendAtMs(at).build()
    }

    @Test fun `a text waits sorted by its time and keeps its line`() {
        val core = ScheduledCore()
        assertNull(core.add(req(1, now + 2 * hour, line = 1), now, lineOff = false))
        assertNull(core.add(req(2, now + hour), now, lineOff = false))
        assertEquals(listOf(ref(2), ref(1)), core.texts.map { it.clientRef })
        assertEquals(1, core.texts[1].line)
        assertFalse(core.texts[0].hasLine())
        assertEquals(now + hour, core.nextAtMs())
    }

    @Test fun `the same ref asked again adds nothing`() {
        val core = ScheduledCore()
        core.add(req(1, now + hour), now, lineOff = false)
        assertNull(core.add(req(1, now + 3 * hour, body = "again"), now, lineOff = false))
        assertEquals(listOf("hi"), core.texts.map { it.body })
    }

    @Test fun `each bad request gets its code and the field it names`() {
        val core = ScheduledCore()
        val refusals = listOf(
            core.add(req(1, now + hour, to = " "), now, lineOff = false),
            core.add(req(2, now + hour, body = ""), now, lineOff = false),
            core.add(req(3, now), now, lineOff = false),
            core.add(req(4, now + ScheduledCore.MAX_AHEAD_MS + 1), now, lineOff = false),
            core.add(req(5, now + hour, line = 0), now, lineOff = true),
        )
        assertEquals(
            listOf(
                ScheduledCore.Refusal(Code.CODE_BAD_VALUE, 1), ScheduledCore.Refusal(Code.CODE_BAD_VALUE, 1),
                ScheduledCore.Refusal(Code.CODE_BAD_VALUE, 2), ScheduledCore.Refusal(Code.CODE_BAD_VALUE, 2),
                ScheduledCore.Refusal(Code.CODE_LINE_OFF, 0),
            ),
            refusals,
        )
        assertEquals(emptyList<Any>(), core.texts)
    }

    @Test fun `the host keeps at most fifty texts`() {
        val core = ScheduledCore()
        repeat(ScheduledCore.MAX_TEXTS) { assertNull(core.add(req(it, now + hour + it), now, lineOff = false)) }
        assertEquals(ScheduledCore.Refusal(Code.CODE_LIMIT_REACHED, 50), core.add(req(99, now + hour), now, lineOff = false))
    }

    @Test fun `due texts leave the list and a cancelled one is taken once`() {
        val core = ScheduledCore()
        core.add(req(1, now + hour), now, lineOff = false)
        core.add(req(2, now + 2 * hour), now, lineOff = false)
        core.add(req(3, now + 3 * hour), now, lineOff = false)
        assertEquals(listOf(ref(1), ref(2)), core.due(now + 2 * hour).map { it.clientRef })
        assertEquals(ref(3), core.take(ref(3))?.clientRef)
        assertNull(core.take(ref(3)))
        assertNull(core.nextAtMs())
    }

    @Test fun `a text more than a day late is dropped, a later one still goes`() {
        val core = ScheduledCore(
            listOf(req(1, now + hour), req(2, now + 30 * hour)).map {
                com.keremgok.hermessim.wire.ScheduledText.newBuilder().setClientRef(it.text.clientRef).setSendAtMs(it.sendAtMs).build()
            },
        )
        val late = now + 26 * hour
        assertEquals(listOf(ref(1)), core.tooLate(late).map { it.clientRef })
        assertEquals(listOf(ref(2)), core.due(now + 30 * hour).map { it.clientRef })
    }

    @Test fun `a Mac may not schedule or cancel and never sees the scheduled group`() {
        val all = PeerGrants.newBuilder().setTexts(true).setControl(true).build()
        val schedule = Request.newBuilder().setScheduleText(req(1, now + hour)).build()
        val cancel = Request.newBuilder().setCancelScheduled(CancelScheduled.newBuilder().setClientRef(ref(1))).build()
        assertEquals(Code.CODE_NOT_ALLOWED, MacRules.refusal(schedule, all))
        assertEquals(Code.CODE_NOT_ALLOWED, MacRules.refusal(cancel, all))
        assertFalse(StateBook.SCHEDULED in MacRules.groups(all))
    }

    @Test fun `a scheduled text has no age limit, its ref guards it`() {
        assertFalse(RequestRules.tooOld(Request.OpCase.SCHEDULE_TEXT, 600_000))
        assertEquals(true, RequestRules.tooOld(Request.OpCase.CANCEL_SCHEDULED, 60_000))
    }
}
