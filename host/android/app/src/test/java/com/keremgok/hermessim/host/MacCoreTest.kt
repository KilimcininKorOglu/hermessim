package com.keremgok.hermessim.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Mac list: by install id, at most 4, one pairing per window, kept across a restart. */
class MacCoreTest {
    private var clock = 1_000L
    private val core = MacCore { clock }
    private fun mac(n: Int) = MacCore.Mac("0$n".repeat(16), "AA:BB:CC:DD:EE:0$n", "Mac $n")

    @Test
    fun theWindowCountsDownAndClosesByItselfAfterTwoMinutes() {
        assertFalse(core.windowOpen())
        core.openWindow()
        clock += 30_500
        assertEquals(89, core.secondsLeft())
        clock += MacCore.WINDOW_MS
        assertFalse(core.windowOpen())
        assertEquals(0, core.secondsLeft())
    }

    @Test
    fun aPairedMacIsListedAndClosesTheWindowUntilItIsRemoved() {
        core.openWindow()
        assertTrue(core.add(mac(1)))
        assertEquals(mac(1), core.byAddress(mac(1).address))
        assertFalse(core.windowOpen())
        assertEquals(mac(1), core.remove(mac(1).id))
        assertNull(core.remove(mac(1).id))
        assertFalse(core.isMac(mac(1).id))
    }

    @Test
    fun theListHoldsFourMacsAtMost() {
        (1..4).forEach { core.openWindow(); assertTrue(core.add(mac(it))) }
        core.openWindow()
        assertFalse(core.add(mac(5)))
        assertEquals(4, core.macs.size)
        assertFalse(core.windowOpen())
    }

    @Test
    fun aMacPairedAgainIsNotListedTwice() {
        core.add(mac(1))
        assertFalse(core.add(mac(1).copy(name = "Renamed")))
        assertEquals(listOf(mac(1)), core.macs)
    }

    @Test
    fun aHelloFromANewAddressOrNameIsKept() {
        core.add(mac(1))
        assertFalse(core.seen(mac(1).id, mac(1).address, mac(1).name))
        assertTrue(core.seen(mac(1).id, "11:22:33:44:55:66", "Office"))
        assertEquals(MacCore.Mac(mac(1).id, "11:22:33:44:55:66", "Office"), core.macs.single())
        assertFalse(core.seen(mac(2).id, "11:22:33:44:55:66", "Stranger"))
    }

    @Test
    fun theListSurvivesStorageAndDropsBrokenEntries() {
        core.add(mac(1).copy(name = "Kerem's Mac | Pro"))
        val old = "AA:BB:CC:DD:EE:01|Mac from the old service"
        val again = MacCore { clock }.apply { load(core.stored() + "broken" + old + "${"g".repeat(32)}|a|b") }
        assertEquals(core.macs, again.macs)
        assertNull(MacCore.decode("no separator"))
    }

    @Test
    fun anIdTravelsAsSixteenBytes() {
        val id = "00112233445566778899aabbccddeeff"
        assertEquals(16, MacCore.idBytes(id).size)
        assertEquals(id, hexOf(MacCore.idBytes(id)))
        assertEquals(id.replaceFirst("00", "80"), hexOf(MacCore.idBytes(id.replaceFirst("00", "80"))))
    }
}
