// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.content.Context
import android.os.Handler
import android.util.Log
import com.keremgok.hermessim.wire.Code
import com.keremgok.hermessim.wire.HostVerb

/** Whether a peer's HostAction may run. Pure. */
object HostActionCore {
    /** Seconds airplane mode stays on for a reconnect. */
    const val CYCLE_SECONDS = 5

    /** When a hotspot that was on before a reconnect is asked to come back, counted from the request. */
    val HOTSPOT_RETRY_MS = listOf(15_000L, 30_000L, 45_000L)

    /**
     * Null when the action may run. A call up refuses a reconnect and a restart; a hotspot report is taken during a
     * call too (HotspotWatch makes its step wait); an unknown action is a bad value.
     */
    fun refusal(verb: HostVerb, inCall: Boolean): Code? = when (verb) {
        HostVerb.HOST_VERB_HOTSPOT_NO_INTERNET -> null
        HostVerb.HOST_VERB_REGISTER_AGAIN, HostVerb.HOST_VERB_RESTART -> if (inCall) Code.CODE_IN_CALL else null
        else -> Code.CODE_BAD_VALUE
    }
}

/**
 * Reconnects the host's network (the daemon's detached airplane-mode cycle) or restarts the phone, for a client or a
 * Mac, or hands a phone's failed hotspot check to HotspotWatch. A hotspot that was on comes back on after a reconnect.
 * Main thread only.
 */
object HostActions {
    private const val TAG = "HostActions"
    private const val REBOOT_DELAY_MS = 1_000L

    fun run(ctx: Context, verb: HostVerb, main: Handler, answer: (Code) -> Unit) {
        val refusal = HostActionCore.refusal(verb, CallRegistry.anyCall())
        if (refusal != null) {
            Log.i(TAG, "$verb refused: $refusal")
            return answer(refusal)
        }
        if (verb == HostVerb.HOST_VERB_HOTSPOT_NO_INTERNET) {
            answer(Code.CODE_OK)
            return HotspotWatch.reported("a phone")
        }
        if (verb == HostVerb.HOST_VERB_RESTART) {
            Log.i(TAG, "restart asked: in ${REBOOT_DELAY_MS / 1000} s")
            answer(Code.CODE_OK)
            main.postDelayed({ Installer.reboot() }, REBOOT_DELAY_MS)
            return
        }
        val hotspotWasOn = Hotspot.isOn(ctx)
        Daemon.query("NETCYCLE ${HostActionCore.CYCLE_SECONDS}") { reply ->
            val ok = reply?.startsWith("OK") == true
            Log.i(TAG, "network reconnect: ${reply ?: "no daemon"}${if (hotspotWasOn) " (hotspot comes back on)" else ""}")
            answer(if (ok) Code.CODE_OK else Code.CODE_FAILED)
            if (ok && hotspotWasOn) HostActionCore.HOTSPOT_RETRY_MS.forEach { delay ->
                main.postDelayed({ hotspotBack(ctx) }, delay)
            }
        }
    }

    private fun hotspotBack(ctx: Context) {
        if (Hotspot.upOrStarting(ctx)) return
        Hotspot.set(ctx, true) { ok, detail -> Log.i(TAG, "hotspot back after the reconnect: $ok $detail") }
    }
}
