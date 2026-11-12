// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import com.keremgok.hermessim.wire.CallRules
import org.junit.Assert.assertEquals
import org.junit.Test

/** Blocked calls and texts never reach the user; the busy reply goes to real numbers, once an hour each. */
class CallRulesCoreTest {
    private fun core(busy: Boolean = true, hidden: Boolean = false, vararg blocked: String) = CallRulesCore().apply {
        set(CallRules.newBuilder().addAllBlocked(blocked.toList()).setBlockHidden(hidden).setBusy(busy).setBusyText("Busy").build())
    }

    @Test fun numbersMatchOnTheirLastTenDigitsAndHiddenCallsOnlyWithTheSwitch() {
        val c = core(hidden = true, blocked = arrayOf("0532 111 22 33"))
        val calls = listOf("+905321112233" to false, "05321112233" to false, "+905329999999" to false, "" to true)
            .map { (n, hidden) -> c.blocksCall(n, hidden) }
        assertEquals(listOf(true, true, false, true), calls)
        assertEquals(false, core(blocked = arrayOf("05321112233")).blocksCall("", withheld = true))
        assertEquals(true, c.blocksText("+90 532 111 22 33"))
    }

    @Test fun theBusyReplyGoesToRealNumbersOnceAnHour() {
        val c = core(blocked = arrayOf("+905321112233"))
        val h = CallRulesCore.HOUR_MS
        val replies = listOf(
            "+905551234567" to 0L, "+905551234567" to h - 1, "+905551234567" to h,   // once an hour
            "4545" to 0L, "VODAFONE" to 0L, "" to 0L,                               // short code, letters, hidden
            "+905321112233" to 0L,                                                    // blocked
        ).map { (from, at) -> c.autoReply(from, at) }
        assertEquals(listOf(true, false, true, false, false, false, false), replies)
        assertEquals(false, core(busy = false).autoReply("+905551234567", 0))
    }

    @Test fun theRulesAreCutToTheirLimits() {
        val c = CallRulesCore()
        val numbers = (1..60).map { "+90555000%04d".format(it) } + "+905550000001" + "  "
        c.set(CallRules.newBuilder().addAllBlocked(numbers).setBusyText(" " + "ş".repeat(200) + " ").build())
        assertEquals(listOf(50, 150), listOf(c.rules.blockedCount, c.rules.busyText.length))
    }
}
