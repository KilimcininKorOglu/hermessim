// HermesSIM host. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import com.keremgok.hermessim.host.wire.StateBook
import com.keremgok.hermessim.wire.Code
import com.keremgok.hermessim.wire.SetHotspotSetup

/** What the host's one name asks of the hotspot (protocol/v2/SPEC.md "State"). Pure. */
object HostNameCore {
    /** The most bytes an SSID may have. */
    const val SSID_MAX_BYTES = 32

    sealed interface Step {
        /** The hotspot already carries the name, has none to carry, or the name cannot be an SSID. */
        data object None : Step
        /** A call is up: the hotspot is not started again until it ends. */
        data object WaitForCall : Step
        data class Rename(val ssid: String) : Step
    }

    /** What to do so the hotspot [hotspot] carries the Bluetooth name [btName]. */
    fun step(btName: String, hotspot: HotspotInfo, inCall: Boolean): Step {
        val name = btName.trim()
        return when {
            // Android 11 reads the settings as root a moment after start: until then the SSID is unknown
            !hotspot.available || hotspot.ssid.isEmpty() -> Step.None
            name.isEmpty() || name.toByteArray(Charsets.UTF_8).size > SSID_MAX_BYTES || name == hotspot.ssid -> Step.None
            inCall -> Step.WaitForCall
            else -> Step.Rename(name)
        }
    }

    /**
     * The host's name a request asks for, trimmed: null when [s] names none, [BtName.Check.Empty] or
     * [BtName.Check.TooLong] when it cannot be the host's name (1 to 29 bytes, what fits in the scan response).
     */
    fun requested(s: SetHotspotSetup): BtName.Check? = if (s.hasName()) BtName.check(s.name) else null
}

/**
 * The host's one name: Android's Bluetooth name, which the hotspot carries too and peers list the host by (the
 * `name` group). A change of the Bluetooth name, from this app, a peer or Android's settings, renames the hotspot.
 */
object HostName {
    private const val TAG = "HermesSimHost"
    private const val CALL_ALARM = "host-name-call"
    private const val CALL_RECHECK_MS = 20_000L

    /**
     * The Bluetooth name; null when Android does not give it (Bluetooth off, or no permission yet), and then nothing
     * is renamed and peers keep the name they have.
     */
    @Suppress("MissingPermission")
    fun current(ctx: Context): String? =
        runCatching { ctx.getSystemService(BluetoothManager::class.java).adapter?.name }.getOrNull()?.trim()
            ?.takeIf { it.isNotEmpty() }

    /** Sets the Bluetooth name; true once Android took it. The hotspot follows when Android reports the change. */
    @Suppress("MissingPermission")
    fun set(ctx: Context, name: String): Boolean {
        val adapter = ctx.getSystemService(BluetoothManager::class.java).adapter ?: return false
        val ok = runCatching { adapter.setName(name) }.onFailure { Log.w(TAG, "Bluetooth name not set: $it") }.getOrDefault(false)
        Log.i(TAG, "Bluetooth name set to ${name.toByteArray().size} bytes -> $ok")
        return ok
    }

    /** The Bluetooth name changed, or the host app started: peers get the name, and the hotspot takes it. */
    fun changed(ctx: Context) {
        Relay.touch(StateBook.NAME)
        sync(ctx.applicationContext)
    }

    private fun sync(app: Context) {
        val name = current(app) ?: return
        Hotspot.rename(app, { hotspot -> ssidFor(app, name, hotspot) }) { code ->
            if (code != Code.CODE_OK) Log.w(TAG, "hotspot kept its old name: $code")
            Relay.touchLater(1500, StateBook.HOTSPOT)
        }
    }

    /** The SSID the hotspot takes now, or null: it has the name, or a call is up and the rename waits for its end. */
    private fun ssidFor(app: Context, name: String, hotspot: HotspotInfo): String? =
        when (val step = HostNameCore.step(name, hotspot, CallRegistry.anyCall())) {
            HostNameCore.Step.None -> null
            HostNameCore.Step.WaitForCall -> { waitForCall(app); null }
            is HostNameCore.Step.Rename -> step.ssid.also { Log.i(TAG, "hotspot takes the host's name (${it.toByteArray().size} bytes)") }
        }

    /** A call is up: look again every 20 s, and rename the hotspot once no call is left. */
    private fun waitForCall(app: Context) {
        if (!WakeAlarm.pending(CALL_ALARM)) Log.i(TAG, "hotspot name waits for the end of the call")
        WakeAlarm.set(app, CALL_ALARM, CALL_RECHECK_MS) { sync(app) }
    }
}
