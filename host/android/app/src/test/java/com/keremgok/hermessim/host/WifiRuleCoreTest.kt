package com.keremgok.hermessim.host

import com.keremgok.hermessim.wire.WifiRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The host Wi-Fi rule of protocol/v2/wire.proto `WifiRule` and protocol/README.md "Local network". */
class WifiRuleCoreTest {
    private fun rule(
        paired: Boolean = true, inCall: Boolean = false, hotspot: Boolean = false, setup: Boolean = false,
        away: Boolean = false, useWifi: Boolean = true,
    ) = WifiRuleCore.decide(paired, inCall, hotspot, setup, away, useWifi)

    @Test fun `with the switch on the host stays on Wi-Fi while the phone is near, for the local network`() {
        assertEquals(true to WifiRule.WIFI_RULE_LOCAL_NETWORK, rule(away = false))
        assertTrue(WifiRuleCore.joins(WifiRule.WIFI_RULE_LOCAL_NETWORK))
    }

    @Test fun `with the switch on the host is on Wi-Fi while the phone is away`() {
        assertEquals(true to WifiRule.WIFI_RULE_AWAY, rule(away = true))
        assertTrue(WifiRuleCore.joins(WifiRule.WIFI_RULE_AWAY))
    }

    @Test fun `with the switch off the host keeps Wi-Fi off near or away`() {
        assertEquals(false to WifiRule.WIFI_RULE_PHONE_NEAR, rule(useWifi = false))
        assertEquals(false to WifiRule.WIFI_RULE_SETTING_OFF, rule(useWifi = false, away = true))
    }

    @Test fun `the hotspot turns Wi-Fi off, a call and an unpaired host leave it alone`() {
        assertEquals(false to WifiRule.WIFI_RULE_HOTSPOT, rule(hotspot = true))
        assertEquals(null to WifiRule.WIFI_RULE_IN_CALL, rule(inCall = true))
        assertEquals(null to WifiRule.WIFI_RULE_NOT_PAIRED, rule(paired = false))
        assertEquals(true to WifiRule.WIFI_RULE_SETUP, rule(setup = true, useWifi = false))
        assertFalse(WifiRuleCore.joins(WifiRule.WIFI_RULE_SETUP))
    }
}
