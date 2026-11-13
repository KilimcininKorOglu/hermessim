// HermesSIM host. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import org.junit.Assert.assertEquals
import org.junit.Test

class AdvertPaceTest {
    private data class Case(val why: String, val pairable: Boolean, val phoneLinked: Boolean, val ms: Long, val slow: Boolean)

    @Test fun theHostSlowsDownOnlyTwoMinutesAfterThePhoneLeft() {
        val cases = listOf(
            Case("just left", pairable = false, phoneLinked = false, ms = 119_999, slow = false),
            Case("two minutes", pairable = false, phoneLinked = false, ms = 120_000, slow = true),
            Case("an hour", pairable = false, phoneLinked = false, ms = 3_600_000, slow = true),
            // The Add host sheet lists it: the user is looking at the phone right now
            Case("waiting to be paired", pairable = true, phoneLinked = false, ms = 3_600_000, slow = false),
            // Only a Mac or nothing at all is up: the phone must find its host at once when it comes back
            Case("a phone is linked", pairable = false, phoneLinked = true, ms = 3_600_000, slow = false),
        )
        for (c in cases) assertEquals(c.why, c.slow, AdvertPace.slow(c.pairable, c.phoneLinked, c.ms))
    }

    @Test fun theWaitCountsDownToTheSlowInterval() {
        assertEquals(listOf(120_000L, 1L, 0L, 0L), listOf(0L, 119_999L, 120_000L, 500_000L).map(AdvertPace::waitMs))
    }
}
