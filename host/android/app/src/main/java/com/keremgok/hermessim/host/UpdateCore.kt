// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import com.keremgok.hermessim.wire.UpdateFault
import com.keremgok.hermessim.wire.UpdatePhase

/**
 * The update rules, apart from the network and the installer. Automatic updates are a setting, off by default: while
 * it is off a newer version is only offered (UPDATE_PHASE_AVAILABLE) and "Update now" downloads and installs it; while it is on the
 * host downloads it by itself and installs it at night once a client has been told. Pure, for [UpdateCoreTest].
 */
object UpdateCore {
    const val HOUR_MS = 3600_000L
    private val NIGHT = 2..4                           // 02:00 to 04:59 host time

    enum class Next { DOWNLOAD, OFFER }
    enum class Install { INSTALL, DOWNLOAD_THEN_INSTALL, NONE }

    /** After a check found a newer version: download it, or only offer it. A verified download is always kept. */
    fun afterCheck(auto: Boolean, downloaded: Boolean): Next = if (auto || downloaded) Next.DOWNLOAD else Next.OFFER

    private val ON_INSTALL_NOW = mapOf(
        UpdatePhase.UPDATE_PHASE_READY to Install.INSTALL,
        UpdatePhase.UPDATE_PHASE_AVAILABLE to Install.DOWNLOAD_THEN_INSTALL,
    )

    /** What "Update now" does in [state]. */
    fun installNow(state: UpdatePhase): Install = ON_INSTALL_NOW[state] ?: Install.NONE

    /** Facts the night install needs. [toldAtMs]: when a client was first shown this version (0: never). */
    data class Night(
        val auto: Boolean,
        val ready: Boolean,
        val hour: Int,
        val nowMs: Long,
        val deferredUntilMs: Long,
        val toldAtMs: Long,
    )

    /** Install by itself now: the setting is on, a verified update waits, it is night, not postponed, and a client was
     *  told at least an hour ago (so the user had time to say "Later"). */
    fun nightInstall(n: Night): Boolean =
        n.auto && n.ready && n.hour in NIGHT && n.nowMs >= n.deferredUntilMs && told(n)

    private fun told(n: Night) = n.toldAtMs > 0 && n.nowMs - n.toldAtMs >= HOUR_MS

    /** Where the module keeps this app as a system app (customize.sh); Android starts it from here at boot. */
    const val MODULE_APK = "/data/adb/modules/hermessim-host/system/priv-app/HermesSimHost/HermesSimHost.apk"

    /**
     * The root command that installs a verified update: the app is a persistent system app, which `pm install` refuses
     * to replace, so the new APK takes the module's place and the phone restarts into it. The copy lands next to the
     * old APK and replaces it in one rename, and the restart runs only after that rename worked; a missing module
     * fails before anything is written. [quote] quotes a path for the shell; [moduleApk] and [restart] are the real
     * ones except in tests.
     */
    fun moduleInstall(
        apk: String,
        quote: (String) -> String,
        moduleApk: String = MODULE_APK,
        restart: String = "reboot",
    ): String {
        val dst = quote(moduleApk)
        val tmp = quote("$moduleApk.new")
        return "test -f $dst && cp ${quote(apk)} $tmp && chmod 644 $tmp && mv -f $tmp $dst && sync && $restart"
    }

    const val DETAIL_MAX = 120

    /** The failed step's own text for the phone: one line, at most [DETAIL_MAX] characters; empty without a fault. */
    fun faultDetail(fault: UpdateFault, detail: String): String {
        if (fault == UpdateFault.UPDATE_FAULT_UNSPECIFIED) return ""
        return detail.map { if (it.isISOControl()) ' ' else it }.joinToString("")
            .replace(Regex(" {2,}"), " ").trim().take(DETAIL_MAX)
    }
}
