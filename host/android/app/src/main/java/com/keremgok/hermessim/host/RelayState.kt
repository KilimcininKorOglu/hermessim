// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.annotation.SuppressLint
import android.content.Context
import android.os.BatteryManager
import android.os.SystemClock
import com.google.protobuf.ByteString
import com.keremgok.hermessim.host.wire.StateBook
import com.keremgok.hermessim.wire.Line
import com.keremgok.hermessim.wire.Lines
import com.keremgok.hermessim.wire.Network
import com.keremgok.hermessim.wire.Peers
import com.keremgok.hermessim.wire.Power
import com.keremgok.hermessim.wire.Remote
import com.keremgok.hermessim.wire.Signal
import com.keremgok.hermessim.wire.Uplink

/** The groups of `State` as their messages, built from what the host's parts report. Pure. */
object StateCore {
    /** The stable id of a SIM card (16 hex digits) as the 8 bytes of `Line.card`; empty when it is not one. */
    fun card(hex: String): ByteString =
        if (hex.length != 16 || hex.any { Character.digit(it, 16) < 0 }) ByteString.EMPTY
        else ByteString.copyFrom(ByteArray(8) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() })

    fun line(s: SimLine): Line {
        val b = Line.newBuilder().setLine(s.slot).setCarrier(s.carrier).setNumber(s.number).setEnabled(s.enabled)
            .setEmbedded(s.embedded).setLock(s.lock).setRoamingAllowed(s.dataRoaming).setRoamingNow(s.roaming)
            .setVolte(s.volte).setCeiling(s.ceiling).addAllCeilings(s.ceilings).setCountry(s.country).setCard(card(s.card))
        if (s.asksForCode && s.attemptsLeft >= 0) b.triesLeft = s.attemptsLeft
        return b.build()
    }

    fun lines(sims: List<SimLine>): Lines = Lines.newBuilder().addAllLines(sims.map(::line)).build()

    fun bars(sims: List<SimLine>): List<Int> = sims.map { it.signal.coerceIn(0, 4) }

    fun signal(bars: List<Int>): Signal = Signal.newBuilder().addAllBars(bars).build()

    /** [wifiName]: the Wi-Fi network the host is on. The mobile uplink is named after the line that carries data. */
    fun network(mobileData: Boolean, uplink: Uplink, sims: List<SimLine>, wifiName: String): Network {
        val data = sims.firstOrNull { it.defaultData }
        val name = when (uplink) {
            Uplink.UPLINK_WIFI -> wifiName
            Uplink.UPLINK_MOBILE -> data?.carrier.orEmpty()
            else -> ""
        }
        val b = Network.newBuilder().setMobileData(mobileData).setUplink(uplink).setUplinkName(name)
        if (data != null) b.dataLine = data.slot
        return b.build()
    }
}

/**
 * The host's state book and the parts it reads. [refresh] reads the named groups again and takes them into the
 * book; the relay then sends what changed. Main thread.
 */
object HostState {
    val ALL: Set<Int> = (StateBook.POWER..StateBook.HOTSPOT_USAGE).toSet()

    /** Changes each time the host app starts: a peer's copy of the state from another run is not built on. */
    val epoch: Int = generateSequence { java.security.SecureRandom().nextInt() }.first { it != 0 }
    val book = StateBook(epoch)
    val signal = SignalGate()

    /** The last start of the hotspot failed and nothing has switched it since. */
    @Volatile var hotspotFailed = false

    /** What one refresh reads at most once, however many groups need it. */
    class Reading(val ctx: Context, val peers: () -> Peers) {
        val sims: List<SimLine> by lazy { Sims.status(ctx) }
        val hotspot: HotspotInfo by lazy { Hotspot.info(ctx) }
        val battery: Int by lazy { ctx.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }
        val charging: Boolean by lazy { ctx.getSystemService(BatteryManager::class.java).isCharging }
        val usage by lazy { DataUsage.usage() }
    }

    private val builders: Map<Int, (Reading) -> ByteArray> = mapOf(
        StateBook.POWER to { r -> power(r).toByteArray() },
        StateBook.NETWORK to { r -> StateCore.network(MobileData.isOn(r.ctx), uplink(r.ctx), r.sims, HostWifi.connectedSsid).toByteArray() },
        StateBook.LINES to { r -> StateCore.lines(r.sims).toByteArray() },
        StateBook.HOTSPOT to { r -> hotspot(r).toByteArray() },
        StateBook.HOTSPOT_CLIENTS to { r -> HotspotCore.clients(r.hotspot.clients, HotspotGuard.blocked()).toByteArray() },
        StateBook.WIFI to { _ -> HostWifi.status().toByteArray() },
        StateBook.UPDATE to { _ -> Updater.status().toByteArray() },
        StateBook.RULES to { _ -> CallRuleStore.rules.toByteArray() },
        StateBook.VOICE to { _ -> VoiceGate.status().toByteArray() },
        StateBook.REMOTE to { _ -> remote().toByteArray() },
        StateBook.PEERS to { r -> r.peers().toByteArray() },
        StateBook.HOTSPOT_LIMITS to { r -> Hotspot.limits(r.ctx).toByteArray() },
        StateBook.HOTSPOT_USAGE to { r -> r.usage.toByteArray() },
    )

    /**
     * Reads [groups] again. Returns the reading, and how long until signal bars that were held back may go (null:
     * none are held).
     */
    fun refresh(ctx: Context, groups: Set<Int>, peers: () -> Peers): Pair<Reading, Long?> {
        val reading = Reading(ctx, peers)
        for (group in groups) builders[group]?.let { build -> book.put(group, build(reading)) }
        val wait = if (StateBook.SIGNAL in groups) signal(reading, force = false) else null
        return reading to wait
    }

    /** The bars go into the book when the gate lets them, or always with [force] (a peer's base state). */
    fun signal(reading: Reading, force: Boolean): Long? {
        val bars = StateCore.bars(reading.sims)
        val now = SystemClock.elapsedRealtime()
        if (force || signal.pass(bars, now)) { book.put(StateBook.SIGNAL, StateCore.signal(bars).toByteArray()); return null }
        return signal.waitMs(bars, now)
    }

    private fun power(r: Reading): Power = Power.newBuilder()
        .setBattery(r.battery).setCharging(r.charging).setBoxMode(Box.on).setChargeLimit(Box.chargeLimit)
        .setChargeState(Box.chargeState).setBoxPaused(Box.pausedNow)
        .setBoxPausedUntilMs(if (Box.pausedNow) Box.suspendedUntil else 0)
        .setChargeLimitOffered(Box.chargeSupported).setCallScreenHidden(Box.callScreenHidden()).build()

    private fun hotspot(r: Reading) = HotspotCore.message(r.hotspot,
        HotspotCore.phase(r.hotspot, paused = HotspotPause.paused, failed = hotspotFailed),
        monthLimit = DataLimit.limitBytes, limitReached = DataLimit.reached(r.usage.month),
        pauseInCalls = HotspotPause.setting && Hotspot.callPauseOffered(r.ctx)).toBuilder().setWatch(HotspotWatch.setting).build()

    private fun remote(): Remote {
        val b = Remote.newBuilder().setOn(RemoteLink.enabled).setHostOnline(RemoteLink.online)
        Push.keyId()?.let { b.pushKeyMark = ByteString.copyFrom(it) }
        return b.build()
    }

    @SuppressLint("MissingPermission")
    private fun uplink(ctx: Context): Uplink {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = runCatching { cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } }.getOrNull()
        return when {
            caps == null || !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) -> Uplink.UPLINK_NONE
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> Uplink.UPLINK_WIFI
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> Uplink.UPLINK_MOBILE
            else -> Uplink.UPLINK_OTHER
        }
    }
}
