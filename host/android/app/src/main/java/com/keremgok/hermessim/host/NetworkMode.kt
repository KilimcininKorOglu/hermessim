// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.content.Context
import android.telephony.TelephonyManager
import android.util.Log
import com.keremgok.hermessim.wire.Code
import com.keremgok.hermessim.wire.NetworkCeiling

/**
 * A line's network mode as a ceiling on network generations (`NetworkCeiling`): the masks are TelephonyManager
 * network type bitmasks, as the modem reports them in getSupportedRadioAccessFamily.
 */
object NetworkModeCore {
    const val NR = 1L shl 19                            // NETWORK_TYPE_BITMASK_NR
    const val LTE = (1L shl 12) or (1L shl 18)          // NETWORK_TYPE_BITMASK_LTE | _LTE_CA

    /** The choices a modem with [supported] offers: none when only one would make sense. */
    fun modes(supported: Long): List<NetworkCeiling> {
        if (supported and LTE == 0L) return emptyList()
        val l = mutableListOf(NetworkCeiling.NETWORK_CEILING_NONE)
        if (supported and NR != 0L) l += NetworkCeiling.NETWORK_CEILING_4G
        if (supported and (NR or LTE).inv() != 0L) l += NetworkCeiling.NETWORK_CEILING_3G
        return if (l.size > 1) l else emptyList()
    }

    /** The mask that allows [mode] and every generation below it. */
    fun mask(mode: NetworkCeiling, supported: Long): Long = when (mode) {
        NetworkCeiling.NETWORK_CEILING_4G -> supported and NR.inv()
        NetworkCeiling.NETWORK_CEILING_3G -> supported and (NR or LTE).inv()
        else -> supported
    }

    /** The mode an allowed mask stands for: the highest generation it lets through. */
    fun modeOf(allowed: Long, supported: Long): NetworkCeiling = when {
        allowed and supported and LTE == 0L -> NetworkCeiling.NETWORK_CEILING_3G
        supported and NR != 0L && allowed and NR == 0L -> NetworkCeiling.NETWORK_CEILING_4G
        else -> NetworkCeiling.NETWORK_CEILING_NONE
    }

    /** Why a peer's choice is refused, or null. */
    fun refusal(mode: NetworkCeiling, supported: Long, inCall: Boolean): Code? = when {
        inCall -> Code.CODE_IN_CALL
        mode !in modes(supported) -> Code.CODE_NOT_OFFERED
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

    /** The line's mode and its choices; null when the modem offers no choice or its mode cannot be read. */
    fun describe(tm: TelephonyManager): Pair<NetworkCeiling, List<NetworkCeiling>>? {
        val supported = supported(tm)
        val modes = NetworkModeCore.modes(supported)
        if (modes.isEmpty()) return null
        val allowed = runCatching { allowed(tm) }
            .onFailure { Log.w(TAG, "network modes: allowed types unknown: ${it.cause ?: it}") }.getOrNull() ?: return null
        return NetworkModeCore.modeOf(allowed, supported) to modes
    }

    /** Sets [mode] on the line of [sub]; CODE_OK, or why not. */
    fun set(ctx: Context, sub: Int, slot: Int, mode: NetworkCeiling, inCall: Boolean): Code {
        val tm = ctx.getSystemService(TelephonyManager::class.java).createForSubscriptionId(sub)
        val supported = supported(tm)
        NetworkModeCore.refusal(mode, supported, inCall)?.let { return it }
        val mask = NetworkModeCore.mask(mode, supported)
        return runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 31)
                c.getMethod("setAllowedNetworkTypesForReason", Int::class.java, Long::class.java).invoke(tm, REASON_USER, mask)
            else c.getMethod("setPreferredNetworkTypeBitmask", Long::class.java).invoke(tm, mask)
            Log.i(TAG, "SIM slot $slot network mode $mode")
        }.fold({ Code.CODE_OK }, { Log.w(TAG, "network mode, SIM slot $slot: ${it.cause ?: it}"); Code.CODE_FAILED })
    }

    private fun allowed(tm: TelephonyManager): Long =
        if (android.os.Build.VERSION.SDK_INT >= 31)
            c.getMethod("getAllowedNetworkTypesForReason", Int::class.java).invoke(tm, REASON_USER) as Long
        else c.getMethod("getPreferredNetworkTypeBitmask").invoke(tm) as Long
}
