package com.keremgok.hermessim.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PairedMac.texts and PairedMac.control: which paired Macs may read the texts, and which may change host settings. */
class MacTextReadersTest {
    private val core = MacCore { 0L }
    private val listed = "AA:BB:CC:DD:EE:01"
    private val stranger = "AA:BB:CC:DD:EE:02"

    /** One permission as MacCore keeps it: set, ask, store and load. */
    private class Permission(
        val set: MacCore.(String, Boolean) -> Boolean,
        val has: MacCore.(String) -> Boolean,
        val stored: MacCore.() -> Set<String>,
        val load: MacCore.(Collection<String>) -> Unit,
    )

    private val permissions = listOf(
        Permission(MacCore::setTexts, MacCore::texts, MacCore::storedTexts, MacCore::loadTexts),
        Permission(MacCore::setControl, MacCore::control, MacCore::storedControl, MacCore::loadControl),
    )

    @Test
    fun onlyAListedMacMayBeAllowedAndRemovingItForgetsThat() {
        core.openWindow(); core.admit(MacCore.Mac(listed, "Mac"))
        for (p in permissions) {
            assertFalse(p.has(core, listed))                                 // off for a new Mac
            assertFalse(p.set(core, stranger, true))                         // a stranger can't be allowed
            assertTrue(p.set(core, listed, true))
            assertFalse(p.set(core, listed, true))                           // no change
            val again = MacCore { 0L }.apply { load(core.stored()) }
            p.load(again, p.stored(core) + stranger)
            assertEquals(setOf(listed), p.stored(again))                     // a stored stranger is dropped
        }
        core.remove(listed)
        for (p in permissions) assertFalse(p.has(core, listed))
    }

    @Test
    fun thePermissionsAreApart() {
        core.openWindow(); core.admit(MacCore.Mac(listed, "Mac"))
        core.setControl(listed, true)
        assertFalse(core.texts(listed))
    }
}
