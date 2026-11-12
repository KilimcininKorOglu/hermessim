package com.keremgok.hermessim.host

import com.keremgok.hermessim.protocol.v1.HostAction
import com.keremgok.hermessim.protocol.v1.Hotspot
import com.keremgok.hermessim.protocol.v1.MacControl
import com.keremgok.hermessim.protocol.v1.MacStatus
import com.keremgok.hermessim.protocol.v1.SetHotspotBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** MacControl: which control writes a Mac may make, and which Macs may make them. */
class MacControlCoreTest {
    private val lines = setOf(0, 1)

    private fun refusal(control: MacControl?, inCall: Boolean = false) = MacControlCore.refusal(control, inCall, lines)

    @Test
    fun aValueThatIsNoControlFails() {
        assertNull(MacControlCore.parse(byteArrayOf(0x7f, 0x7f, 0x7f)))
        assertEquals(MacControlCore.FAILED, refusal(null))
        assertEquals(MacControlCore.FAILED, refusal(MacControlCore.parse(ByteArray(0))))   // nothing chosen
    }

    @Test
    fun theDataLineMustBeALineThatIsOn() {
        assertNull(refusal(MacControl.newBuilder().setDataSlot(1).build()))
        assertEquals(MacControlCore.FAILED, refusal(MacControl.newBuilder().setDataSlot(2).build()))
    }

    @Test
    fun aHostActionWaitsForTheCallToEnd() {
        val reboot = MacControl.newBuilder().setHostAction(HostAction.Action.REBOOT).build()
        assertEquals(MacControlCore.IN_CALL, refusal(reboot, inCall = true))
        assertNull(refusal(reboot))
        val unknown = MacControl.newBuilder().setHostAction(HostAction.Action.ACTION_UNSPECIFIED).build()
        assertEquals(MacControlCore.FAILED, refusal(unknown))
    }

    @Test
    fun aBlockNeedsARealAddress() {
        val good = SetHotspotBlock.newBuilder().apply { mac = "AA:BB:CC:DD:EE:0F"; blocked = true }
        assertNull(refusal(MacControl.newBuilder().apply { block = good.build() }.build()))
        val bad = good.clone().apply { mac = "not a mac" }
        assertEquals(MacControlCore.FAILED, refusal(MacControl.newBuilder().apply { block = bad.build() }.build()))
    }

    @Test
    fun onlyAMacWithControlSeesTheLinesAndDevices() {
        val device = Hotspot.Client.newBuilder().setMac("aa:bb:cc:dd:ee:0f").setIp("192.168.43.10").setDown(7).build()
        assertEquals(listOf(device), MacControlCore.devices(listOf(ApClient("aa:bb:cc:dd:ee:0f", "192.168.43.10", down = 7))))
        val base = MacStatus.newBuilder().setDevices(1).addAllLines(MacControlCore.lines(listOf(
            SimLine(slot = 1, carrier = "Turkcell", enabled = true, defaultData = true))))
            .addClients(device).addBlocked(device).build()
        with(MacControlCore.shown(base, texts = true, control = true)) {
            assertTrue(control && texts)
            assertEquals("Turkcell", linesList.single().name)
            assertTrue(linesList.single().defaultData)
            assertEquals(listOf(device), clientsList)
            assertEquals(1, blockedCount)
        }
        with(MacControlCore.shown(base, texts = false, control = false)) {
            assertFalse(control || texts)
            assertEquals(0, linesCount + clientsCount + blockedCount)
            assertEquals(1, devices)                                         // the count stays for every Mac
        }
    }

    @Test
    fun aStatusTooLongForTheLinkIsReadInstead() {
        val status = ByteArray(200)
        assertEquals(200, MacControlCore.notified(status, mtu = 203).size)
        assertEquals(0, MacControlCore.notified(status, mtu = 202).size)
    }
}
