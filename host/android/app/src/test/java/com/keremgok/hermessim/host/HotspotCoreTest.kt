// HermesSIM host. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import com.keremgok.hermessim.wire.Code
import com.keremgok.hermessim.wire.HotspotBand
import com.keremgok.hermessim.wire.HotspotPhase
import com.keremgok.hermessim.wire.HotspotSecurity
import com.keremgok.hermessim.wire.SetHotspotSetup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a peer may ask of the hotspot's settings, and what it is told of the hotspot. */
class HotspotCoreTest {
    private val now = HotspotInfo(available = true, ssid = "Home", passphrase = "12345678", band = HotspotBand.HOTSPOT_BAND_2G,
        security = HotspotSecurity.HOTSPOT_SECURITY_WPA2, idleOff = true, idleOffMinutes = 10)

    private fun setup(change: SetHotspotSetup.Builder.() -> Unit): SetHotspotSetup = SetHotspotSetup.newBuilder().apply(change).build()

    @Test fun `a setting the request does not carry stays as it is`() {
        val wanted = HotspotCore.wanted(now, setup { band = HotspotBand.HOTSPOT_BAND_5G; idleOff = false })
        assertEquals(ApWanted("Home", "12345678", HotspotBand.HOTSPOT_BAND_5G, HotspotSecurity.HOTSPOT_SECURITY_WPA2,
            hidden = false, idleOff = false, idleOffMinutes = 10, appleMarker = false), wanted)
    }

    @Test fun `the month limit and the call pause alone do not touch the phone's hotspot settings`() {
        assertFalse(HotspotCore.touchesRadio(setup { monthLimitBytes = 5_000_000; pauseInCalls = true }))
        assertTrue(HotspotCore.touchesRadio(setup { hidden = false }))
    }

    @Test fun `a refusal names the code and the field`() {
        fun refusal(change: SetHotspotSetup.Builder.() -> Unit) = HotspotCore.refusal(HotspotCore.wanted(now, setup(change)))
        assertNull(refusal { name = "A" })
        assertNull(refusal { security = HotspotSecurity.HOTSPOT_SECURITY_OPEN; passphrase = "" })
        assertEquals(Code.CODE_BAD_VALUE to 1, refusal { name = " " })
        assertEquals(Code.CODE_BAD_VALUE to 1, refusal { name = "ş".repeat(17) })          // 34 bytes
        assertEquals(Code.CODE_BAD_VALUE to 2, refusal { passphrase = "1234567" })
        assertEquals(Code.CODE_NOT_OFFERED to 4, refusal { security = HotspotSecurity.HOTSPOT_SECURITY_WPA3 })
    }

    @Test fun `on wins over a pause, a pause over a start, a start over a failure`() {
        // on, starting, paused, failed -> the phase a peer is told
        val cases = listOf(
            listOf(true, false, true, true) to HotspotPhase.HOTSPOT_PHASE_ON,
            listOf(false, true, true, true) to HotspotPhase.HOTSPOT_PHASE_PAUSED,
            listOf(false, true, false, true) to HotspotPhase.HOTSPOT_PHASE_STARTING,
            listOf(false, false, false, true) to HotspotPhase.HOTSPOT_PHASE_FAILED,
            listOf(false, false, false, false) to HotspotPhase.HOTSPOT_PHASE_OFF,
        )
        for ((given, phase) in cases) {
            val info = now.copy(on = given[0], starting = given[1])
            assertEquals(given.toString(), phase, HotspotCore.phase(info, paused = given[2], failed = given[3]))
        }
    }

    @Test fun `the hotspot group never carries the passphrase`() {
        val sent = HotspotCore.message(now, HotspotPhase.HOTSPOT_PHASE_OFF, monthLimit = 0, limitReached = false, pauseInCalls = false)
        assertFalse(sent.toByteString().toStringUtf8().contains("12345678"))
        assertEquals("Home", sent.name)
    }

    @Test fun `the device list holds the devices on the hotspot, then the blocked ones that are away`() {
        val laptop = ApClient("aa:bb:cc:dd:ee:01", "192.168.43.10", "Laptop", down = 9, up = 4)
        val tv = ApClient("aa:bb:cc:dd:ee:02", "192.168.43.11")
        val blocked = listOf(ApClient("aa:bb:cc:dd:ee:02", name = "TV"), ApClient("aa:bb:cc:dd:ee:03", name = "Tablet"))
        val list = HotspotCore.clients(listOf(laptop, tv), blocked).clientsList
        assertEquals(listOf("Laptop", "TV", "Tablet"), list.map { it.name })
        assertEquals(listOf(true, true, false), list.map { it.connected })
        assertEquals(listOf(false, true, true), list.map { it.blocked })
        assertEquals(9L to 4L, list[0].downBytes to list[0].upBytes)
        assertEquals("aa:bb:cc:dd:ee:03", ApClient.text(list[2].mac.toByteArray()))
    }
}
