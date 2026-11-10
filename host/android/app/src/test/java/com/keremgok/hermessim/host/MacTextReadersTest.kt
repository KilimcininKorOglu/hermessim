package com.keremgok.hermessim.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PairedMac.texts: which paired Macs may read the texts characteristic. */
class MacTextReadersTest {
    private val core = MacCore { 0L }
    private val listed = "AA:BB:CC:DD:EE:01"
    private val stranger = "AA:BB:CC:DD:EE:02"

    @Test
    fun onlyAListedMacMayReadTheTextsAndRemovingItForgetsThat() {
        core.openWindow(); core.admit(MacCore.Mac(listed, "Mac"))
        assertFalse(core.texts(listed))                                      // off for a new Mac
        assertFalse(core.setTexts(stranger, true))                           // a stranger can't be allowed
        assertTrue(core.setTexts(listed, true))
        assertFalse(core.setTexts(listed, true))                             // no change
        val again = MacCore { 0L }.apply { load(core.stored()); loadTexts(core.storedTexts() + stranger) }
        assertEquals(setOf(listed), again.storedTexts())                     // a stored stranger is dropped
        core.remove(listed)
        assertFalse(core.texts(listed))
    }
}
