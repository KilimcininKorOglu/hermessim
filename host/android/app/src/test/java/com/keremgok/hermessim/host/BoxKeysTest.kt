// HermesSIM host. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Box mode keeps the display dark: Android must not wake it on charger plug or unplug (AOSP PowerManagerService
 * shouldWakeUpWhenPluggedOrUnpluggedLocked returns false while theater mode is on), and restore must turn it back off.
 */
class BoxKeysTest {
    @Test fun `box mode turns theater mode on`() {
        assertTrue(Triple("global", "theater_mode_on", "1") in Box.AOSP_KEYS)
    }

    @Test fun `an unset theater mode key is applied and restored as off`() {
        assertEquals("0", Box.original("global/theater_mode_on", null))
        assertEquals("1", Box.original("global/theater_mode_on", "1"))
    }

    @Test fun `other unset keys are left alone`() {
        assertNull(Box.original("secure/doze_enabled", null))
        assertEquals("1", Box.original("secure/doze_enabled", "1"))
    }
}
