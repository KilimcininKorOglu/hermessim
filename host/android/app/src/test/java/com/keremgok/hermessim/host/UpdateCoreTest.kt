// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import com.keremgok.hermessim.host.UpdateCore.Install
import com.keremgok.hermessim.host.UpdateCore.Next
import com.keremgok.hermessim.wire.UpdateFault
import com.keremgok.hermessim.wire.UpdatePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Automatic updates are off unless turned on: then nothing downloads or installs without the user. */
class UpdateCoreTest {
    private val now = 1_000_000_000_000L
    private val due = UpdateCore.Night(auto = true, ready = true, hour = 3, nowMs = now, deferredUntilMs = 0,
        toldAtMs = now - UpdateCore.HOUR_MS)

    @Test fun withTheSettingOffANewVersionIsOnlyOffered() {
        assertEquals(Next.OFFER, UpdateCore.afterCheck(auto = false, downloaded = false))
        assertEquals(Next.DOWNLOAD, UpdateCore.afterCheck(auto = true, downloaded = false))
    }

    @Test fun aVerifiedDownloadStaysReadyAfterTheSettingIsTurnedOff() {
        assertEquals(Next.DOWNLOAD, UpdateCore.afterCheck(auto = false, downloaded = true))
    }

    @Test fun updateNowDownloadsFirstWhenTheVersionWasOnlyOffered() {
        assertEquals(Install.INSTALL, UpdateCore.installNow(UpdatePhase.UPDATE_PHASE_READY))
        assertEquals(Install.DOWNLOAD_THEN_INSTALL, UpdateCore.installNow(UpdatePhase.UPDATE_PHASE_AVAILABLE))
        assertEquals(Install.NONE, UpdateCore.installNow(UpdatePhase.UPDATE_PHASE_IDLE))
        assertEquals(Install.NONE, UpdateCore.installNow(UpdatePhase.UPDATE_PHASE_DOWNLOADING))
    }

    @Test fun theNightInstallNeedsTheSetting() {
        assertTrue(UpdateCore.nightInstall(due))
        assertFalse(UpdateCore.nightInstall(due.copy(auto = false)))
    }

    @Test fun theNightInstallKeepsItsOtherRules() {
        assertFalse(UpdateCore.nightInstall(due.copy(ready = false)))
        assertFalse(UpdateCore.nightInstall(due.copy(hour = 1)))
        assertFalse(UpdateCore.nightInstall(due.copy(hour = 5)))
        assertTrue(UpdateCore.nightInstall(due.copy(hour = 2)))
        assertFalse(UpdateCore.nightInstall(due.copy(deferredUntilMs = now + 1)))
        assertFalse(UpdateCore.nightInstall(due.copy(toldAtMs = 0)))
        assertFalse(UpdateCore.nightInstall(due.copy(toldAtMs = now - UpdateCore.HOUR_MS + 1)))
    }

    /** The phone shows the installer's own words under a failed update, on one line and at most 120 characters. */
    @Test
    fun `a failed step's text reaches the phone as one short line`() {
        val out = "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE:\n  Package com.keremgok.hermessim.host signatures do not match]"
        val detail = UpdateCore.faultDetail(UpdateFault.UPDATE_FAULT_INSTALL, out)
        assertEquals("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package com.keremgok.hermessim.host signatures do not match]", detail)
        assertEquals(120, UpdateCore.faultDetail(UpdateFault.UPDATE_FAULT_INSTALL, "x".repeat(500)).length)
    }

    @Test
    fun `nothing failed, nothing to show`() {
        assertEquals("", UpdateCore.faultDetail(UpdateFault.UPDATE_FAULT_UNSPECIFIED, "leftover text"))
    }
}
