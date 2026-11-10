package com.keremgok.hermessim.host

import com.keremgok.hermessim.protocol.v1.HostAction
import com.keremgok.hermessim.protocol.v1.HostActionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostActionCoreTest {
    @Test fun aCallUpRefusesBothActions() {
        for (a in listOf(HostAction.Action.RECONNECT_NETWORK, HostAction.Action.REBOOT))
            assertEquals(HostActionResult.Failure.IN_CALL, HostActionCore.refusal(a, inCall = true))
    }

    @Test fun withoutACallBothRun() {
        assertNull(HostActionCore.refusal(HostAction.Action.RECONNECT_NETWORK, inCall = false))
        assertNull(HostActionCore.refusal(HostAction.Action.REBOOT, inCall = false))
    }

    @Test fun anUnknownActionFailsEvenWithoutACall() {
        assertEquals(HostActionResult.Failure.FAILED, HostActionCore.refusal(HostAction.Action.ACTION_UNSPECIFIED, inCall = false))
    }

    @Test fun theResultSaysOkOnlyWithoutAFailure() {
        assertTrue(HostActionCore.result(null).ok)
        with(HostActionCore.result(HostActionResult.Failure.IN_CALL)) {
            assertFalse(ok)
            assertEquals(HostActionResult.Failure.IN_CALL, failure)
        }
    }

    @Test fun theHotspotIsAskedBackAfterTheCycleIsOver() {
        assertTrue(HostActionCore.HOTSPOT_RETRY_MS.first() > HostActionCore.CYCLE_SECONDS * 1000L)
    }
}
