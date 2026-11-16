// HermesSIM host. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
package com.keremgok.hermessim.host

/**
 * What the setup screen knows about hermessimd, kept as one value: the screen reads the last reply and the verdict
 * on it together, so it never shows the daemon down while its reply is OK. Pure (DaemonHealthTest).
 */
data class DaemonHealth(val reply: String?, val fails: Int, val probed: Boolean) {
    enum class Verdict { UP, WAIT, DOWN }

    val verdict: Verdict
        get() = when {
            reply?.startsWith("OK") == true -> Verdict.UP
            probed -> Verdict.DOWN
            else -> Verdict.WAIT
        }

    /**
     * The state after a probe answered [r] (null: no answer). The daemon is down only after two failed answers, or
     * one once the screen has been up for [FIRST_MS] (a cold start right after boot or a crash gets its time).
     */
    fun after(r: String?, screenAgeMs: Long): DaemonHealth {
        val ok = r?.startsWith("OK") == true
        val failed = if (ok) 0 else fails + 1
        return DaemonHealth(r, failed, ok || failed >= 2 || screenAgeMs > FIRST_MS && failed >= 1)
    }

    companion object {
        const val FIRST_MS = 15_000L
        val UNKNOWN = DaemonHealth(null, 0, false)
    }
}
