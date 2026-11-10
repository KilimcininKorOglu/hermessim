// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.content.Context
import android.content.SharedPreferences
import com.keremgok.hermessim.protocol.v1.HostNotice
import com.keremgok.hermessim.protocol.v1.HostStatus
import com.keremgok.hermessim.protocol.v1.Sim

/**
 * Health notices (protocol/README.md "Push"): what a status gives rise to, each once per event. The battery warns at
 * 15 % and at 5 % while not charging and is armed again once it charges or passes 30 %; a SIM warns when it newly asks
 * for its PIN or PUK. Pure: [Health] keeps its state across host restarts.
 */
class HealthCore(var batteryStep: Int = 0, lockedSlots: Set<Int> = emptySet()) {
    val lockedSlots: MutableSet<Int> = lockedSlots.toMutableSet()

    fun notices(s: HostStatus, now: Long): List<HostNotice> {
        val out = mutableListOf<HostNotice>()
        battery(s.batteryPercent, s.charging)?.let { out += notice(it, s.batteryPercent, 0, now) }
        val locked = s.simsList.filter { it.lock == Sim.Lock.PIN_REQUIRED || it.lock == Sim.Lock.PUK_REQUIRED }.map { it.slot }
        newlyLocked(locked.toSet()).forEach { out += notice(HostNotice.Kind.SIM_LOCKED, 0, it, now) }
        return out
    }

    /** The battery notice due at this level, or null. An unknown level (below 0) changes nothing. */
    fun battery(percent: Int, charging: Boolean): HostNotice.Kind? {
        if (percent < 0) return null
        if (charging || percent >= REARM_PERCENT) { batteryStep = 0; return null }
        val step = when {
            percent <= CRITICAL_PERCENT -> STEP_CRITICAL
            percent <= LOW_PERCENT -> STEP_LOW
            else -> 0
        }
        if (step <= batteryStep) return null
        batteryStep = step
        return if (step == STEP_CRITICAL) HostNotice.Kind.BATTERY_CRITICAL else HostNotice.Kind.BATTERY_LOW
    }

    /** The slots locked now that weren't before; a SIM unlocked and locked again warns again. */
    fun newlyLocked(now: Set<Int>): List<Int> {
        val fresh = (now - lockedSlots).sorted()
        lockedSlots.clear()
        lockedSlots.addAll(now)
        return fresh
    }

    private fun notice(kind: HostNotice.Kind, value: Int, slot: Int, now: Long): HostNotice = HostNotice.newBuilder().also {
        it.kind = kind
        it.value = value
        it.slot = slot
        it.atMs = now
    }.build()

    companion object {
        const val LOW_PERCENT = 15
        const val CRITICAL_PERCENT = 5
        const val REARM_PERCENT = 30
        private const val STEP_LOW = 1
        private const val STEP_CRITICAL = 2
    }
}

/** [HealthCore] with its state in the "health" prefs, so a host restart doesn't warn about the same thing again. */
object Health {
    private lateinit var prefs: SharedPreferences
    private lateinit var core: HealthCore

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("health", Context.MODE_PRIVATE)
        val locked = prefs.getStringSet("locked", emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }.toSet()
        core = HealthCore(prefs.getInt("battery_step", 0), locked)
    }

    /** Relay's status, main thread: the notices it gives rise to. */
    fun notices(s: HostStatus): List<HostNotice> {
        if (!::core.isInitialized) return emptyList()
        val out = core.notices(s, System.currentTimeMillis())
        prefs.edit().putInt("battery_step", core.batteryStep)
            .putStringSet("locked", core.lockedSlots.map { it.toString() }.toSet()).apply()
        return out
    }
}
