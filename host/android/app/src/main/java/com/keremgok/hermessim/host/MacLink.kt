// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.keremgok.hermessim.protocol.v1.MacAccess
import com.keremgok.hermessim.protocol.v1.MacCommand
import com.keremgok.hermessim.protocol.v1.MacSecret
import com.keremgok.hermessim.protocol.v1.MacStatus
import com.keremgok.hermessim.protocol.v1.MacText
import com.keremgok.hermessim.protocol.v1.PairedMac
import com.keremgok.hermessim.protocol.v1.SmsReceived
import java.util.UUID

/**
 * The Mac service (protocol/README.md "Mac"): a Mac reads the hotspot's state and passphrase and switches it, and reads
 * the recent texts where the client allowed it. It lives on BleLink's GATT server, which hands it every request for
 * this service, and runs its own advertising set (MacAdvertiser), so a Mac finds the host while the client is
 * connected and the client's set is off. Main thread throughout.
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
        val TEXTS: UUID = UUID.fromString("0e9c0016-0971-48af-ba0b-2c5c254faec0")
        private const val INSUFFICIENT_AUTHORIZATION = 0x08     // ATT error; BluetoothGatt names it only from Android 14
        private const val INVALID_OFFSET = 0x07
    }

    private val main = Handler(Looper.getMainLooper())
    private val bt = ctx.getSystemService(BluetoothManager::class.java)
    private val prefs = ctx.getSharedPreferences("mac", Context.MODE_PRIVATE)
    private val core = MacCore(SystemClock::elapsedRealtime).apply {
        load(prefs.getStringSet("macs", emptySet()).orEmpty())
        loadTexts(prefs.getStringSet("texts", emptySet()).orEmpty())
    }
    private val shelf = MacTextShelf(System::currentTimeMillis)
    private var gatt: BluetoothGattServer? = null
    private val connected = HashSet<String>()
    // The texts notification carries no value: the Mac reads them, a long read past the link's MTU.
    private val notifier = MacNotifier(bt) { address, uuid -> if (uuid == TEXTS) ByteArray(0) else statusFor(address).toByteArray() }
    private val advertiser = MacAdvertiser(main, bt,
        wanted = { gatt != null && (core.macs.isNotEmpty() || core.windowOpen()) }, pairing = core::windowOpen, refused = { changed() })

    /** A Mac was just put on the list (BleLink: it may have come in through the client's set, which it switched off). */
    var admitted: (() -> Unit)? = null

    fun isMac(address: String): Boolean = core.isMac(address)
    fun windowOpen(): Boolean = core.windowOpen()

    /** For HostStatus.mac. */
    fun access(): MacAccess = MacAccess.newBuilder().setSupported(advertiser.supported()).setPairing(core.windowOpen()).setTexts(true)
        .addAllMacs(core.macs.map {
            PairedMac.newBuilder().setName(it.name).setAddress(it.address).setConnected(it.address in connected)
                .setTexts(core.texts(it.address)).build()
        })
        .build()

    /** A new GATT service object for each GATT server (Bluetooth restarted: the old one went with it). */
    fun service(server: BluetoothGattServer): BluetoothGattService {
        gatt = server
        val (statusChar, textsChar) = notifier.characteristics(server, listOf(STATUS, TEXTS))
        return BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(statusChar)
            addCharacteristic(BluetoothGattCharacteristic(COMMAND, BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED))
            addCharacteristic(BluetoothGattCharacteristic(SECRET, BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED))
            addCharacteristic(textsChar)
        }
    }

    /** What [address] is shown: the host's state, plus whether this Mac may read the texts. */
    private fun statusFor(address: String): MacStatus = with(status().toBuilder()) { texts = core.texts(address); build() }

    // ---------------------------------------------------------------- requests (binder threads: answered on main)

    fun onRead(device: BluetoothDevice, requestId: Int, offset: Int, uuid: UUID) = main.post {
        if (!allowed(device, uuid)) return@post respond(device, requestId, INSUFFICIENT_AUTHORIZATION, null)
        val value = when (uuid) {
            SECRET -> MacSecret.newBuilder().setPassphrase(secret()).build().toByteArray()
            TEXTS -> shelf.value().toByteArray()
            else -> statusFor(device.address).toByteArray()
        }
        if (offset > value.size) return@post respond(device, requestId, INVALID_OFFSET, null)
        respond(device, requestId, BluetoothGatt.GATT_SUCCESS, value.copyOfRange(offset, value.size), offset)
    }

    fun onWrite(device: BluetoothDevice, requestId: Int, uuid: UUID, responseNeeded: Boolean, value: ByteArray) = main.post {
        val ok = allowed(device, uuid)
        val cmd = if (ok && uuid == COMMAND) runCatching { MacCommand.parseFrom(value) }.getOrNull() else null
        val code = when { !ok -> INSUFFICIENT_AUTHORIZATION; cmd == null -> BluetoothGatt.GATT_FAILURE; else -> BluetoothGatt.GATT_SUCCESS }
        if (responseNeeded) respond(device, requestId, code, null)
        if (cmd != null) {
            Log.i(TAG, "Mac ${device.address}: hotspot ${if (cmd.on) "on" else "off"}${if (cmd.offWhenUnusedS > 0) " once unused ${cmd.offWhenUnusedS} s" else ""}")
            command(cmd)
        }
    }

    /** A CCCD write on [uuid]'s characteristic: the Mac subscribes to it or leaves. */
    fun onCccd(device: BluetoothDevice, requestId: Int, uuid: UUID, responseNeeded: Boolean, value: ByteArray) = main.post {
        val ok = allowed(device, uuid)
        if (ok) notifier.subscribe(uuid, device.address, value.isNotEmpty() && value[0].toInt() and 1 == 1)
        if (responseNeeded) respond(device, requestId, if (ok) BluetoothGatt.GATT_SUCCESS else INSUFFICIENT_AUTHORIZATION, null)
    }

    /** A listed Mac is served (the texts only where allowed); inside the window a bonded newcomer is put on the list first. */
    private fun allowed(device: BluetoothDevice, uuid: UUID): Boolean {
        if (!allowed(device)) return false
        if (uuid != TEXTS || core.texts(device.address)) return true
        Log.w(TAG, "Mac service: ${device.address} may not read the texts")
        return false
    }

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
                advertiser.refresh()
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
        else { connected.remove(device.address); notifier.drop(device.address) }
        if (core.isMac(device.address)) changed()
        advertiser.kick()
    }

    /** Bluetooth is going off: every link and the advertising set go with it. */
    fun radioOff() = main.post { gatt = null; notifier.clear(); connected.clear(); advertiser.lost() }

    /** The host's state changed: every subscribed Mac hears it (one notification in flight each; the latest wins). */
    fun statusChanged() = main.post { notifier.subscribers(STATUS).forEach { notifier.notify(it, STATUS) } }

    /** A text arrived: it goes on the shelf, and every Mac subscribed to the texts is told to read them. */
    fun textArrived(m: SmsReceived) = main.post {
        shelf.add(MacText.newBuilder().setId(m.id).setSender(m.fromE164.ifEmpty { m.fromRaw }).setBody(m.body).setAtMs(m.receivedMs).build())
        notifier.subscribers(TEXTS).filter(core::texts).forEach { notifier.notify(it, TEXTS) }
    }

    fun notificationSent(device: BluetoothDevice) = main.post { notifier.sent(device.address) }

    // ---------------------------------------------------------------- pairing window and the list

    fun setPairing(on: Boolean) = main.post {
        if (on) core.openWindow() else core.closeWindow()
        Log.i(TAG, "Mac pairing window ${if (on) "open for ${MacCore.WINDOW_MS / 1000} s" else "closed"}")
        advertiser.refresh()
        changed()
        if (on) main.postDelayed({ if (!core.windowOpen()) { advertiser.refresh(); changed() } }, MacCore.WINDOW_MS + 500)
    }

    /** SetMacTexts: the Mac learns it from its status; one no longer allowed stops hearing about texts. */
    fun setTexts(address: String, on: Boolean) = main.post {
        if (!core.setTexts(address, on)) return@post
        save()
        Log.i(TAG, "Mac $address: texts ${if (on) "on" else "off"}")
        if (!on) notifier.subscribe(TEXTS, address, false)
        changed()
    }

    fun remove(address: String) = main.post {
        if (!core.remove(address)) return@post
        save()
        Log.i(TAG, "Mac removed: $address")
        connected.remove(address); notifier.drop(address)
        bt.adapter?.getRemoteDevice(address)?.let { d -> runCatching { gatt?.cancelConnection(d) } }
        unbond(address)
        advertiser.refresh()
        changed()
    }

    /** The list and, apart from it, the Macs that may read the texts. */
    private fun save() = with(prefs.edit()) {
        putStringSet("macs", core.stored())
        putStringSet("texts", core.storedTexts())
        apply()
    }

    /** After any connection change (BleLink): the advertising set follows. */
    fun kick(): Boolean = advertiser.kick()

    fun stop() = main.post { advertiser.stop() }
}
