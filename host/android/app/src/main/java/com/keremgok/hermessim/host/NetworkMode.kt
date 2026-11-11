// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.content.Context
import android.telephony.TelephonyManager
import android.util.Log
import com.keremgok.hermessim.protocol.v1.Sim

/**
 * A line's network mode as a ceiling on network generations (protocol/README.md "Network mode"): the masks are
 * TelephonyManager network type bitmasks, as the modem reports them in getSupportedRadioAccessFamily.
 */
object NetworkModeCore {
    const val NR = 1L shl 19                            // NETWORK_TYPE_BITMASK_NR
    const val LTE = (1L shl 12) or (1L shl 18)          // NETWORK_TYPE_BITMASK_LTE | _LTE_CA

    /** The choices a modem with [supported] offers: none when only one would make sense. */
    fun modes(supported: Long): List<Sim.NetworkMode> {
        if (supported and LTE == 0L) return emptyList()
        val l = mutableListOf(Sim.NetworkMode.AUTO)
        if (supported and NR != 0L) l += Sim.NetworkMode.UP_TO_4G
        if (supported and (NR or LTE).inv() != 0L) l += Sim.NetworkMode.UP_TO_3G
        return if (l.size > 1) l else emptyList()
    }

    /** The mask that allows [mode] and every generation below it. */
    fun mask(mode: Sim.NetworkMode, supported: Long): Long = when (mode) {
        Sim.NetworkMode.UP_TO_4G -> supported and NR.inv()
        Sim.NetworkMode.UP_TO_3G -> supported and (NR or LTE).inv()
        else -> supported
    }

    /** The mode an allowed mask stands for: the highest generation it lets through. */
    fun modeOf(allowed: Long, supported: Long): Sim.NetworkMode = when {
        allowed and supported and LTE == 0L -> Sim.NetworkMode.UP_TO_3G
        supported and NR != 0L && allowed and NR == 0L -> Sim.NetworkMode.UP_TO_4G
        else -> Sim.NetworkMode.AUTO
    }

    /** The reason a client's choice is refused, or null. */
    fun refusal(mode: Sim.NetworkMode, supported: Long, inCall: Boolean): String? = when {
        inCall -> "a call is up: change the network mode after it"
        mode !in modes(supported) -> "this line can't use that network mode"
        else -> null
    }
}

/**
 * The user's network mode on a line, on the modem. Android 12+: the USER reason of the allowed network types
 * (@SystemApi, MODIFY_PHONE_STATE), the one the phone's own settings write; box mode's 5G cut is the POWER reason,
 * and the modem takes the intersection. Android 11: the preferred network type bitmask (box mode there uses the
 * allowed types, intersected the same way). Reached by reflection, as every system API of the host.
 */
object NetworkModes {
    private const val TAG = "HermesSimHost"
    private const val REASON_USER = 0                   // TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER
    private val c = TelephonyManager::class.java

    fun supported(tm: TelephonyManager): Long =
        runCatching { c.getMethod("getSupportedRadioAccessFamily").invoke(tm) as Long }
            .onFailure { Log.w(TAG, "network modes: supported types unknown: ${it.cause ?: it}") }.getOrDefault(0L)

    /** The line's mode and its choices into the status. */
    fun describe(tm: TelephonyManager, b: Sim.Builder) {
        val supported = supported(tm)
        val modes = NetworkModeCore.modes(supported)
        if (modes.isEmpty()) return
        val allowed = runCatching { allowed(tm) }
            .onFailure { Log.w(TAG, "network modes: allowed types unknown: ${it.cause ?: it}") }.getOrNull() ?: return
        b.setNetworkMode(NetworkModeCore.modeOf(allowed, supported)).addAllNetworkModes(modes)
    }

    /** Sets [mode] on the line of [sub]; null on success, else a short reason. */
    fun set(ctx: Context, sub: Int, slot: Int, mode: Sim.NetworkMode, inCall: Boolean): String? {
        val tm = ctx.getSystemService(TelephonyManager::class.java).createForSubscriptionId(sub)
        val supported = supported(tm)
        NetworkModeCore.refusal(mode, supported, inCall)?.let { return it }
        val mask = NetworkModeCore.mask(mode, supported)
        return runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 31)
                c.getMethod("setAllowedNetworkTypesForReason", Int::class.java, Long::class.java).invoke(tm, REASON_USER, mask)
            else c.getMethod("setPreferredNetworkTypeBitmask", Long::class.java).invoke(tm, mask)
            Log.i(TAG, "SIM slot $slot network mode $mode")
        }.fold({ null }, { Log.w(TAG, "network mode, SIM slot $slot: ${it.cause ?: it}"); "could not change the network mode" })
    }

    private fun allowed(tm: TelephonyManager): Long =
        if (android.os.Build.VERSION.SDK_INT >= 31)
            c.getMethod("getAllowedNetworkTypesForReason", Int::class.java).invoke(tm, REASON_USER) as Long
        else c.getMethod("getPreferredNetworkTypeBitmask").invoke(tm) as Long
}
