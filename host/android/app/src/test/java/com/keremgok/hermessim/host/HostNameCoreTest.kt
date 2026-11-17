package com.keremgok.hermessim.host

import com.keremgok.hermessim.wire.SetHotspotSetup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The host's one name (SPEC.md "State"): the Bluetooth name, which the hotspot carries too. */
class HostNameCoreTest {
    private val hotspot = HotspotInfo(available = true, ssid = "Redmi Note 11 Pro+")

    @Test
    fun aHotspotNamedOtherwiseTakesTheBluetoothName() {
        assertEquals(HostNameCore.Step.Rename("Kerem host"), HostNameCore.step("Kerem host", hotspot, inCall = false))
    }

    @Test
    fun theRenameWaitsWhileACallIsUp() {
        assertEquals(HostNameCore.Step.WaitForCall, HostNameCore.step("Kerem host", hotspot, inCall = true))
    }

    @Test
    fun nothingHappensWhenTheHotspotAlreadyCarriesTheName() {
        assertEquals(HostNameCore.Step.None, HostNameCore.step("Redmi Note 11 Pro+", hotspot, inCall = false))
        // Android gives the Bluetooth name with outer spaces kept; the hotspot carries it trimmed
        assertEquals(HostNameCore.Step.None, HostNameCore.step("  Redmi Note 11 Pro+ ", hotspot, inCall = false))
    }

    @Test
    fun aHostWithoutAReadableHotspotIsLeftAlone() {
        // No hotspot; and Android 11 before the root read of the settings, when the SSID is not known yet
        val unread = listOf(HotspotInfo(available = false), HotspotInfo(available = true, on = true))
        assertEquals(listOf(HostNameCore.Step.None, HostNameCore.Step.None), unread.map { HostNameCore.step("Kerem host", it, false) })
    }

    @Test
    fun aBluetoothNameThatCannotBeAnSsidIsNotForcedOnTheHotspot() {
        // An SSID holds at most 32 bytes; Android allows longer Bluetooth names from its own settings
        assertEquals(HostNameCore.Step.None, HostNameCore.step("a".repeat(33), hotspot, inCall = false))
        assertEquals(HostNameCore.Step.Rename("a".repeat(32)), HostNameCore.step("a".repeat(32), hotspot, inCall = false))
        assertEquals(HostNameCore.Step.None, HostNameCore.step("   ", hotspot, inCall = false))
    }

    @Test
    fun aRequestedNameMustFitTheScanResponse() {
        // 30 bytes ("a" x 30, "ş" x 15) would be a valid SSID but leave the name out of the scan response
        val names = listOf("  Kerem host ", "a".repeat(29), "a".repeat(30), "ş".repeat(15), "  ")
        assertEquals(
            listOf(BtName.Check.Ok("Kerem host"), BtName.Check.Ok("a".repeat(29)), BtName.Check.TooLong, BtName.Check.TooLong, BtName.Check.Empty),
            names.map { HostNameCore.requested(request { setHotspotSetup = SetHotspotSetup.newBuilder().apply { name = it }.build() }.setHotspotSetup) },
        )
        assertNull(HostNameCore.requested(SetHotspotSetup.newBuilder().setHidden(true).build()))
    }

    @Test
    fun theHotspotPartOfARequestCarriesNoName() {
        val s = SetHotspotSetup.newBuilder().setName("Kerem host").setHidden(true).build()
        val rest = HostNameCore.withoutName(s)
        assertFalse(rest.hasName())
        assertEquals(true, rest.hidden)
        assertFalse(HotspotCore.touchesRadio(HostNameCore.withoutName(SetHotspotSetup.newBuilder().setName("x").build())))
    }
}
