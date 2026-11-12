package com.keremgok.hermessim.host

import com.keremgok.hermessim.host.HotspotWatchCore.Step
import com.keremgok.hermessim.protocol.v1.MacControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hotspot check: when a stuck hotspot restarts, when mobile data goes off and on, and what waits for a call. */
class HotspotWatchCoreTest {
    private var clock = 0L
    private val core = HotspotWatchCore { clock }

    private fun failed(inCall: Boolean = false) = core.checked(ok = false, limit = 3, inCall = inCall)

    @Test fun `three failed checks in a row restart the hotspot, two do not`() {
        assertEquals(listOf(Step.NONE, Step.NONE, Step.RESTART_HOTSPOT), List(3) { failed() })
    }

    @Test fun `a check that fails right after the restart turns mobile data off and on, then counting starts again`() {
        repeat(3) { failed() }
        assertEquals(Step.TOGGLE_DATA, failed())
        assertEquals(listOf(Step.NONE, Step.NONE, Step.RESTART_HOTSPOT), List(3) { failed() })
    }

    @Test fun `an answered check clears the count and the restart`() {
        repeat(3) { failed() }
        assertEquals(Step.NONE, core.checked(ok = true, limit = 3, inCall = false))
        assertEquals(listOf(Step.NONE, Step.NONE, Step.RESTART_HOTSPOT), List(3) { failed() })
    }

    @Test fun `during a call the step waits and runs once the call is over`() {
        repeat(2) { failed() }
        assertEquals(Step.WAIT, failed(inCall = true))
        assertEquals(Step.RESTART_HOTSPOT, core.afterCall())
        assertEquals(Step.NONE, core.afterCall())
    }

    @Test fun `a device's report restarts at once, a second report within two minutes is dropped`() {
        assertEquals(Step.RESTART_HOTSPOT, core.reported(inCall = false))
        clock += HotspotWatchCore.QUIET_MS - 1
        assertEquals(Step.NONE, core.reported(inCall = false))
        clock += 1
        assertEquals(Step.TOGGLE_DATA, core.reported(inCall = false))
    }

    @Test fun `a report during a call waits for its end`() {
        assertEquals(Step.WAIT, core.reported(inCall = true))
        assertEquals(Step.RESTART_HOTSPOT, core.afterCall())
    }

    @Test fun `a Mac's report is taken from every Mac and during a call`() {
        val report = MacControl.newBuilder().setHotspotNoInternet(true).build()
        assertNull(MacControlCore.refusal(report, inCall = true, slots = emptySet()))
        assertTrue(MacControlCore.forEveryMac(report.toByteArray()))
        assertFalse(MacControlCore.forEveryMac(MacControl.newBuilder().setDataSlot(1).build().toByteArray()))
    }

    @Test fun `the default check is on and pings every 5 minutes, 3 misses in a row`() {
        with(WatchSettings.DEFAULT) { assertEquals(listOf(true, 300, "1.1.1.1", 3), listOf(on, everyS, address, misses)) }
        assertTrue(WatchSettings.valid(WatchSettings.DEFAULT))
    }

    @Test fun `the settings are taken only within their ranges`() {
        val d = WatchSettings.DEFAULT
        val taken = listOf(d.toBuilder().setEveryS(60).setMisses(1).setAddress("dns.google"), d.toBuilder().setEveryS(3600).setMisses(10))
        val refused = listOf(d.toBuilder().setEveryS(59), d.toBuilder().setEveryS(3601), d.toBuilder().setMisses(0),
            d.toBuilder().setMisses(11), d.toBuilder().setAddress(""), d.toBuilder().setAddress("a b"),
            d.toBuilder().setAddress(" 1.1.1.1"), d.toBuilder().setAddress("x".repeat(254)), d.toBuilder().setAddress("-bad.example"))
        assertEquals(listOf(true, true), taken.map { WatchSettings.valid(it.build()) })
        assertTrue(refused.none { WatchSettings.valid(it.build()) })
    }
}
