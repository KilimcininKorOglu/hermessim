// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.util.Log
import com.google.protobuf.ByteString
import com.keremgok.hermessim.host.wire.Channel
import com.keremgok.hermessim.host.wire.Fields
import com.keremgok.hermessim.host.wire.PairResult
import com.keremgok.hermessim.host.wire.PairingDesk
import com.keremgok.hermessim.host.wire.StateBook
import com.keremgok.hermessim.host.wire.StreamChoice
import com.keremgok.hermessim.host.wire.Wire
import com.keremgok.hermessim.host.wire.WireException
import com.keremgok.hermessim.host.wire.WireFault
import com.keremgok.hermessim.protocol.v1.MacCommand
import com.keremgok.hermessim.protocol.v1.MacStatus
import com.keremgok.hermessim.wire.Bye
import com.keremgok.hermessim.wire.ByeReason
import com.keremgok.hermessim.wire.CallPhase
import com.keremgok.hermessim.wire.CallState
import com.keremgok.hermessim.wire.Code
import com.keremgok.hermessim.wire.Event
import com.keremgok.hermessim.wire.AudioPathClosed
import com.keremgok.hermessim.wire.Hello
import com.keremgok.hermessim.wire.HostInfo
import com.keremgok.hermessim.wire.Notice
import com.keremgok.hermessim.wire.Packet
import com.keremgok.hermessim.wire.PairOffer
import com.keremgok.hermessim.wire.PairProof
import com.keremgok.hermessim.wire.PairStart
import com.keremgok.hermessim.wire.Refuse
import com.keremgok.hermessim.wire.ReplayEnd
import com.keremgok.hermessim.wire.Reply
import com.keremgok.hermessim.wire.Request
import com.keremgok.hermessim.wire.StateMark
import com.keremgok.hermessim.wire.TextIn
import com.keremgok.hermessim.wire.TextStatus
import com.keremgok.hermessim.wire.Welcome
import java.util.concurrent.ConcurrentHashMap

/** One transport to the phone and the session on it. Main thread, except [Channel.serving]. */
sealed class Link(val id: String) {
    val channel = Channel(SystemClock::elapsedRealtime) { java.security.SecureRandom().nextInt() }

    /** The install id of the Hello this link was refused for: a pairing proof covers it. */
    var claimed: ByteArray? = null

    /** The ids of the requests that still run: one that comes again meanwhile is not run again. */
    val running = HashSet<Int>()

    /** The seq of the last `State` this link was sent, and the highest text seq. */
    var stateSeq = 0
    var textSeq = 0L

    /** Sends the CREDIT that is due once the peer has been quiet for a moment. */
    val creditTick = Runnable { kick() }

    abstract val bluetooth: Boolean

    /** The channel has something to send. */
    abstract fun kick()
    abstract fun drop()

    class Ble(val device: BluetoothDevice, private val ble: BleLink) : Link(device.address) {
        override val bluetooth = true
        override fun kick() = ble.kick(device)
        override fun drop() = ble.disconnect(device)
    }

    /** The internet room: a link lasts while the host and the phone are both in it. */
    object Internet : Link("remote") {
        private const val MESSAGE_BYTES = 16_000        // the relay takes 64 KB a message, sealed and in base64
        override val bluetooth = false
        override fun kick() {
            while (true) {
                val bytes = channel.next(MESSAGE_BYTES)
                if (bytes.isEmpty()) return
                RemoteLink.send(bytes)
            }
        }
        override fun drop() {}
    }
}

/** One request of a peer and the way to answer it. The answer may come later and from any thread. */
class Ask(val link: Link, val req: Request, val session: Int) {
    fun answer(code: Code, vararg args: Long, fill: (Reply.Builder) -> Unit = {}) = Relay.reply(this, code, args, fill)
    fun ok() = answer(Code.CODE_OK)
}

/**
 * The host's side of protocol version 2 (protocol/v2/SPEC.md) on top of BleLink, and of RemoteLink in internet mode.
 * Bluetooth wins when both serve; pairing and the keys only ever travel over Bluetooth. A phone pairs with the code
 * the host shows; after that only its install id is served.
 */
object Relay {
    private const val TAG = "HermesSimHost"
    private const val TICK_MS = 60_000L
    private const val PAIRING_WINDOW_MS = 5 * 60_000L
    /** How long a phone that was told it is not paired may stay on the link to pair. */
    private const val PAIR_GRACE_MS = 60_000L
    private const val HEARTBEAT_IDLE_MS = 3 * 60_000L

    private lateinit var app: Context
    private lateinit var ble: BleLink
    private lateinit var macLink: MacLink
    private var audio: AudioBridge? = null
    private val main = Handler(Looper.getMainLooper())
    private val links = ConcurrentHashMap<String, Link.Ble>()
    private val rejected = ConcurrentHashMap.newKeySet<String>()      // links told NOT_PAIRED since they connected
    private val droppedOnce = ConcurrentHashMap.newKeySet<String>()   // links already dropped for a missing Hello
    private val desk = PairingDesk { n -> ByteArray(n).also(java.security.SecureRandom()::nextBytes) }
    @Volatile private var shownCode: String? = null
    @Volatile private var pairingUntil = 0L                           // 0: open until a phone pairs
    private val pairExec = Bg.single("pairing")
    private val dirty = HashSet<Int>()
    private var lastHotspotOn = false
    private var lastHotspotRadio = false
    private var relayStarted = false

    internal val mac: MacLink get() = macLink

    private fun prefs() = app.getSharedPreferences("relay", Context.MODE_PRIVATE)

    private fun event(fill: Event.Builder.() -> Unit): Packet {
        val event = Event.newBuilder().apply(fill)
        return Packet.newBuilder().setEvent(event).build()
    }

    // ------------------------------------------------------------- start

    /** Once per process: a second start (the service recreated in a live process) would build a second GATT server,
     *  advertising set and audio bridge. */
    fun start(ctx: Context) {
        if (relayStarted) return
        relayStarted = true
        app = ctx.applicationContext
        CallRegistry.init(app)
        VoiceGate.init(app)
        Api.refreshSupportTable(app)          // newest phone tiers from hermessim.keremgok.tr (bundled copy as fallback)
        startLinks()
        Push.start(app)
        Health.init(app)
        DataLimit.init(app)
        watchParts()
        watchPairingRequests()
        CallRuleStore.init(app)
        Sims.protectNewSims(app)
        Sims.volteOn(app)
        Sims.log(app)
        if (pinned() == null) openPairing(0)
        main.postDelayed(tick, TICK_MS)
        scheduleHeartbeat()
        main.postDelayed({ guardSmsRole(); guardDialerRole() }, 20_000)   // after the daemon is up (service.sh claims SMS 90 s after boot too)
        main.post { pushMissed() }
        touch(*HostState.ALL.toIntArray())
        Log.i(TAG, "relay up; pinned client=${pinned()?.take(8) ?: "none"}")
    }

    private fun startLinks() {
        macLink = MacLink(app, ::macStatus, { Hotspot.info(app).passphrase }, ::macCommand,
            changed = { touch(StateBook.PEERS) },
            unbond = { address -> pairExec.execute { RootBt.unbond(app, address) } },
            control = { address, value -> MacControlRunner.run(app, main, address, value) })
        ble = BleLink(app, ::onBytes, ::onLink, { device, max -> links[device.address]?.channel?.next(max) ?: ByteArray(0) },
            ::pairable, macLink)
        ble.onServiceReady = { askForHello() }
        ble.start()
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
        audio = AudioBridge(adapter, ble).also { it.start() }
        RemoteLink.start(app, { bytes -> main.post { receive(Link.Internet, bytes) } }) { present, _ -> main.post { roomChanged(present) } }
    }

    /** The parts whose changes the peers hear: each names the state groups it moves. */
    private fun watchParts() {
        val hotspot = intArrayOf(StateBook.HOTSPOT, StateBook.HOTSPOT_CLIENTS, StateBook.HOTSPOT_LIMITS, StateBook.WIFI, StateBook.NETWORK)
        val lines = intArrayOf(StateBook.LINES, StateBook.SIGNAL, StateBook.NETWORK)
        MobileData.watch(app) { touch(StateBook.NETWORK, delayMs = 800) }
        Updater.start(app) { touch(StateBook.UPDATE, delayMs = 500) }
        HotspotPause.init(app, ::bleNear) { touch(StateBook.HOTSPOT, StateBook.HOTSPOT_LIMITS) }
        Box.start(app) { hotspotMoved(); touch(StateBook.POWER, *lines) }
        watchBattery()
        DataUsage.init(app) { touch(StateBook.HOTSPOT_USAGE, StateBook.HOTSPOT) }
        HotspotGuard.init(app) { touch(StateBook.HOTSPOT_CLIENTS) }
        Hotspot.watch(app) { hotspotMoved(); touch(*hotspot) }
        HostWifi.start(app, near = ::bleNear, inCall = CallRegistry::live, paired = { pinned() != null },
            hotspotOn = { Hotspot.isOnOrStarting(app) }, changed = { touch(StateBook.WIFI, StateBook.NETWORK) })
        Sims.watch(app, changed = { Sims.protectNewSims(app); Sims.volteOn(app); Sims.log(app); touch(*lines) },
            radioChanged = { touch(*lines) })
        Sims.attemptsKnown = { touch(StateBook.LINES) }
    }

    /** Battery level and charging reach the phone (app and widget) right away, not with the next other change. */
    private fun watchBattery() {
        var last = -1 to false
        app.registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val charging = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING
                if ((level to charging) != last) { last = level to charging; touch(StateBook.POWER) }
                // A held limit stops charging with the charger still in: the charge state needs "plugged", not "charging"
                Box.batteryChanged(level, i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
            }
        }, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    /**
     * Hotspot on or off: the call pause, box mode and the host Wi-Fi rule follow it. Box mode and the Wi-Fi rule see a
     * hotspot paused for a call as on: no network-mode or Wi-Fi change in the middle of the call.
     */
    private fun hotspotMoved() {
        val radio = Hotspot.isOn(app)
        if (radio != lastHotspotRadio) { lastHotspotRadio = radio; HotspotPause.hotspotChanged(radio) }
        val on = radio || HotspotPause.paused
        if (on == lastHotspotOn) return
        lastHotspotOn = on
        Box.hotspotChanged(on)
        HostWifi.evaluate("hotspot ${if (on) "on" else "off"}")
    }

    /**
     * This process may have been started by the call or the text itself (the host app had died): they reached the relay
     * before [start], when nothing could be pushed, and a phone that is away never heard of them (audit, 1 Nov 2026).
     */
    private fun pushMissed() {
        val log = Events.log(app)
        if (!bleNear()) CallRegistry.snapshot().filter { it.incoming && it.phase == CallPhase.CALL_PHASE_RINGING }
            .forEach { Push.call(log.callCursor, it) }
        val recent = System.currentTimeMillis() - 120_000
        log.textsAfter(0).forEach { (seq, bytes) ->
            val event = runCatching { Event.parseFrom(bytes) }.getOrNull()
            if (event != null && event.hasTextIn() && event.textIn.atMs > recent) Push.text(seq, bytes)
        }
    }

    // ------------------------------------------------------------- what other parts ask

    /** For the setup screen: paired client model (null = not paired), connected now, pairing code shown. */
    fun pairedClient(): String? = pinned()?.let { prefs().getString("client_model", null) ?: "your phone" }

    /** For the setup screen: Android's last answer to our advertising ([BleLink.advStatus]; null: none yet). */
    fun advertisingStatus(): Int? = if (::ble.isInitialized) ble.advStatus else null

    /** The paired phone is here on Bluetooth (it said Hello on a subscribed link). */
    private fun bleNear(): Boolean = ::ble.isInitialized && ble.subscribers().any { links[it.address]?.channel?.serving == true }

    fun clientConnected(): Boolean = bleNear() || (RemoteLink.peerPresent && Link.Internet.channel.serving)

    /** [start] ran in this process (Retire can run from a host whose service never came up). */
    fun started(): Boolean = ::app.isInitialized

    /** The internet link went up or down: the phone's Host tab shows it. */
    fun remoteChanged() { if (started()) touch(StateBook.REMOTE) }

    /** A call-audio profile appeared (built after a daemon restart): the phone can offer the call test. */
    fun voiceChanged() { if (started()) touch(StateBook.VOICE, StateBook.HOTSPOT_LIMITS) }

    // The setup screen can ask before the service has started (fresh install: crashed on the Redmi, 18 Oct).
    private fun pinned(): String? = if (!::app.isInitialized) null else prefs().getString("pinned_install_id", null)

    // ------------------------------------------------------------- pairing

    /** The code a phone needs to pair now, or null while the host takes no new phone. */
    fun pairingCode(): String? = shownCode

    fun pairingOpen(): Boolean = shownCode != null

    /** Seconds until the code goes; 0 when it stays until a phone pairs, or when none is shown. */
    fun pairingSecondsLeft(): Int = if (pairingUntil == 0L) 0 else secondsLeft(pairingUntil, SystemClock.elapsedRealtime())

    /** A phone may pair now: hosts say so in their advertisement, and a phone's "Add host" sheet lists only those. */
    fun pairable(): Boolean = pairingOpen()

    /**
     * Shows a new pairing code for [ms], or with 0 until a phone pairs (a host without a phone always shows one). The
     * host's own screen comes up with it: the code is nowhere else.
     */
    fun openPairing(ms: Long = PAIRING_WINDOW_MS) = Bg.onMain {
        desk.open()
        pairingUntil = if (ms > 0) SystemClock.elapsedRealtime() + ms else 0
        Log.i(TAG, "pairing code shown${if (ms > 0) " for ${ms / 1000} s" else ""}")
        pairingChanged()
        if (ms > 0) {
            main.postDelayed({ closePairingWhenDue() }, ms + 500)
            runCatching { app.startActivity(Intent(app, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("pairing", true)) }
                .onFailure { Log.w(TAG, "pairing screen: $it") }
        }
    }

    private fun closePairingWhenDue() {
        if (pairingUntil == 0L || SystemClock.elapsedRealtime() < pairingUntil) return
        if (pinned() == null) { pairingUntil = 0; return }        // nobody paired: the code stays
        desk.close()
        pairingUntil = 0
        Log.i(TAG, "pairing code gone")
        pairingChanged()
    }

    private fun pairingChanged() {
        shownCode = desk.code
        if (::ble.isInitialized) ble.refreshAdvertising()
    }

    /**
     * Bluetooth pairing requests are confirmed as root while the host shows a code (headless hosts can't tap "Pair").
     * Also the paired phone pairing again after its system dropped the keys (accessory removed, network settings
     * reset): with no screen, nobody could tap "Pair" and the phone hung at "Connecting" (19 Oct). The pairing code
     * and the pinned install id still decide who is served; other requests are left to Android.
     */
    private fun watchPairingRequests() {
        app.registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)?.let(::pairingRequest)
            }
        }, android.content.IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST).apply { priority = 1000 })
    }

    private fun pairingRequest(dev: BluetoothDevice) {
        val address = dev.address
        val known = pinned() != null && address == prefs().getString("client_mac", null)
        val forMac = macLink.windowOpen()       // a Mac pairs while the phone holds its window open
        if (!pairable() && !known && !forMac) return
        if (known && !pairable()) Log.i(TAG, "pairing request from the paired phone (its keys were reset): accepting")
        if (forMac) Log.i(TAG, "pairing request inside the Mac window: accepting")
        pairExec.execute { RootBt.confirm(app, address) }
    }

    private fun pairStart(link: Link, m: PairStart) {
        if (!link.bluetooth) return refuse(link, Code.CODE_NOT_ALLOWED)
        val (hostKey, salt) = desk.offer(m.publicKey.toByteArray()) ?: return refuse(link, Code.CODE_PAIRING_CLOSED)
        sendNow(link, Packet.newBuilder().setPairOffer(PairOffer.newBuilder().setPublicKey(ByteString.copyFrom(hostKey))
            .setSalt(ByteString.copyFrom(salt)).setTriesLeft(desk.triesLeft)).build())
    }

    private fun pairProof(link: Link, m: PairProof) {
        val claimed = link.claimed
        if (!link.bluetooth || claimed == null) return refuse(link, Code.CODE_NOT_PAIRED)
        when (desk.check(m.proof.toByteArray(), claimed)) {
            PairResult.PAIRED -> paired(link, claimed)
            PairResult.CLOSED -> refuse(link, Code.CODE_PAIRING_CLOSED)
            PairResult.WRONG -> {
                Log.w(TAG, "pairing: wrong proof from ${link.id}, ${desk.triesLeft} tries left")
                pairingChanged()                                  // the third wrong proof made a new code
                sendNow(link, Packet.newBuilder().setRefuse(Refuse.newBuilder().setCode(Code.CODE_PAIRING_WRONG)
                    .setTriesLeft(desk.triesLeft)).build())
            }
        }
    }

    /** The proof was right: this install id is the phone from now on. A phone before it loses its keys. */
    private fun paired(link: Link, installId: ByteArray) {
        val id = installId.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        if (pinned() != null && pinned() != id) forgetKeys()
        prefs().edit().putString("pinned_install_id", id).apply()
        pairingUntil = 0
        rejected.remove(link.id)
        pairingChanged()
        Log.i(TAG, "PAIRED with install_id=${id.take(8)}")
    }

    // ------------------------------------------------------------- host -> phone

    fun callEvent(e: CallState) = Bg.onMain {
        if (!started()) return@onMain
        val live = CallRegistry.live()
        // Fast BLE link while any call is ringing/dialing/active (alert latency + audio), relaxed when idle.
        runCatching { ble.setCallPriority(live) }
        Log.i(TAG, "call ${e.call} ${e.phase} incoming=${e.incoming} line=${if (e.hasLine()) e.line else -1} end=${e.end}")
        if (e.phase == CallPhase.CALL_PHASE_ENDED && !live) {
            RemoteCall.close("call ended")
            main.post { HostWifi.evaluate("call ended") }
        }
        val seq = Events.log(app).nextCall()
        broadcast(event { setSeq(seq); call = e })
        // No phone on Bluetooth: the app may be closed, and its system only starts it for a push. One in the room gets
        // the event above as well; it rings the same call once.
        if (e.incoming && e.phase == CallPhase.CALL_PHASE_RINGING && !bleNear()) Push.call(seq, e)
    }

    /** A text came in ([event]: its stored `Event` bytes): to the phone now, as a push if it does not store it, and to the Macs. */
    fun textIn(seq: Long, text: TextIn, event: ByteArray) = Bg.onMain {
        if (!started()) return@onMain
        textEvent(event)
        Push.text(seq, event)
        macLink.textArrived(seq, text)
    }

    /** A stored text event to the phone served now. It stays stored until an EventAck names it. */
    fun textEvent(event: ByteArray) = Bg.onMain {
        if (!started()) return@onMain
        val seq = runCatching { Event.parseFrom(event).seq }.getOrDefault(0)
        targets().forEach { link -> link.textSeq = maxOf(link.textSeq, seq); sendBytes(link, Fields.bytes(Packet.EVENT_FIELD_NUMBER, event)) }
    }

    /** What became of a text the host sent. A status no phone was there to hear is kept (SmsSender.untold). */
    fun textStatus(status: TextStatus) = Bg.onMain {
        if (!started()) return@onMain
        if (broadcast(event { textStatus = status })) SmsSender.told(status.text)
    }

    /** The host closed the internet audio path by itself (RemoteCall). */
    fun audioPathClosed() = Bg.onMain { broadcast(event { audioPathClosed = AudioPathClosed.getDefaultInstance() }) }

    /** A health notice: to the phone served now and, with none on Bluetooth, as a push too. */
    private fun notice(n: Notice) {
        Log.i(TAG, "notice ${n.kind} value=${n.value} line=${if (n.hasLine()) n.line else -1}")
        broadcast(event { notice = n })
        if (!bleNear()) Push.notice(n)
    }

    /** Bluetooth links when any serves, else the internet room. */
    private fun targets(): List<Link> {
        if (!::ble.isInitialized) return emptyList()
        val viaBle = ble.subscribers().mapNotNull { links[it.address] }.filter { it.channel.serving }
        if (viaBle.isNotEmpty()) return viaBle
        return if (RemoteLink.peerPresent && Link.Internet.channel.serving) listOf(Link.Internet) else emptyList()
    }

    /** True when a phone was there to hear it. */
    private fun broadcast(packet: Packet): Boolean {
        val to = targets()
        if (to.isEmpty()) Log.i(TAG, "no phone connected; ${packet.kindCase} not sent")
        to.forEach { send(it, packet) }
        return to.isNotEmpty()
    }

    private fun send(link: Link, packet: Packet, answers: Request.OpCase? = null) {
        val bytes = packet.toByteArray()
        sendBytes(link, bytes, StreamChoice.of(packet, bytes.size, answers))
    }

    /** A serialized `Packet` to a link that is served; it leaves when the link's credit allows. */
    private fun sendBytes(link: Link, packet: ByteArray, stream: Int = if (packet.size > Wire.BULK_FROM) Wire.BULK else Wire.NORMAL): Int {
        if (!link.channel.serving) return stream
        link.channel.send(packet, stream)
        link.kick()
        return stream
    }

    /** To a link before its Hello was accepted: the answers of the session's start and of pairing. */
    private fun sendNow(link: Link, packet: Packet) = with(link) {
        channel.send(packet.toByteArray(), Wire.URGENT)
        kick()
    }

    /** A request's reply (Ask.answer, any thread). The last reply of a request is kept for a peer that asks again. */
    internal fun reply(ask: Ask, code: Code, args: LongArray, fill: (Reply.Builder) -> Unit) = Bg.onMain {
        val channel = ask.link.channel
        if (channel.sessionId != ask.session) return@onMain            // the session ended: its ids mean nothing now
        val reply = Reply.newBuilder().setId(ask.req.id).setCode(code).addAllArgs(args.toList()).also(fill).build()
        val packet = Packet.newBuilder().setReply(reply).build()
        val bytes = packet.toByteArray()
        if (!reply.hasLog() || reply.log.last) ask.link.running.remove(ask.req.id)
        if (!reply.hasLog()) channel.replies.keep(ask.req.id, bytes)
        if (code != Code.CODE_OK) Log.w(TAG, "${ask.req.opCase} from ${ask.link.id}: $code ${args.toList()}")
        sendBytes(ask.link, bytes, StreamChoice.of(packet, bytes.size, ask.req.opCase))
    }

    /** The kept reply of a request that came again. */
    internal fun resend(link: Link, reply: ByteArray, op: Request.OpCase) {
        sendBytes(link, reply, StreamChoice.of(Packet.getDefaultInstance(), reply.size, op))
    }

    // ------------------------------------------------------------- state

    /** The parts behind [groups] changed: they are read again shortly and what differs goes to the phone. Any thread. */
    internal fun touch(vararg groups: Int, delayMs: Long = 250) = Bg.onMain {
        dirty.addAll(groups.toList())
        main.removeCallbacks(flush)
        main.postDelayed(flush, delayMs)
    }

    /** As [touch], after the phone system had time to settle. */
    internal fun touchLater(afterMs: Long, vararg groups: Int) { main.postDelayed({ touch(*groups) }, afterMs) }

    // Runs on the main thread after every change: a failure here must cost one update, not the process.
    private val flush = Runnable {
        Bg.guard("state") {
            val groups = dirty.toSet()
            dirty.clear()
            val (reading, signalWait) = HostState.refresh(app, groups, macLink::peers)
            signalWait?.let { touchLater(it + 50, StateBook.SIGNAL) }
            sendChanges()
            if (StateBook.POWER in groups || StateBook.LINES in groups)
                Health.notices(HealthView(reading.battery, reading.charging, reading.sims, Box.chargeState, Box.chargeLimit)).forEach(::notice)
            if (StateBook.HOTSPOT_USAGE in groups) dataLimit(reading)
        }
        if (::macLink.isInitialized) macLink.statusChanged()            // the Macs hear the same changes
    }

    /** The `State` with what changed since the last one, to the links served now; [except] gets a base instead. */
    private fun sendChanges(except: Link? = null) {
        val state = HostState.book.next() ?: return
        val to = targets().filter { it !== except }
        to.forEach { link -> link.stateSeq = HostState.book.seq; sendBytes(link, Fields.bytes(Packet.STATE_FIELD_NUMBER, state)) }
        if (to.isNotEmpty()) Updater.informed()
    }

    /** GetState: the named groups read again and sent whole, with the book's current seq. */
    internal fun sendState(link: Link, groups: List<Int>) {
        val wanted = if (groups.isEmpty()) HostState.ALL else groups.toSet()
        val (reading, _) = HostState.refresh(app, wanted - StateBook.SIGNAL, macLink::peers)
        if (StateBook.SIGNAL in wanted) {
            HostState.signal.asked(SystemClock.elapsedRealtime())
            HostState.signal(reading, force = true)
        }
        sendChanges()                         // what the reading changed goes to every link first, with its own seq
        link.stateSeq = HostState.book.seq
        sendBytes(link, Fields.bytes(Packet.STATE_FIELD_NUMBER, HostState.book.whole(groups)))
    }

    /** This month's hotspot usage against its limit (DataLimit): a notice, and at the limit the hotspot off once. */
    private fun dataLimit(reading: HostState.Reading) {
        val out = DataLimit.check(reading.usage.month, reading.hotspot.on)
        val kind = out.notice ?: return
        notice(Notice.newBuilder().setKind(kind).setValue(out.percent.toLong()).setAtMs(System.currentTimeMillis()).build())
        if (!out.cutOff) return
        Log.i(TAG, "monthly data limit used up: hotspot off")
        switchHotspot(false, 0) { code -> if (code != Code.CODE_OK) Log.w(TAG, "data limit: hotspot not turned off: $code") }
    }

    // ------------------------------------------------------------- phone -> host

    private fun onLink(d: BluetoothDevice, up: Boolean) = main.post {
        rejected.remove(d.address)
        val link = links.getOrPut(d.address) { Link.Ble(d, ble) }
        if (up) {
            link.channel.newLink()
            Log.i(TAG, "client ${d.address} subscribed; waiting for Hello")
            return@post
        }
        val wasServed = link.channel.serving
        link.channel.close()
        link.claimed = null
        Log.i(TAG, "client ${d.address} gone")
        HostWifi.evaluate("Bluetooth gone")
        HotspotPause.transportChanged("Bluetooth gone")
        scheduleHeartbeat()
        // The internet room serves now. It heard nothing while Bluetooth served: a call's end or a text would be lost
        // until the next one (audit, 1 Nov 2026).
        if (wasServed && targets().singleOrNull() === Link.Internet) {
            Log.i(TAG, "Bluetooth gone, the internet room serves: sending it what it lacks")
            replay(Link.Internet, StateMark.newBuilder().setEpoch(HostState.epoch).setSeq(Link.Internet.stateSeq).build(), Link.Internet.textSeq)
        }
    }

    /** The phone joined the room or left it: a link lasts while both are in. The phone says Hello on a new link. */
    private fun roomChanged(present: Boolean) {
        if (present) { Link.Internet.channel.newLink(); return }
        Link.Internet.channel.close()
        // A room blip mid-call doesn't touch the call's audio (TURN carries it); it ends with the call.
        if (!CallRegistry.live()) RemoteCall.close("the phone left the room")
    }

    /** BLE values arrive on binder threads: handled on main, and never let one take the process down. */
    private fun onBytes(dev: BluetoothDevice, bytes: ByteArray) {
        main.post { receive(links.getOrPut(dev.address) { Link.Ble(dev, ble) }, bytes) }
    }

    private fun receive(link: Link, bytes: ByteArray) {
        val arrived = try { link.channel.feed(bytes) } catch (e: WireException) {
            Log.w(TAG, "link ${link.id}: ${e.message}, reset")
            link.channel.reset(e.fault)
            link.kick()
            return
        }
        arrived.forEach { message ->
            runCatching { onPacket(link, message.packet) }.onFailure { Log.e(TAG, "message from ${link.id} failed: $it") }
        }
        link.kick()
        main.removeCallbacks(link.creditTick)
        main.postDelayed(link.creditTick, 220)                 // the CREDIT that says the messages arrived
    }

    private fun onPacket(link: Link, bytes: ByteArray) {
        val p = runCatching { Packet.parseFrom(bytes) }.getOrElse {
            Log.w(TAG, "link ${link.id}: not a packet, reset")
            link.channel.reset(WireFault.BAD_PACKET)
            return
        }
        when {
            p.hasHello() -> hello(link, p.hello)
            p.hasPairStart() -> pairStart(link, p.pairStart)
            p.hasPairProof() -> pairProof(link, p.pairProof)
            // A host whose app restarted while the link stayed up: ask for a Hello (the phone would otherwise wait)
            !link.channel.serving -> link.channel.reset(WireFault.NO_SESSION)
            p.hasRequest() -> Requests.handle(app, main, link, p.request)
            p.hasEventAck() -> Events.log(app).ack(p.eventAck.textsUpTo)
            else -> Log.w(TAG, "unexpected ${p.kindCase} from ${link.id}")
        }
    }

    private fun hello(link: Link, h: Hello) {
        val refusal = HelloRules.refusal(h, pinned())
        if (refusal != null) {
            if (refusal == Code.CODE_NOT_PAIRED) notPaired(link, h)
            return refuse(link, refusal)
        }
        val resumed = link.channel.open(if (h.hasResume()) h.resume.sessionId else null)
        if (!resumed) link.running.clear()
        link.claimed = null
        val welcome = Welcome.newBuilder().setVersion(Wire.VERSION).setFeatures(HelloRules.FEATURES)
            .setSessionId(link.channel.sessionId).setResumed(resumed).setEpoch(HostState.epoch).setHost(hostInfo())
            .setAudioPsm(if (link.bluetooth) ble.audioPsm else 0)
        if (resumed) welcome.received = link.channel.received.toInt()
        send(link, Packet.newBuilder().setWelcome(welcome).build())
        served(link, h, resumed)
        replay(link, if (h.hasStateMark()) h.stateMark else null, h.textCursor)
    }

    private fun hostInfo(): HostInfo = HostInfo.newBuilder().setAppVersion(BuildConfigInfo.version(app))
        .setDeviceModel("${Build.MANUFACTURER} ${Build.MODEL}").setFirmware(Build.FINGERPRINT)
        .setMaxBlockedNumbers(CallRulesCore.MAX_NUMBERS).setMaxMacPeers(MacCore.MAX_MACS)
        .setMaxBlockedClients(HotspotGuardCore.MAX_BLOCKED).build()

    /** What the host does for a phone it serves from now on, apart from the protocol. */
    private fun served(link: Link, h: Hello, resumed: Boolean) {
        val via = if (link.bluetooth) "Bluetooth" else "internet"
        Log.i(TAG, "Hello from ${h.deviceModel} app ${h.appVersion} via $via (session ${if (resumed) "resumed" else "new"})")
        val edit = prefs().edit().putString("client_model", h.deviceModel)
        if (link is Link.Ble) {
            noHelloSince = 0
            // What the link agreed on (RadioLook): well after the Hello, when the link serves and nothing is being set up
            main.postDelayed({ if (link.channel.serving) RadioLook.linkLook() }, 20_000)
            HostWifi.evaluate("Bluetooth up")
            HotspotPause.transportChanged("Bluetooth back")
            // Remembered so the same phone can pair again on its own after its system dropped the keys (see the receiver)
            edit.putString("client_mac", link.id)
            scheduleHeartbeat()
        }
        edit.apply()
        Diag.milestone("paired", org.json.JSONObject().put("client", h.deviceModel.substringBefore(' ')))
    }

    /**
     * All a phone lacks after its Hello: the state since [mark], every live call, the texts above [textCursor] and the
     * statuses nobody heard; then ReplayEnd, on the stream that carries the slowest of them, so it arrives last.
     */
    private fun replay(link: Link, mark: StateMark?, textCursor: Long) {
        val (reading, _) = HostState.refresh(app, HostState.ALL - StateBook.SIGNAL, macLink::peers)
        HostState.signal(reading, force = true)
        sendChanges(except = link)
        link.stateSeq = HostState.book.seq
        var lane =sendBytes(link, Fields.bytes(Packet.STATE_FIELD_NUMBER, HostState.book.since(mark?.epoch, mark?.seq ?: 0)))
        Updater.informed()
        val log = Events.log(app)
        val calls = CallRegistry.snapshot()
        calls.forEach { live -> send(link, event { setSeq(log.callCursor); call = live }) }
        log.textsAfter(textCursor).forEach { (_, event) -> lane = maxOf(lane, sendBytes(link, Fields.bytes(Packet.EVENT_FIELD_NUMBER, event))) }
        link.textSeq = log.textCursor
        SmsSender.untold().forEach { status ->
            send(link, event { textStatus = status })
            SmsSender.told(status.text)
        }
        val end = ReplayEnd.newBuilder().setTextCursor(log.textCursor).setCallCursor(log.callCursor)
            .addAllLiveCalls(calls.filter { it.phase != CallPhase.CALL_PHASE_ENDED }.map { it.call }).build()
        sendBytes(link, Packet.newBuilder().setReplayEnd(end).build().toByteArray(), lane)
    }

    private fun refuse(link: Link, code: Code) {
        Log.w(TAG, "refusing ${link.id}: $code")
        val refuse = Refuse.newBuilder().setCode(code)
        if (code == Code.CODE_VERSION) refuse.setMinVersion(Wire.VERSION).setMaxVersion(Wire.VERSION)
        link.channel.close()
        sendNow(link, Packet.newBuilder().setRefuse(refuse).build())
    }

    /**
     * A phone that is not the paired one. Its install id is kept for a pairing proof, and the link stays up for
     * pairing; a phone that does not pair is dropped, at once while the host shows no code: connectable advertising
     * stops while a phone is connected, and the paired phone must still find the host.
     */
    private fun notPaired(link: Link, h: Hello) {
        link.claimed = h.installId.toByteArray()
        if (link !is Link.Ble) return
        rejected.add(link.id)
        main.postDelayed({ if (!link.channel.serving && link.id in rejected) link.drop() }, if (pairingOpen()) PAIR_GRACE_MS else 500)
    }

    // ------------------------------------------------------------- the end of a pairing

    private fun forgetKeys() {
        // Both keys go: a room key kept here kept the host in a room nobody holds a key for, and pushing calls and
        // texts there (Push falls back to it) for a forgotten phone (audit, 1 Nov 2026)
        Push.setKey(ByteArray(0))
        RemoteLink.configure(false, null)
    }

    private fun forgetPin(): String? {
        val address = prefs().getString("client_mac", null)
        prefs().edit().remove("pinned_install_id").remove("client_model").remove("client_mac").apply()
        forgetKeys()
        return address
    }

    private fun closeSessions() {
        links.values.forEach { it.channel.close() }
        Link.Internet.channel.close()
    }

    /**
     * ForgetHost was answered: the host forgets the phone too (pin, keys, Bluetooth bond), so a new pairing starts
     * clean. A stale bond made the next pairing fail ("encryption is insufficient", 19 Oct).
     */
    internal fun forgetPhone(link: Link) {
        val address = forgetPin() ?: (link as? Link.Ble)?.id
        Log.i(TAG, "UNPAIRED: the phone forgot this host")
        Diag.event("unpaired", org.json.JSONObject().put("what", "client_forgot"), withLog = false)
        main.postDelayed({
            closeSessions()
            link.drop()
            if (address != null) pairExec.execute { RootBt.unbond(app, address) }
            openPairing(0)                                          // ready for the next phone
            touch(StateBook.REMOTE)
        }, 500)                                                     // time for the reply to reach the phone
    }

    /**
     * This phone stops being a host (Retire): the phone gets a Bye, which tells it the host is gone for good, then the
     * host forgets it as after ForgetHost and stops advertising. [done] runs off main.
     */
    fun retire(done: () -> Unit) = main.post {
        broadcast(Packet.newBuilder().setBye(Bye.newBuilder().setReason(ByeReason.BYE_REASON_RETIRED)).build())
        main.postDelayed({
            targets().forEach { it.drop() }
            val address = forgetPin()
            closeSessions()
            desk.close()
            shownCode = null
            if (::ble.isInitialized) runCatching { ble.stop() }
            Log.i(TAG, "RETIRED: the phone was told, pairing forgotten")
            pairExec.execute { if (address != null) RootBt.unbond(app, address); done() }
        }, 1500)                                               // time for the Bye to reach the phone
    }

    /**
     * Retire failed after [retire]: Bluetooth back as a host's (GATT service and advertising), so the phone isn't left
     * deaf until someone restarts it. The pairing stays forgotten (the phone was told): it pairs again like a new host.
     */
    fun unretire() = main.post {
        if (!::ble.isInitialized) return@post
        runCatching { ble.start() }.onFailure { Log.w(TAG, "unretire: $it") }
        openPairing(0)
        Log.i(TAG, "retire rolled back: advertising as an unpaired host")
    }

    // ------------------------------------------------------------- roles

    /** Role requests that didn't stick wait longer each time (1, 2, 4 ... up to 60 minutes): a phone that refuses a
     *  role must not get a root command every minute for ever. Holding the role again resets it. */
    private val roleRetryAt = HashMap<String, Long>()
    private val roleRetryGap = HashMap<String, Long>()
    @Synchronized private fun roleRequestDue(role: String, held: Boolean): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (held) { roleRetryAt.remove(role); roleRetryGap.remove(role); return false }
        if (now < (roleRetryAt[role] ?: 0L)) return false
        val gap = ((roleRetryGap[role] ?: 30_000L) * 2).coerceAtMost(3_600_000L)
        roleRetryGap[role] = gap
        roleRetryAt[role] = now + gap
        return true
    }

    /** Self-repair's SMS role request (Onboarding.selfHeal, off main) waits on the same backoff as the guard: two
     *  schedules sent a phone that refuses the role a root command every 10 minutes (audit, 20 Oct 2026). */
    fun smsRoleRequestDue(): Boolean = roleRequestDue("sms", false)

    /**
     * The host is the phone app from the moment it runs, not only in box mode (20 Oct 2026: during setup the
     * phone's own call screen came up for the call test, as if the host weren't in charge). Its non-UI InCallService
     * then sees every call and no call screen lights the host up. The app that had the role is kept for "Stop using
     * this phone as a host" (Retire), which hands it back.
     */
    private fun guardDialerRole() {
        if (Retire.running) return
        val tm = app.getSystemService(android.telecom.TelecomManager::class.java) ?: return
        val holder = tm.defaultDialerPackage
        if (!roleRequestDue("dialer", holder == app.packageName)) return
        Retire.rememberDialer(app, holder)
        Log.w(TAG, "not the phone app (${holder ?: "none"}): taking the role")
        Daemon.role("android.app.role.DIALER", app.packageName)
    }

    /**
     * The host must stay the default SMS app, or incoming SMS go to another app and never reach the phone (Redmi,
     * 19 Oct: Google Messages had taken the role back after the SIM went in). Checked at start and every tick; taken
     * back as root when lost.
     */
    private fun guardSmsRole() {
        if (Retire.running) return                              // giving the phone back: the role goes to its old app
        val rm = app.getSystemService(android.app.role.RoleManager::class.java) ?: return
        if (!roleRequestDue("sms", rm.isRoleHeld(android.app.role.RoleManager.ROLE_SMS))) return
        Log.w(TAG, "not the default SMS app any more: taking the role back")
        Daemon.role(android.app.role.RoleManager.ROLE_SMS, app.packageName)
    }

    // ------------------------------------------------------------- a link without a session

    /**
     * This process may be new while the phone's link stayed up (app update or crash): the phone doesn't know the host
     * forgot its session, so the host says so (RESET, "no session") rather than wait for it to speak (seen 16 Oct).
     * Sent once the GATT service is up and again with every tick until the phone says Hello.
     */
    private fun askForHello() {
        // Only the paired phone, and not after it was told it isn't paired: the phone's other LE links (a watch, a band)
        // aren't ours to ask, and asking a rejected phone looped Hello / NOT_PAIRED every minute (29 Oct 2026, 23 times)
        val ours = prefs().getString("client_mac", null)
        if (pinned() == null || ours == null) return
        val silent = ble.connectedDevices().filter { it.address == ours && links[it.address]?.channel?.serving != true && it.address !in rejected }
        watchSilent(silent.firstOrNull())
        silent.forEach { askForHello(it) }
    }

    @Volatile private var noHelloSince = 0L

    /**
     * The paired phone's Bluetooth link is up and the phone doesn't say Hello, tick after tick: the phone shows
     * "connecting" meanwhile and nothing works over Bluetooth. A phone sat like that for three minutes after its host
     * updated itself, and the host's log held nothing of those minutes (11 Nov 2026). So the host says so in its log,
     * with what it knows of the link. [d]: the silent link, null when none.
     */
    private fun watchSilent(d: BluetoothDevice?) {
        val now = SystemClock.elapsedRealtime()
        val bond = d?.let { runCatching { it.bondState }.getOrDefault(-1) }
        if (d == null || pairingOpen() || bond == BluetoothDevice.BOND_BONDING) { noHelloSince = 0; return }
        if (noHelloSince == 0L) { noHelloSince = now; return }
        val s = (now - noHelloSince) / 1000
        if (s < 90) return
        Log.w(TAG, "link ${d.address} has been up for $s s without a Hello (bond $bond, " +
            "subscribed ${ble.subscribers().any { it.address == d.address }}, dropped once ${d.address in droppedOnce})")
    }

    private fun serving(d: BluetoothDevice): Boolean = links[d.address]?.channel?.serving == true

    private fun askForHello(d: BluetoothDevice) {
        Log.i(TAG, "link ${d.address} is up without a session: asking for a Hello")
        val link = links.getOrPut(d.address) { Link.Ble(d, ble) }
        link.channel.reset(WireFault.NO_SESSION)
        link.kick()
        // After an app restart the phone's subscription belongs to the old process: our request may never reach it
        // (18 Oct, after a self-update: 3 min of silence). No Hello in 5 s -> drop the link; it reconnects at once.
        if (!droppedOnce.add(d.address)) return                      // once per link after a restart, not every minute
        // By alarm, not by the main thread's timer: that one stops while the host sleeps, and a host that restarts in
        // a drawer goes back to sleep at once (11 Nov 2026).
        WakeAlarm.set(app, "hello-drop", 5000) { dropSilentLink(d) }
    }

    /** Never during pairing: a new phone may be in its system's pairing dialog. */
    private fun dropSilentLink(d: BluetoothDevice) {
        val up = ble.connectedDevices().any { it.address == d.address }
        if (serving(d) || pairingOpen() || d.bondState == BluetoothDevice.BOND_BONDING || !up) return
        Log.i(TAG, "no Hello from ${d.address} after 5 s: dropping the link so it reconnects fresh")
        ble.disconnect(d)
        // The phone owns the link (it's the central): cancelConnection rarely drops it, and a locked iPhone's app then
        // hears nothing for minutes (19 Oct, after a self-update: 170 s+, reproduced). Switching our Bluetooth off and
        // on is a real disconnect, which its system always delivers: Hello within 3 s. Never in a call.
        WakeAlarm.set(app, "hello-reset", 3000) {
            if (!serving(d) && ble.connectedDevices().any { it.address == d.address } && !CallRegistry.live()) {
                Log.i(TAG, "link ${d.address} still up without a Hello: resetting Bluetooth so the phone reconnects")
                // Detached: if this app dies in those 2 s, Bluetooth must still come back on
                Root.background("setsid sh -c 'svc bluetooth disable; sleep 2; svc bluetooth enable' </dev/null >/dev/null 2>&1 &")
            }
        }
    }

    // ------------------------------------------------------------- the minute's tick

    /**
     * An idle link carries one CREDIT a minute from each side, and that is all that keeps it alive (SPEC.md "CREDIT").
     * It is the phone's wake-up call too. The main thread's timer stops while the host is in deep sleep, so an alarm
     * allowed while idle runs the tick then: every minute while a phone is on Bluetooth, else every few minutes.
     */
    @Volatile private var lastTickAt = 0L
    private var ticks = 0
    private const val FULL_READ_TICKS = 10

    private fun heartbeat() {
        if (SystemClock.elapsedRealtime() - lastTickAt >= TICK_MS - 2_000L) {
            main.removeCallbacks(tick)
            tick.run()
        }
        scheduleHeartbeat()
    }

    private fun scheduleHeartbeat() = WakeAlarm.set(app, "heartbeat", if (bleNear()) TICK_MS else HEARTBEAT_IDLE_MS, ::heartbeat)

    private val tick = object : Runnable {
        override fun run() {
            lastTickAt = SystemClock.elapsedRealtime()
            Bg.guard("tick") {
                askForHello()
                links.values.filter { it.channel.serving }.forEach(::keepOrAsk)
                if (Link.Internet.channel.serving) Link.Internet.kick()
                HostWifi.evaluate("tick")
                guardSmsRole()
                guardDialerRole()
                Onboarding.selfHeal(app)
                Diag.retry()                                     // reports queued while offline
                if (Box.blocksPowerKey) Daemon.powerKey(true)    // the daemon's dead-man: an unconfirmed block lapses in 15 min
                // A change no part reported: everything is read again now and then, and only what differs is sent
                if (++ticks % FULL_READ_TICKS == 0) touch(*HostState.ALL.toIntArray())
            }
            main.postDelayed(this, TICK_MS)
        }
    }

    /** A served link gets its idle CREDIT; one that has been silent too long is asked for a Hello instead. */
    private fun keepOrAsk(link: Link.Ble) {
        if (!link.channel.silent()) return link.kick()
        Log.w(TAG, "link ${link.id}: no frame for ${Wire.DEAD_MS / 1000} s, asking for a Hello")
        link.channel.reset(WireFault.NO_SESSION)
        link.kick()
    }

    // ------------------------------------------------------------- the hotspot switch and the Macs

    private val hotspotGroups = intArrayOf(StateBook.HOTSPOT, StateBook.HOTSPOT_CLIENTS)

    /**
     * SetHotspot from the phone or a Mac. on = false with [offWhenUnusedS] > 0: the host keeps the "off once unused"
     * timer. [done] hears CODE_OK or why it didn't happen.
     */
    internal fun switchHotspot(on: Boolean, offWhenUnusedS: Int, done: (Code) -> Unit) {
        if (!on && offWhenUnusedS > 0) { Hotspot.offWhenUnused(app, offWhenUnusedS); return done(Code.CODE_OK) }
        Hotspot.cancelOffWhenUnused()
        // Starting the hotspot can take the host off Wi-Fi, which may carry this internet call (18 Oct).
        if (on && !bleNear() && CallRegistry.live()) return done(Code.CODE_IN_CALL)
        HotspotPause.userSwitched(on)        // the user's switch wins over a call pause
        HostState.hotspotFailed = false
        Hotspot.set(app, on) { ok, detail ->
            if (!ok) { if (on) HostState.hotspotFailed = true; Log.w(TAG, "hotspot switch: $detail") }
            done(if (ok) Code.CODE_OK else Code.CODE_FAILED)
            // The AP state change itself reports the hotspot (Hotspot.watch); this catches a settle without one.
            touchLater(1500, *hotspotGroups)
        }
        touch(*hotspotGroups, delayMs = 0)      // "starting" / off right away, not after the AP settles
        // A start Android accepted can still die before the AP is up (no state change then): end "starting"
        // as soon as its 15 s window closes.
        if (on) touchLater(15_500, *hotspotGroups)
    }

    /**
     * What a Mac is shown (MacStatus): the hotspot as the phone sees it, plus the host's battery and signal, and the
     * lines and devices MacLink keeps for a Mac with control.
     */
    @SuppressLint("MissingPermission")
    private fun macStatus(): MacStatus {
        val hs = Hotspot.info(app)
        val bm = app.getSystemService(BatteryManager::class.java)
        val name = runCatching { (app.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter?.name }.getOrNull()
        return MacStatus.newBuilder()
            .setState(MacCore.state(hs.on, hs.starting, HostState.hotspotFailed))
            .setSsid(hs.ssid).setFrequencyMhz(hs.frequencyMhz).setGateway(hs.gateway)
            .setBatteryPercent(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)).setCharging(bm.isCharging)
            .setSignalLevel(app.getSystemService(TelephonyManager::class.java).signalStrength?.level ?: 0)
            .setHostName(name?.takeIf { it.isNotBlank() } ?: Build.MODEL)
            .setControllable(hs.available && HostWifi.clientOverride == null)
            .setPausedForCall(HotspotPause.paused && !hs.on).setOffWhenUnused(true)
            .setLimitReached(DataLimit.reached(DataUsage.usage().month))
            .setDevices(hs.clients.size).setHostActions(true)
            .addAllLines(MacControlCore.lines(Sims.status(app)))
            .addAllClients(MacControlCore.devices(hs.clients)).addAllBlocked(MacControlCore.devices(HotspotGuard.blocked()))
            .build()
    }

    /** A Mac's command (MacLink, main thread): the same switch as the phone's SetHotspot. */
    private fun macCommand(cmd: MacCommand) {
        if (HostWifi.clientOverride != null) { Log.i(TAG, "rig running: the Mac's hotspot switch ignored"); return }
        switchHotspot(cmd.on, cmd.offWhenUnusedS) { code -> if (code != Code.CODE_OK) Log.w(TAG, "Mac hotspot switch: $code") }
    }
}

object BuildConfigInfo {
    fun version(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
}
