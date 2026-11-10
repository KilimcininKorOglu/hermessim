package com.keremgok.hermessim.host

import com.keremgok.hermessim.protocol.v1.MacStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MacCoreTest {
    private var clock = 1_000L
    private val core = MacCore { clock }
    private fun mac(n: Int) = MacCore.Mac("AA:BB:CC:DD:EE:0$n", "Mac $n")

    @Test
    fun aStrangerIsRefusedUntilTheWindowOpens() {
        assertEquals(MacCore.Access.DENY, core.access(mac(1).address))
        core.openWindow()
        assertEquals(MacCore.Access.ADMIT, core.access(mac(1).address))
        clock += MacCore.WINDOW_MS
        assertEquals(MacCore.Access.DENY, core.access(mac(1).address))       // closed by itself after 120 s
    }

    @Test
    fun anAdmittedMacStaysAllowedAndClosesTheWindow() {
        core.openWindow()
        core.admit(mac(1))
        assertEquals(MacCore.Access.ALLOW, core.access(mac(1).address))
        assertFalse(core.windowOpen())
        assertEquals(MacCore.Access.DENY, core.access(mac(2).address))       // one pairing per window
    }

    @Test
    fun theListHoldsFourMacsAtMost() {
        (1..4).forEach { core.openWindow(); core.admit(mac(it)) }
        core.openWindow()
        assertEquals(MacCore.Access.DENY, core.access(mac(5).address))
        core.admit(mac(5))
        assertEquals(4, core.macs.size)
    }

    @Test
    fun aRemovedMacIsAStrangerAgain() {
        core.openWindow(); core.admit(mac(1))
        assertTrue(core.remove(mac(1).address))
        assertFalse(core.remove(mac(1).address))
        assertEquals(MacCore.Access.DENY, core.access(mac(1).address))
    }

    @Test
    fun theListSurvivesStorageAndDropsBrokenEntries() {
        core.openWindow(); core.admit(MacCore.Mac("AA:BB:CC:DD:EE:01", "Kerem's Mac | Pro"))
        val again = MacCore { clock }.apply { load(core.stored() + "broken" + "|nameless") }
        assertEquals(core.macs, again.macs)
        assertNull(MacCore.decode("no separator"))
    }

    @Test
    fun theWindowCountsDownInSeconds() {
        core.openWindow()
        clock += 30_500
        assertEquals(89, core.secondsLeft())
        core.closeWindow()
        assertEquals(0, core.secondsLeft())
    }

    @Test
    fun theMacSeesOnStartingFailedOrOff() {
        assertEquals(MacStatus.State.ON, MacCore.state(on = true, starting = false, failed = true))
        assertEquals(MacStatus.State.STARTING, MacCore.state(on = false, starting = true, failed = true))
        assertEquals(MacStatus.State.FAILED, MacCore.state(on = false, starting = false, failed = true))
        assertEquals(MacStatus.State.OFF, MacCore.state(on = false, starting = false, failed = false))
    }
}
