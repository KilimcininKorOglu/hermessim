// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import com.keremgok.hermessim.protocol.v1.MacStatus

/** Whole seconds from [now] until [until] (elapsed-realtime ms), never negative: a pairing window's countdown. */
fun secondsLeft(until: Long, now: Long): Int = ((until - now) / 1000).coerceAtLeast(0).toInt()

/**
 * The rules of Mac access (protocol/README.md "Mac"), apart from Android so they are tested on their own: which Macs
 * may ask for the hotspot, the pairing window that admits a new one, and the state a Mac is shown.
 */
class MacCore(private val now: () -> Long) {
    data class Mac(val address: String, val name: String)

    enum class Access { ALLOW, ADMIT, DENY }

    companion object {
        const val MAX_MACS = 4
        const val WINDOW_MS = 120_000L

        /** One stored entry: "address|name" (an address never holds a '|'). */
        fun encode(m: Mac): String = "${m.address}|${m.name}"
        fun decode(s: String): Mac? = s.split('|', limit = 2).takeIf { it.size == 2 && it[0].isNotEmpty() }?.let { Mac(it[0], it[1]) }

        /** The state a Mac is shown: a start that failed stays FAILED until the hotspot comes on or is switched again. */
        fun state(on: Boolean, starting: Boolean, failed: Boolean): MacStatus.State = when {
            on -> MacStatus.State.ON
            starting -> MacStatus.State.STARTING
            failed -> MacStatus.State.FAILED
            else -> MacStatus.State.OFF
        }
    }

    var macs: List<Mac> = emptyList()
        private set
    private var windowUntil = 0L

    fun load(stored: Collection<String>) { macs = stored.mapNotNull(::decode).distinctBy { it.address }.take(MAX_MACS) }
    fun stored(): Set<String> = macs.map(::encode).toSet()

    fun windowOpen(): Boolean = now() < windowUntil
    fun secondsLeft(): Int = secondsLeft(windowUntil, now())
    fun openWindow() { windowUntil = now() + WINDOW_MS }
    fun closeWindow() { windowUntil = 0 }

    fun isMac(address: String): Boolean = macs.any { it.address == address }

    /**
     * A central reading or writing the Mac service (only over an encrypted link, so it is bonded): a listed Mac is
     * served; inside the window a new one is admitted while there is room; anyone else is refused.
     */
    fun access(address: String): Access = when {
        isMac(address) -> Access.ALLOW
        windowOpen() && macs.size < MAX_MACS -> Access.ADMIT
        else -> Access.DENY
    }

    /** Puts [mac] on the list and closes the window: one pairing per window. */
    fun admit(mac: Mac) {
        if (!isMac(mac.address) && macs.size < MAX_MACS) macs = macs + mac
        closeWindow()
    }

    /** True if [address] was on the list. */
    fun remove(address: String): Boolean {
        val before = macs.size
        macs = macs.filterNot { it.address == address }
        return macs.size != before
    }
}
