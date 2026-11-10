// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import com.keremgok.hermessim.protocol.v1.HostNotice.Kind
import com.keremgok.hermessim.protocol.v1.Hotspot
import org.junit.Assert.assertEquals
import org.junit.Test

/** The monthly data limit: a notice at 80 %, one cut at 100 %, a new month or limit starts over. */
class DataLimitCoreTest {
    private val gb = 1_000_000_000L

    @Test fun eightyPercentWarnsAndAHundredCutsOnceAMonth() {
        val c = DataLimitCore(limitBytes = 10 * gb)
        val heard = listOf(
            "2026-11" to 7 * gb, "2026-11" to 8 * gb, "2026-11" to 9 * gb,
            "2026-11" to 10 * gb, "2026-11" to 12 * gb,   // turned on again by the user: no second cut
            "2026-12" to 9 * gb,                          // a new month starts over
        ).map { (month, used) -> c.check(month, used, hotspotOn = true) }
        assertEquals(
            listOf(null to false, Kind.DATA_LIMIT_NEAR to false, null to false, Kind.DATA_LIMIT_REACHED to true,
                null to false, Kind.DATA_LIMIT_NEAR to false),
            heard.map { it.notice to it.cutOff },
        )
        assertEquals(listOf(70, 80, 90, 100, 120, 90), heard.map { it.percent })
    }

    @Test fun noLimitNeverWarnsAndANewLimitStartsOver() {
        val c = DataLimitCore()
        assertEquals(null, c.check("2026-11", 50 * gb, hotspotOn = true).notice)
        assertEquals(listOf(true, false), listOf(c.setLimit(gb), c.setLimit(gb)))
        val over = c.check("2026-11", 2 * gb, hotspotOn = false)
        assertEquals(listOf<Any?>(Kind.DATA_LIMIT_REACHED, false, true, false), listOf(over.notice, over.cutOff, c.reached(gb), c.reached(gb - 1)))
        c.setLimit(5 * gb)                                // a higher limit: its 80 % warns again
        assertEquals(Kind.DATA_LIMIT_NEAR, c.check("2026-11", 4 * gb, hotspotOn = true).notice)
    }

    @Test fun aConfigThatOnlyChangesTheLimitNeedsNoRestart() {
        val current = Hotspot.newBuilder().setSsid("Home").setPassphrase("12345678").setOn(true).build()
        val asked = { change: Hotspot.Builder.() -> Unit -> current.toBuilder().apply(change).build() }
        assertEquals(
            listOf(true, false, false),
            listOf(asked { monthlyLimitBytes = gb }, asked { ssid = "Away" }, asked { hidden = true })
                .map { DataLimitCore.onlyLimit(it, current) },
        )
    }
}
