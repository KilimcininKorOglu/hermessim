package com.keremgok.hermessim.host

import com.keremgok.hermessim.wire.Code
import com.keremgok.hermessim.wire.HostVerb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostActionCoreTest {
    @Test fun aCallUpRefusesBothActionsAndWithoutACallBothRun() {
        val both = listOf(HostVerb.HOST_VERB_REGISTER_AGAIN, HostVerb.HOST_VERB_RESTART)
        assertEquals(listOf(Code.CODE_IN_CALL, Code.CODE_IN_CALL), both.map { HostActionCore.refusal(it, inCall = true) })
        assertEquals(listOf(null, null), both.map { HostActionCore.refusal(it, inCall = false) })
    }

    @Test fun anUnknownActionIsABadValueEvenDuringACall() {
        assertEquals(Code.CODE_BAD_VALUE, HostActionCore.refusal(HostVerb.HOST_VERB_UNSPECIFIED, inCall = false))
        assertEquals(Code.CODE_BAD_VALUE, HostActionCore.refusal(HostVerb.UNRECOGNIZED, inCall = true))
    }

    @Test fun theHotspotIsAskedBackAfterTheCycleIsOver() {
        assertTrue(HostActionCore.HOTSPOT_RETRY_MS.first() > HostActionCore.CYCLE_SECONDS * 1000L)
    }
}
