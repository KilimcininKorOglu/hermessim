// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.keremgok.hermessim.protocol.v1.MacAccess
import com.keremgok.hermessim.protocol.v1.MacCommand
import com.keremgok.hermessim.protocol.v1.MacSecret
import com.keremgok.hermessim.protocol.v1.MacStatus
import com.keremgok.hermessim.protocol.v1.PairedMac
import java.util.UUID

/**
 * The Mac service (protocol/README.md "Mac"): a Mac reads the hotspot's state and passphrase and switches it, nothing
 * else. It lives on BleLink's GATT server, which hands it every request for this service, and runs its own advertising
 * set, so a Mac finds the host while the client is connected and the client's set is off. Main thread throughout.
 */
@SuppressLint("MissingPermission") // runtime permissions are granted by the Magisk module (headless host)
class MacLink(
    private val ctx: Context,
    private val status: () -> MacStatus,
    private val secret: () -> String,
    private val command: (MacCommand) -> Unit,
    private val changed: () -> Unit,
    private val unbond: (String) -> Unit,
) {
    companion object {
        private const val TAG = "HermesSimHost"
        val SERVICE: UUID = UUID.fromString("0e9c0011-0971-48af-ba0b-2c5c254faec0")
        val STATUS: UUID = UUID.fromString("0e9c0012-0971-48af-ba0b-2c5c254faec0")
        val COMMAND: UUID = UUID.fromString("0e9c0013-0971-48af-ba0b-2c5c254faec0")
        val SECRET: UUID = UUID.fromString("0e9c0014-0971-48af-ba0b-2c5c254faec0")
        val PAIR: UUID = UUID.fromString("0e9c0015-0971-48af-ba0b-2c5c254faec0")
        private const val INSUFFICIENT_AUTHORIZATION = 0x08     // ATT error; BluetoothGatt names it only from Android 14
        private const val INVALID_OFFSET = 0x07
    }

    private val main = Handler(Looper.getMainLooper())
    private val bt = ctx.getSystemService(BluetoothManager::class.java)
    private val prefs = ctx.getSharedPreferences("mac", Context.MODE_PRIVATE)
    private val core = MacCore(SystemClock::elapsedRealtime).apply { load(prefs.getStringSet("macs", emptySet()).orEmpty()) }
    private var gatt: BluetoothGattServer? = null
    private lateinit var statusChar: BluetoothGattCharacteristic
    private val subscribed = HashSet<String>()
    private val connected = HashSet<String>()
    private val inFlight = HashSet<String>()
    private val again = HashSet<String>()

    /** A Mac was just put on the list (BleLink: it may have come in through the client's set, which it switched off). */
    var admitted: (() -> Unit)? = null

    fun isMac(address: String): Boolean = core.isMac(address)
    fun windowOpen(): Boolean = core.windowOpen()

    /** For HostStatus.mac. */
    fun access(): MacAccess = MacAccess.newBuilder().setSupported(supported()).setPairing(core.windowOpen())
        .addAllMacs(core.macs.map { PairedMac.newBuilder().setName(it.name).setAddress(it.address).setConnected(it.address in connected).build() })
        .build()

    /** A new GATT service object for each GATT server (Bluetooth restarted: the old one went with it). */
    fun service(server: BluetoothGattServer): BluetoothGattService {
        gatt = server
        statusChar = BluetoothGattCharacteristic(STATUS, BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED).apply {
            addDescriptor(BluetoothGattDescriptor(BleLink.CCCD,
                BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED))
        }
        return BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(statusChar)
            addCharacteristic(BluetoothGattCharacteristic(COMMAND, BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED))
            addCharacteristic(BluetoothGattCharacteristic(SECRET, BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED))
        }
    }

    // ---------------------------------------------------------------- requests (binder threads: answered on main)

    fun onRead(device: BluetoothDevice, requestId: Int, offset: Int, uuid: UUID) = main.post {
        if (!allowed(device)) return@post respond(device, requestId, INSUFFICIENT_AUTHORIZATION, null)
        val value = if (uuid == SECRET) MacSecret.newBuilder().setPassphrase(secret()).build().toByteArray()
            else status().toByteArray()
        if (offset > value.size) return@post respond(device, requestId, INVALID_OFFSET, null)
        respond(device, requestId, BluetoothGatt.GATT_SUCCESS, value.copyOfRange(offset, value.size), offset)
    }

    fun onWrite(device: BluetoothDevice, requestId: Int, uuid: UUID, responseNeeded: Boolean, value: ByteArray) = main.post {
        val ok = allowed(device)
        val cmd = if (ok && uuid == COMMAND) runCatching { MacCommand.parseFrom(value) }.getOrNull() else null
        val code = when { !ok -> INSUFFICIENT_AUTHORIZATION; cmd == null -> BluetoothGatt.GATT_FAILURE; else -> BluetoothGatt.GATT_SUCCESS }
        if (responseNeeded) respond(device, requestId, code, null)
        if (cmd != null) {
            Log.i(TAG, "Mac ${device.address}: hotspot ${if (cmd.on) "on" else "off"}${if (cmd.offWhenUnusedS > 0) " once unused ${cmd.offWhenUnusedS} s" else ""}")
            command(cmd)
        }
    }

    fun onCccd(device: BluetoothDevice, requestId: Int, responseNeeded: Boolean, value: ByteArray) = main.post {
        val ok = allowed(device)
        if (ok) { if (value.isNotEmpty() && value[0].toInt() and 1 == 1) subscribed.add(device.address) else subscribed.remove(device.address) }
        if (responseNeeded) respond(device, requestId, if (ok) BluetoothGatt.GATT_SUCCESS else INSUFFICIENT_AUTHORIZATION, null)
    }

    /** A listed Mac is served; inside the window a bonded newcomer is put on the list first. */
    private fun allowed(device: BluetoothDevice): Boolean = when (core.access(device.address)) {
        MacCore.Access.ALLOW -> true
        MacCore.Access.DENY -> { Log.w(TAG, "Mac service: ${device.address} is not a paired Mac"); false }
        MacCore.Access.ADMIT -> {
            if (device.bondState != BluetoothDevice.BOND_BONDED) false
            else {
                core.admit(MacCore.Mac(device.address, device.name ?: "Mac"))
                save()
                connected.add(device.address)
                Log.i(TAG, "Mac paired: ${device.name} ${device.address}")
                refreshAdvertising()
                admitted?.invoke()
                changed()
                true
            }
        }
    }

    private fun respond(device: BluetoothDevice, requestId: Int, status: Int, value: ByteArray?, offset: Int = 0) {
        runCatching { gatt?.sendResponse(device, requestId, status, offset, value) }.onFailure { Log.w(TAG, "Mac service answer: $it") }
    }

    // ---------------------------------------------------------------- links and notifications

    fun connectionChanged(device: BluetoothDevice, up: Boolean) = main.post {
        if (up) { if (core.isMac(device.address)) connected.add(device.address) }
        else { connected.remove(device.address); subscribed.remove(device.address); inFlight.remove(device.address); again.remove(device.address) }
        if (core.isMac(device.address)) changed()
        kick()
    }

    /** Bluetooth is going off: every link and the advertising set go with it. */
    fun radioOff() = main.post {
        gatt = null; subscribed.clear(); connected.clear(); inFlight.clear(); again.clear()
        advSet = null; advStarting = false
    }

    /** The host's state changed: every subscribed Mac hears it (one notification in flight each; the latest wins). */
    fun statusChanged() = main.post { subscribed.toList().forEach(::notifyStatus) }

    fun notificationSent(device: BluetoothDevice) = main.post {
        inFlight.remove(device.address)
        if (again.remove(device.address)) notifyStatus(device.address)
    }

    @Suppress("DEPRECATION")
    private fun notifyStatus(address: String) {
        val g = gatt ?: return
        if (address in inFlight) { again.add(address); return }
        val device = bt.adapter?.getRemoteDevice(address) ?: return
        val value = status().toByteArray()
        val sent = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 33) g.notifyCharacteristicChanged(device, statusChar, false, value) == android.bluetooth.BluetoothStatusCodes.SUCCESS
            else { statusChar.value = value; g.notifyCharacteristicChanged(device, statusChar, false) }
        }.getOrDefault(false)
        if (sent) inFlight.add(address) else Log.w(TAG, "Mac $address: status not sent")
    }

    // ---------------------------------------------------------------- pairing window and the list

    fun setPairing(on: Boolean) = main.post {
        if (on) core.openWindow() else core.closeWindow()
        Log.i(TAG, "Mac pairing window ${if (on) "open for ${MacCore.WINDOW_MS / 1000} s" else "closed"}")
        refreshAdvertising()
        changed()
        if (on) main.postDelayed({ if (!core.windowOpen()) { refreshAdvertising(); changed() } }, MacCore.WINDOW_MS + 500)
    }

    fun remove(address: String) = main.post {
        if (!core.remove(address)) return@post
        save()
        Log.i(TAG, "Mac removed: $address")
        connected.remove(address); subscribed.remove(address)
        bt.adapter?.getRemoteDevice(address)?.let { d -> runCatching { gatt?.cancelConnection(d) } }
        unbond(address)
        refreshAdvertising()
        changed()
    }

    private fun save() = prefs.edit().putStringSet("macs", core.stored()).apply()

    // ---------------------------------------------------------------- advertising

    // A set of its own, apart from the client's: a central connecting stops a connectable legacy set, and the client's
    // comes back only when its iPhone disconnects. Advertising only while a Mac is listed or the window is open.
    private var advSet: AdvertisingSet? = null
    private var advStarting = false
    @Volatile private var advRefused = false

    /** Two sets at once: the controller says it can, and has not refused ours. */
    private fun supported(): Boolean = !advRefused && bt.adapter?.isMultipleAdvertisementSupported == true

    private fun wanted(): Boolean = gatt != null && (core.macs.isNotEmpty() || core.windowOpen())

    private fun adData(): AdvertiseData = BleLink.advertised(if (core.windowOpen()) PAIR else SERVICE)

    private val advCallback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
            advStarting = false
            if (status == ADVERTISE_SUCCESS) { advSet = set; Log.i(TAG, "Mac advertising"); kick(); return }
            Log.w(TAG, "Mac advertising failed $status")
            if (status == ADVERTISE_FAILED_TOO_MANY_ADVERTISERS || status == ADVERTISE_FAILED_FEATURE_UNSUPPORTED) { advRefused = true; changed() }
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

    private fun refreshAdvertising() {
        advSet?.let { set -> runCatching { set.setAdvertisingData(adData()) } }
        kick()
    }

    fun stop() = main.post {
        runCatching { bt.adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(advCallback) }
        advSet = null; advStarting = false
    }
}
