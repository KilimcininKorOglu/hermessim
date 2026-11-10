// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import com.keremgok.hermessim.host.HotspotGuardCore.Usage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Blocking drops a device's traffic by MAC in both IP families; counting keeps earlier counts and reads bytes per IP. */
class HotspotGuardCoreTest {
    private val a = "aa:bb:cc:00:11:22"

    @Test fun theBlockedListTakesValidMacsOnceAndStopsAtThirtyTwo() {
        assertEquals(listOf(a), HotspotGuardCore.change(emptyList(), "AA:BB:CC:00:11:22", true))
        assertEquals(listOf(a), HotspotGuardCore.change(listOf(a), a, true))
        assertEquals(emptyList<String>(), HotspotGuardCore.change(listOf(a), a, false))
        assertEquals(listOf(a), HotspotGuardCore.change(listOf(a), "x; rm -rf /", true))
        val full = (0 until 32).map { "aa:bb:cc:00:11:%02x".format(it) }
        assertNull(HotspotGuardCore.change(full, "de:ad:be:ef:00:01", true))
    }

    @Test fun blockingDropsByMacOnTheHotspotInBothFamilies() {
        val s = HotspotGuardCore.blockScript("wlan1", listOf(a, "not a mac")).lines()
        assertTrue("iptables -w -A hermessim_hsblock -i wlan1 -m mac --mac-source $a -j DROP" in s)
        assertTrue("ip6tables -w -A hermessim_hsblock -i wlan1 -m mac --mac-source $a -j DROP" in s)
        assertTrue("iptables -w -F hermessim_hsblock" in s)
        assertEquals(2, s.count { "--mac-source" in it })
    }

    @Test fun aBadInterfaceNameIsRefused() {
        assertTrue(runCatching { HotspotGuardCore.blockScript("wlan1; reboot", emptyList()) }.isFailure)
    }

    @Test fun countingAddsOnlyNewDevicesAndNeverFlushes() {
        val s = HotspotGuardCore.countScript(listOf("192.168.43.10", "192.168.43.11", "bad"), setOf("192.168.43.10")).lines()
        assertEquals(listOf("iptables -w -A hermessim_hscount -s 192.168.43.11", "iptables -w -A hermessim_hscount -d 192.168.43.11"),
            s.filter { "-A hermessim_hscount" in it })
        assertFalse(s.any { "-F" in it })
    }

    @Test fun theCountersReadAsBytesPerDevice() {
        val out = """
            Chain hermessim_hscount (1 references)
                pkts      bytes target     prot opt in     out     source               destination
                  12     3400            all  --  *      *       192.168.43.10        0.0.0.0/0
                  40    52000            all  --  *      *       0.0.0.0/0            192.168.43.10
                   0        0            all  --  *      *       192.168.43.11        0.0.0.0/0
        """.trimIndent()
        assertEquals(mapOf("192.168.43.10" to Usage(down = 52000, up = 3400), "192.168.43.11" to Usage()),
            HotspotGuardCore.parse(out))
    }

    @Test fun teardownRemovesBothChainsInBothFamilies() {
        val s = HotspotGuardCore.teardownScript().lines()
        assertTrue("iptables -w -X hermessim_hsblock 2>/dev/null" in s)
        assertTrue("ip6tables -w -X hermessim_hscount 2>/dev/null" in s)
    }
}
