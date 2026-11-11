package com.keremgok.hermessim.host

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstSeenTest {
    @Test
    fun aTextIsReportedSentOnceWhenTheDeliveryReportFollows() {
        val okSent = FirstSeen(50)
        assertTrue(okSent.first("m1"))                  // the last part went out
        assertFalse(okSent.first("m1"))                 // its delivery report: already told
    }

    @Test
    fun theOldestIdsLeaveOnceTheLimitIsPassed() {
        val seen = FirstSeen(2)
        seen.first("a"); seen.first("b"); seen.first("c")
        assertTrue(seen.first("a"))
        assertFalse(seen.first("c"))
    }
}
