package com.keremgok.hermessim.host

import com.keremgok.hermessim.host.SilentLinkCore.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SilentLinkCoreTest {

    @Test
    fun `the steps run only for a silent bonded phone outside the pairing window`() {
        assertEquals(true, SilentLinkCore.mayHelp(stillSilent = true, pairingOpen = false, bonded = true))
        assertEquals(false, SilentLinkCore.mayHelp(stillSilent = false, pairingOpen = false, bonded = true))
        assertEquals(false, SilentLinkCore.mayHelp(stillSilent = true, pairingOpen = true, bonded = true))
        assertEquals(false, SilentLinkCore.mayHelp(stillSilent = true, pairingOpen = false, bonded = false))
    }

    @Test
    fun `telling the phone counts only for a Hello on the same link`() {
        assertEquals(Step.SERVICES_CHANGED, SilentLinkCore.recoveredBy(Step.SERVICES_CHANGED, stepLink = 3, helloLink = 3))
        assertNull(SilentLinkCore.recoveredBy(Step.SERVICES_CHANGED, stepLink = 3, helloLink = 4))
        assertEquals(Step.SERVICES_CHANGED_REFUSED, SilentLinkCore.recoveredBy(Step.SERVICES_CHANGED_REFUSED, 2, 2))
    }

    @Test
    fun `a drop or a reset counts only for a Hello on the very next link`() {
        assertEquals(Step.DROP, SilentLinkCore.recoveredBy(Step.DROP, stepLink = 3, helloLink = 4))
        assertNull(SilentLinkCore.recoveredBy(Step.DROP, stepLink = 3, helloLink = 3))
        assertNull(SilentLinkCore.recoveredBy(Step.BLUETOOTH_RESET, stepLink = 3, helloLink = 5))
        assertEquals(Step.BLUETOOTH_RESET, SilentLinkCore.recoveredBy(Step.BLUETOOTH_RESET, 1, 2))
        assertNull(SilentLinkCore.recoveredBy(null, 1, 2))
    }

    @Test
    fun `the place note names the old place only when the service moved`() {
        assertEquals(" at place 40", SilentLinkCore.placeNote(40, 0))
        assertEquals(" at place 40", SilentLinkCore.placeNote(40, 40))
        assertEquals(" at place 44 (the phone last said Hello with it at 40)", SilentLinkCore.placeNote(44, 40))
    }

    @Test
    fun `the wait before the steps is five seconds`() {
        assertEquals(5000L, SilentLinkCore.SILENT_MS)
    }
}
