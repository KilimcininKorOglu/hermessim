// HermesSIM host. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
package com.keremgok.hermessim.host

/**
 * How often the host advertises (BleLink). A phone finds its host fastest at the short interval (~100 ms), which
 * matters right after a link drops and while the host waits to be paired. Once no phone has been linked for
 * [SLOW_AFTER_MS], the phone is away and the host advertises at the medium interval (~250 ms) to spend less battery.
 * Pure (AdvertPaceTest).
 */
object AdvertPace {
    const val SLOW_AFTER_MS = 120_000L

    /** [msSincePhoneDown] counts from the last phone link's end, or from the host's start when none came yet. */
    fun slow(pairable: Boolean, phoneLinked: Boolean, msSincePhoneDown: Long): Boolean =
        !pairable && !phoneLinked && msSincePhoneDown >= SLOW_AFTER_MS

    /** How long until [slow] can turn true: 0 when it already can. */
    fun waitMs(msSincePhoneDown: Long): Long = (SLOW_AFTER_MS - msSincePhoneDown).coerceAtLeast(0)
}
