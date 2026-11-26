package com.keremgok.hermessim.host

import com.keremgok.hermessim.wire.WifiRule

/**
 * The host's own Wi-Fi rule (protocol/README.md "Local network", `WifiRule`): what the radio should be and why. Pure.
 * With the user's "use Wi-Fi" switch on, the host stays on any saved network whether the phone is near or away, so a
 * phone on the same network (a home, an office, a travel router) reaches it over the local network.
 */
object WifiRuleCore {
    /** The radio wanted (null: left alone) and the rule that says so. */
    fun decide(
        paired: Boolean,
        inCall: Boolean,
        hotspotOn: Boolean,
        settingUp: Boolean,
        away: Boolean,
        useWifi: Boolean,
    ): Pair<Boolean?, WifiRule> = when {
        !paired -> null to WifiRule.WIFI_RULE_NOT_PAIRED
        inCall -> null to WifiRule.WIFI_RULE_IN_CALL
        hotspotOn -> false to WifiRule.WIFI_RULE_HOTSPOT
        settingUp -> true to WifiRule.WIFI_RULE_SETUP
        !useWifi -> false to (if (away) WifiRule.WIFI_RULE_SETTING_OFF else WifiRule.WIFI_RULE_PHONE_NEAR)
        away -> true to WifiRule.WIFI_RULE_AWAY
        else -> true to WifiRule.WIFI_RULE_LOCAL_NETWORK
    }

    /** Rules under which the host looks for a saved network to join. */
    fun joins(rule: WifiRule): Boolean = rule == WifiRule.WIFI_RULE_AWAY || rule == WifiRule.WIFI_RULE_LOCAL_NETWORK
}
