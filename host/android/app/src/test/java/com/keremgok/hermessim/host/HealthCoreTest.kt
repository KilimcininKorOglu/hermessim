// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import com.keremgok.hermessim.wire.ChargeState
import com.keremgok.hermessim.wire.LineLock
import com.keremgok.hermessim.wire.Notice
import com.keremgok.hermessim.wire.NoticeKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** Health notices fire once per event: the battery on its way down, a SIM that newly asks for its PIN or PUK, a charge
 *  limit the phone keeps undoing. */
class HealthCoreTest {
    private val low = NoticeKind.NOTICE_KIND_BATTERY_LOW
    private val critical = NoticeKind.NOTICE_KIND_BATTERY_CRITICAL

    /** Kind, value, line (null when the notice names none) and time of each notice. */
    private fun List<Notice>.rows() = map { listOf(it.kind, it.value, if (it.hasLine()) it.line else null, it.atMs) }

    @Test fun theBatteryWarnsOnceAtEachStepOnItsWayDown() {
        val c = HealthCore()
        val heard = listOf(40, 16, 15, 14, 9, 5, 4, 3).map { c.battery(it, charging = false) }
        assertEquals(listOf(null, null, low, null, null, critical, null, null), heard)
    }

    @Test fun chargingOrPassingThirtyArmsItAgainAndABriefRiseDoesNot() {
        val c = HealthCore()
        val heard = listOf(
            15 to false, 20 to false, 15 to false,   // up a little without charging: no second warning
            15 to true, 14 to false,                 // charged a moment: armed again
            30 to false, 12 to false,                // passed 30 %: armed again
            -1 to false, 4 to false,                 // an unknown level changes nothing
        ).map { (percent, charging) -> c.battery(percent, charging) }
        assertEquals(listOf(low, null, null, null, low, null, low, null, critical), heard)
    }

    @Test fun aSimWarnsWhenItNewlyLocksAndAgainAfterAnUnlock() {
        val c = HealthCore(lockedSlots = setOf(1))       // slot 1 was locked before the restart
        val heard = listOf(setOf(0, 1), setOf(0, 1), setOf(1), setOf(0, 1)).map { c.newlyLocked(it) }
        assertEquals(listOf(listOf(0), emptyList(), emptyList(), listOf(0)), heard)
    }

    /** "line:value" of each roaming notice; the pairs are the roaming SIMs (slot to data roaming allowed). */
    private fun HealthCore.roaming(vararg sims: Pair<Int, Boolean>): List<String> {
        val lines = sims.map { (slot, data) -> SimLine(slot, roaming = true, dataRoaming = data) }
        return notices(HealthView(80, charging = false, lines = lines), now = 1)
            .filter { it.kind == NoticeKind.NOTICE_KIND_ROAMING }.map { "${it.line}:${it.value}" }
    }

    @Test fun aSimTellsWhenItStartsRoamingWithItsDataRoamingAndAgainAfterItStops() {
        val c = HealthCore(roamingSlots = setOf(0))      // slot 0 was roaming before the restart
        val heard = listOf(
            c.roaming(0 to false),
            c.roaming(0 to false, 1 to true),
            c.roaming(0 to false, 1 to true),
            c.roaming(),
            c.roaming(0 to false),
        )
        assertEquals(listOf(emptyList(), listOf("1:1"), emptyList(), emptyList(), listOf("0:0")), heard)
    }

    @Test fun theStateGivesTheNoticesWithTheirValueLineAndTime() {
        val lines = listOf(SimLine(0), SimLine(1, lock = LineLock.LINE_LOCK_PUK))
        val got = HealthCore().notices(HealthView(4, charging = false, lines = lines), now = 42).rows()
        assertEquals(listOf(listOf(critical, 4L, null, 42L), listOf(NoticeKind.NOTICE_KIND_LINE_LOCKED, 0L, 1, 42L)), got)
    }

    @Test fun aStuckChargeLimitTellsOnceAndAgainOnlyAfterItHeld() {
        val c = HealthCore()
        val heard = listOf(ChargeState.CHARGE_STATE_HELD, ChargeState.CHARGE_STATE_STUCK, ChargeState.CHARGE_STATE_STUCK,
            ChargeState.CHARGE_STATE_UNSPECIFIED, ChargeState.CHARGE_STATE_STUCK,   // an unknown state changes nothing
            ChargeState.CHARGE_STATE_DRAINING, ChargeState.CHARGE_STATE_STUCK).map { c.newlyStuck(it) }
        assertEquals(listOf(false, true, false, false, false, false, true), heard)
    }

    @Test fun theStuckNoticeCarriesTheLimit() {
        val view = HealthView(90, charging = true, chargeState = ChargeState.CHARGE_STATE_STUCK, chargeLimit = 70)
        assertEquals(listOf(listOf(NoticeKind.NOTICE_KIND_CHARGE_STUCK, 70L, null, 7L)), HealthCore().notices(view, now = 7).rows())
    }
}
