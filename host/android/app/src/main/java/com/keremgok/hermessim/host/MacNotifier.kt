// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothManager
import android.os.Build
import android.util.Log
import java.util.UUID

/**
 * The Mac service's notifying characteristics (status and texts): who subscribed to each, and one notification in
 * flight per Mac link, the rest waiting in order. [value] gives what a Mac is notified of. Main thread throughout.
 */
@SuppressLint("MissingPermission") // runtime permissions are granted by the Magisk module (headless host)
class MacNotifier(private val bt: BluetoothManager, private val value: (address: String, uuid: UUID) -> ByteArray) {
    private companion object { const val TAG = "HermesSimHost" }

    private var gatt: BluetoothGattServer? = null
    private val chars = HashMap<UUID, BluetoothGattCharacteristic>()
    private val subscribed = HashMap<UUID, HashSet<String>>()
    private val inFlight = HashSet<String>()
    /** Per Mac, the characteristics to notify once its notification in flight is sent. */
    private val again = HashMap<String, LinkedHashSet<UUID>>()

    /** New characteristics for a new GATT server (Bluetooth restarted: the old ones went with it). */
    fun characteristics(server: BluetoothGattServer, uuids: List<UUID>): List<BluetoothGattCharacteristic> {
        gatt = server
        chars.clear()
        return uuids.map { uuid -> characteristic(uuid).also { chars[uuid] = it } }
    }

    private fun characteristic(uuid: UUID) = BluetoothGattCharacteristic(uuid,
        BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED).apply {
        addDescriptor(BluetoothGattDescriptor(BleLink.CCCD,
            BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED))
    }

    fun subscribe(uuid: UUID, address: String, on: Boolean) {
        val set = subscribed.getOrPut(uuid) { HashSet() }
        if (on) set.add(address) else set.remove(address)
    }

    fun subscribers(uuid: UUID): List<String> = subscribed[uuid].orEmpty().toList()

    /** The Mac's link went down, or the Mac was removed. */
    fun drop(address: String) { subscribed.values.forEach { it.remove(address) }; inFlight.remove(address); again.remove(address) }

    /** Bluetooth is going off. */
    fun clear() {
        gatt = null
        for (map in listOf<MutableMap<*, *>>(subscribed, again)) map.clear()
        inFlight.clear()
    }

    fun sent(address: String) {
        inFlight.remove(address)
        val next = again[address]?.firstOrNull() ?: return
        again.getValue(address).remove(next)
        notify(address, next)
    }

    @Suppress("DEPRECATION")
    fun notify(address: String, uuid: UUID) {
        val g = gatt ?: return
        val char = chars[uuid] ?: return
        if (address in inFlight) { again.getOrPut(address) { LinkedHashSet() }.add(uuid); return }
        val device = bt.adapter?.getRemoteDevice(address) ?: return
        val bytes = value(address, uuid)
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= 33) g.notifyCharacteristicChanged(device, char, false, bytes) == android.bluetooth.BluetoothStatusCodes.SUCCESS
            else { char.value = bytes; g.notifyCharacteristicChanged(device, char, false) }
        }.getOrDefault(false)
        if (ok) inFlight.add(address) else Log.w(TAG, "Mac $address: $uuid not sent")
    }
}
