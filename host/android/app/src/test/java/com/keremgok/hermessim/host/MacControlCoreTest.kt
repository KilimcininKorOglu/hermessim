package com.keremgok.hermessim.host

import com.keremgok.hermessim.protocol.v1.HostAction
import com.keremgok.hermessim.protocol.v1.MacControl
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
    fun onlyAListedMacMayControlAndRemovingItForgetsThat() {
        val core = MacCore { 0L }
        val listed = "AA:BB:CC:DD:EE:01"
        core.openWindow(); core.admit(MacCore.Mac(listed, "Mac"))
        assertFalse(core.control(listed))                                    // off for a new Mac
        assertFalse(core.setControl("AA:BB:CC:DD:EE:02", true))              // a stranger can't be allowed
        assertTrue(core.setControl(listed, true))
        assertTrue(core.control(listed))
        core.remove(listed)
        assertFalse(core.control(listed))
    }
}
