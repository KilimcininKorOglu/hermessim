// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import com.keremgok.hermessim.protocol.v1.Update

/**
 * The update rules, apart from the network and the installer. Automatic updates are a setting, off by default: while
 * it is off a newer version is only offered (AVAILABLE) and "Update now" downloads and installs it; while it is on the
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
        Update.State.READY to Install.INSTALL,
        Update.State.AVAILABLE to Install.DOWNLOAD_THEN_INSTALL,
    )

    /** What "Update now" does in [state]. */
    fun installNow(state: Update.State): Install = ON_INSTALL_NOW[state] ?: Install.NONE

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
}
