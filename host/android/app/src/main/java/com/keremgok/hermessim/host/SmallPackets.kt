// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Whether this host's Bluetooth link sends in the smallest radio packets, 27 bytes: seen in the chip's record
 * ([RadioLook]), by this run or an earlier one (a call can start before the first look at a new link). A Galaxy A13's
 * chip allows no more, and such a link is the only one whose call audio may go lean ([LeanAudio]).
 */
object SmallPackets {
    private const val TAG = "HermesSimHost"

    private var prefs: SharedPreferences? = null
    @Volatile private var seen: Boolean? = null

    /** adb debug (AUDIO_LEAN): treat this host's link as one of small packets until the app restarts. */
    @Volatile var forced = false

    /** Reads what earlier looks learned about this host's link (HostApp, once). */
    fun init(ctx: Context) {
        val p = ctx.applicationContext.getSharedPreferences("link", Context.MODE_PRIVATE)
        prefs = p
        if (p.contains("small")) seen = p.getBoolean("small", false)
    }

    /**
     * The link sends in small packets. False until a record says so: on every other host a struggling link means the
     * phone is leaving Bluetooth range, and that call belongs on the internet, not on a lower bit rate.
     */
    val small: Boolean get() = forced || seen == true

    /** For a call's numbers: "yes", "no", "?" (no record ever said), "forced". */
    fun text(): String = when {
        forced -> "forced"
        seen == null -> "?"
        seen == true -> "yes"
        else -> "no"
    }

    /** A look saw what the link agreed ([RadioLook]); kept across restarts. */
    fun learned(link: HciRing.Link?) {
        val s = link?.small ?: return
        if (s == seen) return
        seen = s
        Log.i(TAG, "BLE link: ${if (s) "small radio packets (27 B) on this host" else "radio packets longer than 27 B on this host"}")
        prefs?.edit()?.putBoolean("small", s)?.apply()
    }
}
