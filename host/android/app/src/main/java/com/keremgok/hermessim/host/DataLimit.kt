// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.content.Context
import android.content.SharedPreferences
import com.keremgok.hermessim.protocol.v1.HostNotice
import com.keremgok.hermessim.protocol.v1.Hotspot as HotspotMsg
import java.time.YearMonth

/**
 * The hotspot's monthly data limit (protocol Hotspot.monthly_limit_bytes), counted as DataUsage's month: a notice at
 * 80 %, and at 100 % the hotspot goes off once with a notice. Turned on again by the user, it is not cut a second time
 * that month; a new month or a new limit starts over. Pure: [DataLimit] keeps the state.
 */
class DataLimitCore(var limitBytes: Long = 0, var month: String = "", var step: Int = 0) {
    /** What this month's usage calls for: a notice, and whether to turn the hotspot off. */
    data class Outcome(val notice: HostNotice.Kind?, val percent: Int, val cutOff: Boolean)

    fun check(month: String, used: Long, hotspotOn: Boolean): Outcome {
        if (month != this.month) { this.month = month; step = 0 }
        if (limitBytes <= 0) return Outcome(null, 0, false)
        val percent = (used * 100 / limitBytes).toInt()
        val due = when {
            percent >= 100 -> STEP_REACHED
            percent >= NEAR_PERCENT -> STEP_NEAR
            else -> 0
        }
        if (due <= step) return Outcome(null, percent, false)
        step = due
        return if (due == STEP_REACHED) Outcome(HostNotice.Kind.DATA_LIMIT_REACHED, percent, hotspotOn)
        else Outcome(HostNotice.Kind.DATA_LIMIT_NEAR, percent, false)
    }

    fun reached(used: Long): Boolean = limitBytes in 1..used

    /** A new limit starts the month's notices over; true when it changed. */
    fun setLimit(bytes: Long): Boolean {
        val next = bytes.coerceAtLeast(0)
        if (next == limitBytes) return false
        limitBytes = next
        step = 0
        return true
    }

    companion object {
        const val NEAR_PERCENT = 80
        private const val STEP_NEAR = 1
        private const val STEP_REACHED = 2

        /** True when [asked] changes nothing but the limit against [current]: no hotspot restart is needed. */
        fun onlyLimit(asked: HotspotMsg, current: HotspotMsg): Boolean = radio(asked) == radio(current)

        private fun radio(h: HotspotMsg): List<Any> = listOf(h.ssid, h.passphrase, h.band, h.hidden, h.security,
            h.maxClients, h.autoOff, h.autoOffMinutes, h.appleMarker)
    }
}

/** [DataLimitCore] with its state in the "data_limit" prefs. */
object DataLimit {
    private lateinit var prefs: SharedPreferences
    private lateinit var core: DataLimitCore

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("data_limit", Context.MODE_PRIVATE)
        core = DataLimitCore(prefs.getLong("limit", 0), prefs.getString("month", "").orEmpty(), prefs.getInt("step", 0))
    }

    val limitBytes: Long get() = if (::core.isInitialized) core.limitBytes else 0

    fun reached(used: Long): Boolean = ::core.isInitialized && core.reached(used)

    /** SetHotspotConfig's limit; true when it changed. */
    fun set(bytes: Long): Boolean {
        if (!::core.isInitialized || !core.setLimit(bytes)) return false
        save()
        return true
    }

    /** Relay's status, main thread: the notice this month's usage calls for, and whether to turn the hotspot off. */
    fun check(h: HotspotMsg): DataLimitCore.Outcome {
        if (!::core.isInitialized) return DataLimitCore.Outcome(null, 0, false)
        val out = core.check(YearMonth.now().toString(), h.usage.month, h.on)
        if (out.notice != null) save()
        return out
    }

    private fun save() {
        prefs.edit().putLong("limit", core.limitBytes).putString("month", core.month).putInt("step", core.step).apply()
    }
}
