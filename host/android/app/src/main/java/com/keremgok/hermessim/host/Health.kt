// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.content.Context
import android.content.SharedPreferences
import com.keremgok.hermessim.wire.ChargeState
import com.keremgok.hermessim.wire.Notice
import com.keremgok.hermessim.wire.NoticeKind

/** What the health rules look at: the battery, the lines and the charge limit, as they are now. */
data class HealthView(
    val battery: Int,
    val charging: Boolean,
    val lines: List<SimLine> = emptyList(),
    val chargeState: ChargeState = ChargeState.CHARGE_STATE_UNSPECIFIED,
    val chargeLimit: Int = 0,
)

/**
 * Health notices (`Notice`): what the host's state gives rise to, each once per event. The battery warns at
 * 15 % and at 5 % while not charging and is armed again once it charges or passes 30 %; a SIM warns when it newly asks
 * for its PIN or PUK; a SIM tells once when it starts roaming (value 1 when its data roaming is allowed) and is armed
 * again once it stops; the charge limit tells once when the phone keeps undoing it (value = the limit) and is armed
 * again once it holds. Pure: [Health] keeps its state across host restarts.
 */
class HealthCore(
    var batteryStep: Int = 0,
    lockedSlots: Set<Int> = emptySet(),
    roamingSlots: Set<Int> = emptySet(),
    var chargeStuck: Boolean = false,
) {
    val lockedSlots: MutableSet<Int> = lockedSlots.toMutableSet()
    val roamingSlots: MutableSet<Int> = roamingSlots.toMutableSet()

    fun notices(s: HealthView, now: Long): List<Notice> {
        val out = mutableListOf<Notice>()
        battery(s.battery, s.charging)?.let { out += notice(it, s.battery, null, now) }
        val locked = s.lines.filter { it.asksForCode }.map { it.slot }
        newlyLocked(locked.toSet()).forEach { out += notice(NoticeKind.NOTICE_KIND_LINE_LOCKED, 0, it, now) }
        val roaming = s.lines.filter { it.roaming }.associateBy { it.slot }
        newlyRoaming(roaming.keys).forEach { slot ->
            out += notice(NoticeKind.NOTICE_KIND_ROAMING, if (roaming.getValue(slot).dataRoaming) 1 else 0, slot, now)
        }
        if (newlyStuck(s.chargeState)) out += notice(NoticeKind.NOTICE_KIND_CHARGE_STUCK, s.chargeLimit, null, now)
        return out
    }

    /** True when the charge limit has just become stuck. An unknown state changes nothing; any other state re-arms. */
    fun newlyStuck(state: ChargeState): Boolean {
        if (state == ChargeState.CHARGE_STATE_UNSPECIFIED) return false
        val stuck = state == ChargeState.CHARGE_STATE_STUCK
        return (stuck && !chargeStuck).also { chargeStuck = stuck }
    }

    /** The slots roaming now that weren't before; a SIM that stops roaming and starts again tells again. */
    fun newlyRoaming(now: Set<Int>): List<Int> = fresh(now, roamingSlots)

    /** The battery notice due at this level, or null. An unknown level (below 0) changes nothing. */
    fun battery(percent: Int, charging: Boolean): NoticeKind? {
        if (percent < 0) return null
        if (charging || percent >= REARM_PERCENT) { batteryStep = 0; return null }
        val step = when {
            percent <= CRITICAL_PERCENT -> STEP_CRITICAL
            percent <= LOW_PERCENT -> STEP_LOW
            else -> 0
        }
        if (step <= batteryStep) return null
        batteryStep = step
        return if (step == STEP_CRITICAL) NoticeKind.NOTICE_KIND_BATTERY_CRITICAL else NoticeKind.NOTICE_KIND_BATTERY_LOW
    }

    /** The slots locked now that weren't before; a SIM unlocked and locked again warns again. */
    fun newlyLocked(now: Set<Int>): List<Int> = fresh(now, lockedSlots)

    /** The members of [now] not in [seen]; [seen] becomes [now]. */
    private fun fresh(now: Set<Int>, seen: MutableSet<Int>): List<Int> {
        val out = (now - seen).sorted()
        seen.clear()
        seen.addAll(now)
        return out
    }

    /** [slot] is the line a notice is about; null for a notice about the whole host. */
    private fun notice(kind: NoticeKind, value: Int, slot: Int?, now: Long): Notice = Notice.newBuilder().also {
        it.kind = kind
        it.value = value.toLong()
        if (slot != null) it.line = slot
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
        val stored = ctx.getSharedPreferences("health", Context.MODE_PRIVATE)
        prefs = stored
        core = with(stored) { HealthCore(getInt("battery_step", 0), slots("locked"), slots("roaming"), getBoolean("charge_stuck", false)) }
    }

    private fun SharedPreferences.slots(key: String): Set<Int> =
        getStringSet(key, emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }.toSet()

    /** Main thread, after each change of the host's state: the notices it gives rise to. */
    fun notices(s: HealthView): List<Notice> {
        if (!::core.isInitialized) return emptyList()
        val out = core.notices(s, System.currentTimeMillis())
        prefs.edit().putInt("battery_step", core.batteryStep)
            .putStringSet("locked", core.lockedSlots.map { it.toString() }.toSet())
            .putStringSet("roaming", core.roamingSlots.map { it.toString() }.toSet())
            .putBoolean("charge_stuck", core.chargeStuck).apply()
        return out
    }
}
