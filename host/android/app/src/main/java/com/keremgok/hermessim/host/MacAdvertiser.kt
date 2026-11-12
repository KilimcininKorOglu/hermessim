// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.os.Handler
import android.util.Log

/**
 * The Macs' advertising set, apart from the phone's: a central connecting stops a connectable legacy set, and the
 * phone's comes back only when the phone disconnects. It advertises while [wanted], with the Mac pairing UUID while
 * [pairing], else the service. [refused] runs once the controller turns a second set down. Main thread throughout.
 */
@SuppressLint("MissingPermission") // runtime permissions are granted by the Magisk module (headless host)
class MacAdvertiser(
    private val main: Handler,
    private val bt: BluetoothManager,
    private val wanted: () -> Boolean,
    private val pairing: () -> Boolean,
    private val refused: () -> Unit,
) {
    private companion object { const val TAG = "HermesSimHost" }

    private var advSet: AdvertisingSet? = null
    private var advStarting = false
    @Volatile private var advRefused = false

    /** Two sets at once: the controller says it can, and has not refused ours. */
    fun supported(): Boolean = !advRefused && bt.adapter?.isMultipleAdvertisementSupported == true

    private fun adData() = BleLink.advertised(if (pairing()) BleLink.MAC_PAIR_SERVICE else BleLink.SERVICE)

    private val advCallback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
            advStarting = false
            if (status == ADVERTISE_SUCCESS) { advSet = set; Log.i(TAG, "Mac advertising"); kick(); return }
            Log.w(TAG, "Mac advertising failed $status")
            if (status == ADVERTISE_FAILED_TOO_MANY_ADVERTISERS || status == ADVERTISE_FAILED_FEATURE_UNSUPPORTED) { advRefused = true; refused() }
        }
        override fun onAdvertisingSetStopped(set: AdvertisingSet?) { if (advSet === set) advSet = null }
    }

    /** After any connection change: the set is on while wanted (a Mac connecting through it switched it off), else off. */
    fun kick(): Boolean = main.post {
        val set = advSet
        if (set != null) { runCatching { set.enableAdvertising(wanted(), 0, 0) }.onFailure { advSet = null }; return@post }
        if (!wanted() || advStarting || !supported()) return@post
        val adv = bt.adapter?.bluetoothLeAdvertiser ?: return@post
        val params = AdvertisingSetParameters.Builder().setLegacyMode(true).setConnectable(true).setScannable(true)
            .setInterval(AdvertisingSetParameters.INTERVAL_MEDIUM).setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM).build()
        advStarting = true
        runCatching { adv.startAdvertisingSet(params, adData(), null, null, null, advCallback) }
            .onFailure { advStarting = false; Log.w(TAG, "Mac advertising: $it") }
    }

    /** The advertised UUID may have changed (the pairing window opened or closed). */
    fun refresh() {
        advSet?.let { set -> runCatching { set.setAdvertisingData(adData()) } }
        kick()
    }

    /** Bluetooth went off: the set went with it. */
    fun lost() { advSet = null; advStarting = false }

    fun stop() {
        runCatching { bt.adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(advCallback) }
        lost()
    }
}
