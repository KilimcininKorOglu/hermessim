// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.AdvertiseData
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * BLE peripheral side of the control channel (protocol/v2/SPEC.md "Bluetooth LE service"). The values of each
 * direction are one byte stream of frames: what a peer writes is handed on as it comes ([onBytes]), and what goes to
 * a peer is pulled from its channel ([next]) one notification at a time. Lessons from the M1 spike: restart
 * advertising after a real disconnect; verify DISCONNECTED against the stack (a short duplicate connection produced a
 * false one); persist CCCD state for bonded clients; one notification in flight per device (Android drops the rest).
 */
@SuppressLint("MissingPermission") // runtime permissions are granted by the Magisk module (headless host)
class BleLink(
    private val ctx: Context,
    /** The bytes a peer wrote, in order. Binder threads. */
    private val onBytes: (BluetoothDevice, ByteArray) -> Unit,
    private val onLink: (BluetoothDevice, Boolean) -> Unit,
    /** The next bytes for a peer, at most the given count; empty when it has nothing to send. Main thread. */
    private val next: (BluetoothDevice, Int) -> ByteArray,
    private val pairable: () -> Boolean = { false },
    private val mac: MacPeers? = null,
) {
    companion object {
        /** The link's connection interval as last reported to our client role (ms; 0 = not yet): call audio check. */
        @Volatile var lastIntervalMs = 0.0

        /** Advertised instead of [SERVICE] while the host shows a pairing code; a phone's Add host sheet looks for it only. */
        val PAIR_SERVICE: UUID = UUID.fromString("7d5f00f0-3c1a-4b6e-9e42-5a1c0de51a00")
        /** Advertised by the Macs' set while the host takes a new Mac; a Mac's Add phone search looks for it only. */
        val MAC_PAIR_SERVICE: UUID = UUID.fromString("7d5f00f1-3c1a-4b6e-9e42-5a1c0de51a00")
        private const val TAG = "HermesSimHost"
        val SERVICE: UUID = UUID.fromString("7d5f0001-3c1a-4b6e-9e42-5a1c0de51a00")
        val DOWN: UUID = UUID.fromString("7d5f0002-3c1a-4b6e-9e42-5a1c0de51a00")
        val UP: UUID = UUID.fromString("7d5f0003-3c1a-4b6e-9e42-5a1c0de51a00")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        /** ATT caps an attribute value at 512 bytes, whatever the MTU. */
        private const val MAX_VALUE = 512

        /** Advertising data naming one service: the client's set and the Mac's carry nothing else. */
        fun advertised(uuid: UUID): AdvertiseData = AdvertiseData.Builder().addServiceUuid(ParcelUuid(uuid)).build()
    }

    private val main = Handler(Looper.getMainLooper())
    private val bt = ctx.getSystemService(BluetoothManager::class.java)
    private val prefs = ctx.getSharedPreferences("ble", Context.MODE_PRIVATE)
    private var gatt: BluetoothGattServer? = null
    private lateinit var tx: BluetoothGattCharacteristic
    private val mtu = ConcurrentHashMap<String, Int>()
    init { mac?.admitted = { restartAdvertising() } }
    /** Main thread: the value in the air per link, and the one a failed notify left to send again. */
    private val inFlight = HashSet<String>()
    private val held = HashMap<String, ByteArray>()
    private val notifyFails = HashMap<String, Int>()
    /** The L2CAP channel of call audio (AudioBridge): the Welcome carries it. */
    @Volatile var audioPsm = 0

    /**
     * A host without Bluetooth is unreachable near its iPhone, so it doesn't stay off: switched off by someone,
     * airplane mode or an OEM power saver, it comes back on after 10 s (as root; at most every 30 s). Our own reset
     * turns it back on within 2 s, so this only acts when nobody else will (audit, 19 Oct).
     */
    private var lastRadioKick = 0L
    private fun radioBack() { main.removeCallbacks(radioCheck); main.postDelayed(radioCheck, 10_000) }
    private val radioCheck: Runnable = Runnable {
        if (bt.adapter?.isEnabled == true) return@Runnable
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRadioKick < 30_000) { radioBack(); return@Runnable }
        lastRadioKick = now
        Log.i(TAG, "Bluetooth is off: switching it back on")
        Root.background("svc bluetooth enable")
        radioBack()                                    // checks again; the 30 s limit paces it
    }

    /**
     * Android 12+: without its Bluetooth permissions every GATT call throws. The module grants them at boot, but
     * HermesSIM's data cleared in Settings resets them: the service crashed at start, Android gave up after two
     * crashes, and the module's watchdog saw the process alive (its other parts), so the host stayed dead until
     * someone opened its screen (J7 Prime, 5 Nov 2026). Now the GATT service waits for them; self-repair grants them.
     */
    fun allowed(): Boolean = android.os.Build.VERSION.SDK_INT < 31 ||
        Onboarding.missing(ctx, listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)).isEmpty()
    private var waitingPermission = false
    private val permissionCheck: Runnable = Runnable { if (gatt == null) start() }

    /** GATT service + advertising; with the radio off (left off, or mid-toggle) STATE_ON calls this again. */
    fun start() {
        if (!radioWatch) {
            radioWatch = true
            ctx.registerReceiver(radioReceiver, android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED))
            ctx.registerReceiver(nameReceiver, android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED))
        }
        if (!allowed()) {
            if (!waitingPermission) { waitingPermission = true; Log.w(TAG, "no Bluetooth permission: GATT service waits for self-repair") }
            Onboarding.selfHeal(ctx)                       // grants it as root now (then on its own back-off)
            main.removeCallbacks(permissionCheck); main.postDelayed(permissionCheck, 5_000)
            return
        }
        if (waitingPermission) { waitingPermission = false; Log.i(TAG, "Bluetooth permission back: starting the GATT service") }
        if (bt.adapter?.isEnabled != true) { Log.w(TAG, "Bluetooth is off: GATT service waits for it"); radioBack(); return }
        val service = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        tx = BluetoothGattCharacteristic(DOWN, BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED).apply {
            addDescriptor(BluetoothGattDescriptor(CCCD,
                BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED))
        }
        service.addCharacteristic(tx)
        service.addCharacteristic(BluetoothGattCharacteristic(UP, BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED))
        val server = bt.openGattServer(ctx, callback) ?: run { Log.w(TAG, "GATT server unavailable (radio turning off?)"); return }
        server.addService(service)
        gatt = server
        advertise()
    }

    /**
     * Bluetooth switched off and on (our own link reset after a restart, the user, the stack): Android drops our GATT
     * service and advertising with the radio, and nothing brought them back, so the host stayed deaf until the app
     * restarted (19 Oct). Rebuild on STATE_ON; per-link state is stale after STATE_OFF.
     */
    @Volatile private var radioWatch = false
    private val radioReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: android.content.Intent) {
            when (i.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1)) {
                android.bluetooth.BluetoothAdapter.STATE_TURNING_OFF -> main.post {
                    Log.i(TAG, "Bluetooth turning off: GATT server down")
                    runCatching { gatt?.close() }; gatt = null
                    advSet = null; registered = false; starting = false; replacing = false; enableTries = 0; failures = 0; advDisabled = false   // the radio drops it
                    advStatus = null
                    resetPace()
                    mac?.radioOff()
                    held.clear(); inFlight.clear(); mtu.clear(); notifyFails.clear()
                }
                android.bluetooth.BluetoothAdapter.STATE_OFF -> radioBack()
                android.bluetooth.BluetoothAdapter.STATE_ON -> main.post {
                    if (gatt != null) return@post
                    Log.i(TAG, "Bluetooth on again: rebuilding the GATT service and advertising")
                    start()
                }
            }
        }
    }

    /**
     * The phone's Bluetooth name changed (the host screen's Bluetooth name, or Android's settings): put the new name in
     * the running set's scan response, so phones adding the host see it without a restart. A name that did not fit
     * before may fit now, so it is tried again.
     */
    private val nameReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: android.content.Intent) {
            main.post {
                withName = true
                val set = advSet ?: return@post
                runCatching { set.setScanResponseData(scanResponse()) }
                    .onSuccess { Log.i(TAG, "advertising: Bluetooth name changed (${nameBytes()} bytes)") }
                    .onFailure { Log.w(TAG, "advertising: new Bluetooth name not taken: $it") }
            }
        }
    }

    fun stop() {
        runCatching { bt.adapter.bluetoothLeAdvertiser.stopAdvertisingSet(advCallback) }
        advSet = null; registered = false; starting = false; replacing = false
        resetPace()
        mac?.stop()
        gatt?.close()
        gatt = null
    }

    /** Devices the stack reports connected that have subscribed to [DOWN] (now or in an earlier connection). */
    fun subscribers(): List<BluetoothDevice> {
        if (gatt == null) return emptyList()           // no service (radio off, or no permission: the stack would throw)
        return bt.getConnectedDevices(BluetoothProfile.GATT).filter { it.address in subscribed() }
    }

    /** The peer's channel has bytes to send: they go out as soon as no notification to it is in the air. Any thread. */
    fun kick(device: BluetoothDevice) = Bg.onMain { pump(device) }

    fun disconnect(device: BluetoothDevice) { gatt?.cancelConnection(device) }

    /** Links that are already up (they outlive an app restart: the Bluetooth stack keeps the connection). */
    @SuppressLint("MissingPermission")
    fun connectedDevices(): List<BluetoothDevice> = runCatching { bt.getConnectedDevices(BluetoothProfile.GATT_SERVER) }.getOrDefault(emptyList())

    // ---- Connection interval ----
    // As the peripheral, Android can only ask for a link's interval through a GATT *client* on the same ACL link
    // (BluetoothGatt.requestConnectionPriority), opened when a peer subscribes. HIGH asks for ~11.25-15 ms during a
    // call (iOS grants within its own rules, 15 ms steps): a short interval cuts audio transit time and burstiness.
    // Otherwise LOW_POWER (~100-125 ms) keeps the peer's radio asleep most of the time (LinkPriority).
    private val clients = ConcurrentHashMap<String, BluetoothGatt>()
    @Volatile private var wantHigh = false
    /** Disconnects ignored because the stack still listed the link: looked at again when our client role goes. */
    private val ignoredDown: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun setCallPriority(high: Boolean) {
        if (wantHigh == high) return
        wantHigh = high
        for (d in subscribers().filter { mac?.isMac(it.address) != true }) {     // a Mac's link has no call audio
            applyPriority(d)
            // A call starts: its numbers say what the link runs on
            if (high) runCatching { gatt?.readPhy(d) }
        }
    }

    /**
     * Ask the link for the host's radio mode ([LinkRadio.mask]). A change comes to onPhyUpdate; not every stack passes
     * that on, and no change means no word at all, so the mode is read once the exchange has had its time.
     */
    private fun askPhy(d: BluetoothDevice, why: String) {
        val m = LinkRadio.mask
        LinkRadio.asked(m, why)
        runCatching { gatt?.setPreferredPhy(d, m, m, BluetoothDevice.PHY_OPTION_NO_PREFERRED) }
            .onFailure { Log.w(TAG, "BLE radio mode: asking failed: $it") }
        main.postDelayed({ runCatching { gatt?.readPhy(d) } }, 2_000)
    }

    private fun priorityFor(d: BluetoothDevice): Int = LinkPriority.of(wantHigh, mac?.isMac(d.address) == true)

    private fun applyPriority(d: BluetoothDevice) {
        val existing = clients[d.address]
        if (existing != null) {
            val prio = priorityFor(d)
            Log.i(TAG, "connection priority ${LinkPriority.name(prio)} -> ${existing.requestConnectionPriority(prio)}")
            return
        }
        // First use: attach a client role to the existing link; request priority once connected.
        // null while the Bluetooth service restarts (a call ringing then crashed the host: audit, 19 Oct)
        val g = runCatching { d.connectGatt(ctx, false, object : android.bluetooth.BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    val p = priorityFor(d)
                    Log.i(TAG, "client role up; connection priority ${LinkPriority.name(p)} -> ${g.requestConnectionPriority(p)}")
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    clients.remove(d.address); g.close()
                    // Our own client role was what kept the link listed: the iPhone's disconnect was real. Ignored, it left
                    // the host serving a dead link, advertising off and the iPhone in the room unserved (5 times, 20-24
                    // Oct 2026, after calls)
                    if (ignoredDown.remove(d.address) && bt.getConnectedDevices(BluetoothProfile.GATT).none { it.address == d.address }) {
                        Log.i(TAG, "BLE ${d.address}: down after all (our client role was holding the link)")
                        linkDown(d)
                    }
                }
            }
            // Hidden in the SDK stubs but invoked by the framework (same signature = override at runtime).
            @Suppress("unused")
            fun onConnectionUpdated(g: BluetoothGatt, interval: Int, latency: Int, timeout: Int, status: Int) {
                Log.i(TAG, "BLE connection updated: interval ${interval * 1.25} ms, latency $latency, timeout ${timeout * 10} ms, status $status")
                if (status == BluetoothGatt.GATT_SUCCESS) lastIntervalMs = interval * 1.25
            }
        }, BluetoothDevice.TRANSPORT_LE) }.getOrNull()
        if (g != null) clients[d.address] = g else Log.w(TAG, "connection priority: no GATT client right now")
    }

    /**
     * Main thread: one value to [device] when none is in the air. The value is as long as the link takes (ATT caps it
     * at 512 bytes: a 514-byte value at MTU 517 reached iOS 2 bytes short, 18 Oct), so small frames share it.
     */
    private fun pump(device: BluetoothDevice) {
        val address = device.address
        if (address in inFlight || address !in subscribed()) return
        val value = held.remove(address) ?: next(device, minOf((mtu[address] ?: 23) - 3, MAX_VALUE))
        if (value.isEmpty()) return
        val rc = notify(device, value)
        if (rc == BluetoothStatusCodes.SUCCESS) { inFlight.add(address); notifyFails.remove(address); return }
        val n = (notifyFails[address] ?: 0) + 1
        if (n > 100) {                       // ~5 s: the link is gone; the peer resumes its session on the next one
            Log.w(TAG, "notify to $address keeps failing (rc=$rc): giving the link up")
            notifyFails.remove(address)
            gatt?.cancelConnection(device)
            return
        }
        if (n == 1 || n % 20 == 0) Log.w(TAG, "notify to $address rc=$rc, retrying (#$n)")
        notifyFails[address] = n
        held[address] = value                // the stream has no gaps: the same bytes go again
        main.postDelayed({ pump(device) }, 50)
    }

    /** notifyCharacteristicChanged(device, char, confirm, value) is API 33; Android 11-12 set the value first. */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun notify(device: BluetoothDevice, chunk: ByteArray): Int? {
        val g = gatt ?: return null
        if (android.os.Build.VERSION.SDK_INT >= 33) return g.notifyCharacteristicChanged(device, tx, false, chunk)
        tx.value = chunk
        return if (g.notifyCharacteristicChanged(device, tx, false)) BluetoothStatusCodes.SUCCESS else -1
    }

    private fun subscribed(): Set<String> = prefs.getStringSet("cccd", emptySet()) ?: emptySet()

    private fun setSubscribed(address: String, on: Boolean) {
        val s = subscribed().toMutableSet()
        if (on) s.add(address) else s.remove(address)
        prefs.edit().putStringSet("cccd", s).apply()
    }

    /** A link came or went: nothing of the one before is sent on. The peer's channel starts its own link apart ([onLink]). */
    private fun resetStream(address: String) {
        if (bt.getConnectedDevices(BluetoothProfile.GATT).none { it.address == address }) mtu.remove(address)
        main.post { held.remove(address); inFlight.remove(address); notifyFails.remove(address) }
    }

    private fun linkDown(d: BluetoothDevice) {
        if (mac?.isMac(d.address) != true) phoneDownAt = android.os.SystemClock.elapsedRealtime()
        resetStream(d.address)
        onLink(d, false)
        restartAdvertising()
    }

    /** Our GATT service is registered: notifications can reach clients from now on. */
    var onServiceReady: (() -> Unit)? = null

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            Log.i(TAG, "GATT service ${service.uuid} added status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) return
            main.post { onServiceReady?.invoke() }
            mac?.serviceReady()
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            mac?.kick()
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "BLE connected ${device.address} bond=${device.bondState}")
                ignoredDown.remove(device.address)
                resetStream(device.address)
                LinkRadio.linkUp()
                askPhy(device, "connected")
                // A Mac that came in through the client's set switched it off: the iPhone must still find the host.
                if (mac?.isMac(device.address) == true) restartAdvertising()
                return
            }
            // A false DISCONNECTED comes from a short duplicate connection while the real link stays up (M1 spike)
            val stillUp = bt.getConnectedDevices(BluetoothProfile.GATT).any { it.address == device.address }
            Log.i(TAG, "BLE disconnected ${device.address} status=$status (stack says ${if (stillUp) "STILL UP, ignored" else "down"})")
            if (stillUp) { ignoredDown.add(device.address); return }
            ignoredDown.remove(device.address)
            linkDown(device)
        }

        // Hidden in the SDK stubs but invoked by the framework: the link's actual connection parameters.
        @Suppress("unused")
        fun onConnectionUpdated(device: BluetoothDevice, interval: Int, latency: Int, timeout: Int, status: Int) {
            Log.i(TAG, "BLE link ${device.address}: interval ${interval * 1.25} ms, latency $latency, timeout ${timeout * 10} ms (status $status)")
        }

        // The link's radio mode, after a change and when read (askPhy, a call's start): a call's numbers say it
        override fun onPhyUpdate(device: BluetoothDevice, txPhy: Int, rxPhy: Int, status: Int) = LinkRadio.seen(txPhy, rxPhy, status, "changed")
        override fun onPhyRead(device: BluetoothDevice, txPhy: Int, rxPhy: Int, status: Int) = LinkRadio.seen(txPhy, rxPhy, status, "read")

        override fun onMtuChanged(device: BluetoothDevice, m: Int) {
            mtu[device.address] = m
            Log.i(TAG, "mtu ${device.address} = $m")
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, d: BluetoothGattDescriptor,
                                              prepared: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            if (d.uuid == CCCD) {
                val on = value.isNotEmpty() && value[0].toInt() and 1 == 1
                setSubscribed(device.address, on)
                Log.i(TAG, "down notify=$on for ${device.address}")
                if (on) {
                    resetStream(device.address); onLink(device, true)
                    // Every link asks for its interval at the start: LOW_POWER when idle, HIGH when a call is already up
                    applyPriority(device)
                }
            }
            if (responseNeeded) gatt?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int,
                                                 c: BluetoothGattCharacteristic) {
            gatt?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, ByteArray(0))
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, c: BluetoothGattCharacteristic,
                                                  prepared: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            if (responseNeeded) gatt?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (c.uuid == UP) onBytes(device, value)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "notification to ${device.address} status=$status")
            main.post { inFlight.remove(device.address); pump(device) }
        }
    }

    // ---------------------------------------------------------------- advertising

    // One advertising set for the host's life, enabled again after each disconnect. Android gives every new set a
    // new random address; before the iPhone bonds (no shared key to follow address changes), the host it picked in
    // Apple's accessory sheet then vanished at the first disconnect and the iPhone hung at "connecting" (19 Oct).
    //
    // A small state machine on the main thread: at most one start in flight; a set is replaced only after Android
    // confirms the old one stopped (or 2 s pass); each failure kind has its own answer, and none retries every 2 s
    // forever or leaves advertising off for good (audit, 19 Oct).
    @Volatile private var advSet: AdvertisingSet? = null
    private var registered = false               // our callback is attached to a set (started, not yet stopped)
    private var starting = false
    private var replacing = false
    private var enableTries = 0
    private var failures = 0
    private var withName = true                  // the name in the scan response, unless it doesn't fit
    private var advDisabled = false              // the controller can't advertise as we need: reported, not retried
    private val reportedAdv = HashSet<Int>()

    /** Android's last answer to our advertising: null before any (or while Bluetooth is off), ADVERTISE_SUCCESS, or the
     *  failure status. The setup screen shows this, the only real test: we run one advertising set, which every
     *  Bluetooth LE controller can do since Android 8; isMultipleAdvertisementSupported (several sets at once) said
     *  nothing about it and turned away older chips that work (1 Nov 2026). */
    @Volatile var advStatus: Int? = null
        private set

    private val advCallback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
            starting = false
            advStatus = status
            if (status == ADVERTISE_SUCCESS) { advSet = set; registered = true; failures = 0; enableTries = 0; Log.i(TAG, "advertising"); schedulePace(); return }
            failures++
            Log.w(TAG, "advertising failed $status (try $failures)")
            report(status)
            when (status) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> { withName = false; retry(0) }       // a long Bluetooth name
                ADVERTISE_FAILED_ALREADY_STARTED -> replaceSet()
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> { advDisabled = true; Log.e(TAG, "this Bluetooth can't advertise as a host") }
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> retry(60_000)                 // another app holds the only slot
                else -> retry(minOf(60_000L, 2_000L shl minOf(failures, 5)))
            }
        }
        override fun onAdvertisingEnabled(set: AdvertisingSet?, enable: Boolean, status: Int) {
            if (!enable) { pacing?.let { paceParams(set, it) }; return }
            if (status == ADVERTISE_SUCCESS) { enableTries = 0; advStatus = status; Log.i(TAG, "advertising again (same address)"); return }
            // Right after a disconnect the controller can still refuse (status 18 on the Redmi, 19 Oct): retry the same
            // set a few times (its address is what an unbonded iPhone knows), then replace it.
            enableTries++
            Log.w(TAG, "advertising again failed $status (try $enableTries)")
            if (enableTries < 5) retry(400L * enableTries) else replaceSet()
        }
        override fun onAdvertisingParametersUpdated(set: AdvertisingSet?, txPower: Int, status: Int) {
            val target = pacing ?: return
            if (status != ADVERTISE_SUCCESS) Log.w(TAG, "advertising: interval not changed (status $status)")
            finishPace(if (status == ADVERTISE_SUCCESS) target else null)
        }
        override fun onAdvertisingSetStopped(set: AdvertisingSet?) {
            if (advSet === set) advSet = null
            registered = false
            if (replacing) { replacing = false; retry(300) }
        }
    }

    private fun retry(ms: Long) { main.postDelayed({ advertise() }, ms) }

    private fun report(status: Int) {
        if (reportedAdv.add(status)) Diag.event("advertising_failed", org.json.JSONObject().put("what", "status $status")
            .put("multi_adv", bt.adapter?.isMultipleAdvertisementSupported).put("name_bytes", nameBytes()), withLog = false)
    }

    @Suppress("MissingPermission")
    private fun nameBytes(): Int = runCatching { bt.adapter?.name?.toByteArray()?.size ?: 0 }.getOrDefault(0)

    /** [PAIR_SERVICE] while [pairable] (the Add host sheet lists only those), else [SERVICE] (the paired iPhone's
     *  search). The GATT service is [SERVICE] either way. */
    private fun adData(): AdvertiseData = advertised(if (pairable()) PAIR_SERVICE else SERVICE)

    /** The phone's name in the scan response when it fits (31 bytes - 2; Android 11/12 count the whole name, and
     *  refuse the set otherwise): nameless, the host still works, the iPhone's sheet shows HermesSIM Host. */
    private fun scanResponse(): AdvertiseData =
        AdvertiseData.Builder().setIncludeDeviceName(withName && nameBytes() <= 29).build()

    /** The pairable state changed: update the running set's data (same set, same address). */
    fun refreshAdvertising() = main.post {
        val set = advSet ?: return@post
        val p = pairable()
        runCatching { set.setAdvertisingData(adData()) }.onSuccess { Log.i(TAG, "advertising: ${if (p) "ready to pair" else "paired"}") }
        if (p && slowAdv) setPace(false) else schedulePace()
    }

    /** Drop the set; a new one (new address) starts once Android confirms it stopped, or after 2 s without a word. */
    private fun replaceSet() {
        enableTries = 0
        val adv = bt.adapter?.bluetoothLeAdvertiser
        if (adv == null || !registered) { advSet = null; registered = false; retry(300); return }
        replacing = true
        if (runCatching { adv.stopAdvertisingSet(advCallback) }.isFailure) { replacing = false; advSet = null; registered = false; retry(300); return }
        main.postDelayed({ if (replacing) { replacing = false; advSet = null; registered = false; Log.w(TAG, "advertising: no stop confirmation"); advertise() } }, 2000)
    }

    private fun advertise() {
        if (gatt == null || advDisabled || starting || replacing || pacing != null) return
        val adv = bt.adapter?.bluetoothLeAdvertiser ?: run { Log.w(TAG, "no advertiser: Bluetooth is off"); return }
        advSet?.let { set ->
            if (slowAdv && !wantSlow()) { setPace(false); return }     // a phone came back: fast again (same set)
            if (runCatching { set.enableAdvertising(true, 0, 0) }.isSuccess) { schedulePace(); return }
            advSet = null
        }
        if (registered) { replaceSet(); return }        // a set we lost track of still holds our callback
        starting = true
        slowAdv = false
        runCatching { adv.startAdvertisingSet(params(false), adData(), scanResponse(), null, null, advCallback) }.onFailure { e ->
            starting = false
            Log.w(TAG, "advertising: $e")
            when {
                e.message?.contains("too big", ignoreCase = true) == true -> { withName = false; retry(0) }
                e.message?.contains("already associated", ignoreCase = true) == true -> { registered = true; replaceSet() }
                else -> { failures++; retry(minOf(60_000L, 2_000L shl minOf(failures, 5))) }
            }
        }
    }

    /** Connectable advertising stops once a central connects; enable it again after every real disconnect. */
    private fun restartAdvertising() = main.post { advertise() }

    // ---- Advertising pace (AdvertPace): the short interval while a phone may look for the host, the medium one once
    // no phone has been linked for 2 minutes. The set stays the same, so its address does too (an unbonded iPhone
    // knows the host by it).
    private var phoneDownAt = android.os.SystemClock.elapsedRealtime()   // the host's start counts as a link's end
    private var slowAdv = false                  // the running set advertises at the medium interval
    private var pacing: Boolean? = null          // a pace change in flight: the interval it goes to (true = medium)
    private val paceCheck = Runnable { if (!slowAdv && wantSlow()) setPace(true) }
    private val paceTimeout = Runnable { if (pacing != null) { Log.w(TAG, "advertising: interval change got no answer"); finishPace(null) } }

    private fun params(slow: Boolean): AdvertisingSetParameters = AdvertisingSetParameters.Builder()
        .setLegacyMode(true).setConnectable(true).setScannable(true)
        .setInterval(if (slow) AdvertisingSetParameters.INTERVAL_MEDIUM else AdvertisingSetParameters.INTERVAL_LOW)
        .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
        .build()

    @SuppressLint("MissingPermission")
    private fun phoneConnected(): Boolean = runCatching { bt.getConnectedDevices(BluetoothProfile.GATT) }
        .getOrDefault(emptyList()).any { mac?.isMac(it.address) != true }

    private fun sincePhoneDown(): Long = android.os.SystemClock.elapsedRealtime() - phoneDownAt

    private fun wantSlow(): Boolean = AdvertPace.slow(pairable(), phoneConnected(), sincePhoneDown())

    private fun schedulePace() {
        main.removeCallbacks(paceCheck)
        main.postDelayed(paceCheck, AdvertPace.waitMs(sincePhoneDown()))
    }

    private fun resetPace() {
        main.removeCallbacks(paceCheck); main.removeCallbacks(paceTimeout)
        slowAdv = false; pacing = null
    }

    /** Moves the running set to the other interval: Android changes it only while the set is off, so off, then
     *  [paceParams] when that is confirmed, then on again in [finishPace]. */
    private fun setPace(slow: Boolean) {
        val set = advSet ?: return
        if (pacing != null) return
        pacing = slow
        if (runCatching { set.enableAdvertising(false, 0, 0) }.isFailure) { finishPace(null); return }
        main.postDelayed(paceTimeout, 2_000)
    }

    private fun paceParams(set: AdvertisingSet?, slow: Boolean) {
        if (set == null || runCatching { set.setAdvertisingParameters(params(slow)) }.isFailure) finishPace(null)
    }

    /** The change ended: [slow] is the interval now in use, null when it stayed. Advertising goes on again, unless a
     *  phone connected meanwhile (its disconnect starts it). */
    private fun finishPace(slow: Boolean?) {
        main.removeCallbacks(paceTimeout)
        pacing = null
        if (slow != null) { slowAdv = slow; Log.i(TAG, "advertising every ${if (slow) 250 else 100} ms") }
        if (!phoneConnected()) runCatching { advSet?.enableAdvertising(true, 0, 0) }
        if (!slowAdv) schedulePace()
    }
}
