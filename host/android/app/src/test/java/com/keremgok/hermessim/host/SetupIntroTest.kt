// HermesSIM host. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupIntroTest {
    @Test
    fun `a first start before root shows the intro`() {
        assertTrue(SetupIntro.shows(seen = false, rooted = false, host = false))
    }

    @Test
    fun `the intro is shown only once`() {
        assertFalse(SetupIntro.shows(seen = true, rooted = false, host = false))
    }

    @Test
    fun `a rooted phone skips the intro`() {
        assertFalse(SetupIntro.shows(seen = false, rooted = true, host = false))
    }

    @Test
    fun `a running host skips the intro`() {
        assertFalse(SetupIntro.shows(seen = false, rooted = true, host = true))
        assertFalse(SetupIntro.shows(seen = false, rooted = false, host = true))
    }
}
