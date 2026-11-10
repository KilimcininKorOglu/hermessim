// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import com.keremgok.hermessim.protocol.v1.Box
import com.keremgok.hermessim.protocol.v1.Box.ChargeState
import com.keremgok.hermessim.protocol.v1.HostNotice.Kind
import com.keremgok.hermessim.protocol.v1.HostStatus
import com.keremgok.hermessim.protocol.v1.Sim
import org.junit.Assert.assertEquals
import org.junit.Test

/** Health notices fire once per event: the battery on its way down, a SIM that newly asks for its PIN or PUK, a charge
 *  limit the phone keeps undoing. */
class HealthCoreTest {
    @Test fun theBatteryWarnsOnceAtEachStepOnItsWayDown() {
        val c = HealthCore()
        val heard = listOf(40, 16, 15, 14, 9, 5, 4, 3).map { c.battery(it, charging = false) }
        assertEquals(listOf(null, null, Kind.BATTERY_LOW, null, null, Kind.BATTERY_CRITICAL, null, null), heard)
    }

    @Test fun chargingOrPassingThirtyArmsItAgainAndABriefRiseDoesNot() {
        val c = HealthCore()
        val heard = listOf(
            15 to false, 20 to false, 15 to false,   // up a little without charging: no second warning
            15 to true, 14 to false,                 // charged a moment: armed again
            30 to false, 12 to false,                // passed 30 %: armed again
            -1 to false, 4 to false,                 // an unknown level changes nothing
        ).map { (percent, charging) -> c.battery(percent, charging) }
        assertEquals(listOf(Kind.BATTERY_LOW, null, null, null, Kind.BATTERY_LOW, null, Kind.BATTERY_LOW, null,
            Kind.BATTERY_CRITICAL), heard)
    }

    @Test fun aSimWarnsWhenItNewlyLocksAndAgainAfterAnUnlock() {
        val c = HealthCore(lockedSlots = setOf(1))       // slot 1 was locked before the restart
        val heard = listOf(setOf(0, 1), setOf(0, 1), setOf(1), setOf(0, 1)).map { c.newlyLocked(it) }
        assertEquals(listOf(listOf(0), emptyList(), emptyList(), listOf(0)), heard)
    }

    /** "slot:value" of each ROAMING notice; the pairs are the roaming SIMs (slot to data roaming allowed). */
    private fun HealthCore.roaming(vararg sims: Pair<Int, Boolean>): List<String> {
        val status = HostStatus.newBuilder().setBatteryPercent(80)
        sims.forEach { (slot, data) -> status.addSims(Sim.newBuilder().setSlot(slot).setRoaming(true).setDataRoaming(data)) }
        return notices(status.build(), now = 1).filter { it.kind == Kind.ROAMING }.map { "${it.slot}:${it.value}" }
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

    @Test fun aStatusGivesTheNoticesWithTheirValueSlotAndTime() {
        val sim = { slot: Int, lock: Sim.Lock -> Sim.newBuilder().setSlot(slot).setLock(lock).build() }
        val s = HostStatus.newBuilder().setBatteryPercent(4).setCharging(false)
            .addSims(sim(0, Sim.Lock.UNLOCKED)).addSims(sim(1, Sim.Lock.PUK_REQUIRED)).build()
        val got = HealthCore().notices(s, now = 42).map { listOf(it.kind, it.value, it.slot, it.atMs) }
        assertEquals(listOf(listOf(Kind.BATTERY_CRITICAL, 4, 0, 42L), listOf(Kind.SIM_LOCKED, 0, 1, 42L)), got)
    }

    @Test fun aStuckChargeLimitTellsOnceAndAgainOnlyAfterItHeld() {
        val c = HealthCore()
        val heard = listOf(ChargeState.HELD, ChargeState.STUCK, ChargeState.STUCK,
            ChargeState.CHARGE_STATE_UNSPECIFIED, ChargeState.STUCK,   // an unknown state changes nothing
            ChargeState.DRAINING, ChargeState.STUCK).map { c.newlyStuck(it) }
        assertEquals(listOf(false, true, false, false, false, false, true), heard)
    }

    @Test fun theStuckNoticeCarriesTheLimit() {
        val box = Box.newBuilder().setChargeLimit(70).setChargeState(ChargeState.STUCK)
        val s = HostStatus.newBuilder().setBatteryPercent(90).setCharging(true).setBox(box).build()
        val got = HealthCore().notices(s, now = 7).map { listOf(it.kind, it.value, it.slot, it.atMs) }
        assertEquals(listOf(listOf(Kind.CHARGE_CONTROL_STUCK, 70, 0, 7L)), got)
    }
}
