// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructTimeval
import android.util.Log
import com.keremgok.hermessim.wire.HotspotWatch as HotspotWatchMsg
import java.io.IOException
import java.net.Inet4Address

/** The hotspot check's settings (Hotspot.watch), kept as the message itself. Pure. */
object WatchSettings {
    val DEFAULT: HotspotWatchMsg = HotspotWatchMsg.newBuilder().setOn(true).setEveryS(300).setAddress("1.1.1.1").setMisses(3).build()
    private val NAME = Regex("^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$")

    /** True when every value of [m] is in its range (protocol/v2/SPEC.md "The hotspot check"). */
    fun valid(m: HotspotWatchMsg): Boolean =
        m.everyS in 60..3600 && m.misses in 1..10 && m.address.length in 1..253 && NAME.matches(m.address)
}

/**
 * The hotspot check's steps, free of Android (HotspotWatchCoreTest). After `misses` (Hotspot.watch) failed checks in a
 * row, or a device's report, the hotspot restarts; when the next check after that fails too, mobile data goes off
 * and on, and counting starts again. A report within [QUIET_MS] of a step is dropped: two devices on one stuck
 * hotspot restart it once. During a call a step waits ([Step.WAIT]) and runs at [afterCall].
 */
class HotspotWatchCore(private val now: () -> Long) {
    enum class Step { NONE, RESTART_HOTSPOT, TOGGLE_DATA, WAIT }

    var misses = 0; private set
    /** The last step restarted the hotspot: the next failure turns mobile data off and on. */
    var restarted = false; private set
    private var waiting = false
    private var lastStepAt: Long? = null

    /** The result of one check of the host's own. */
    fun checked(ok: Boolean, limit: Int, inCall: Boolean): Step {
        if (ok) {
            misses = 0; restarted = false; waiting = false
            return Step.NONE
        }
        misses++
        return if (restarted || waiting || misses >= limit) step(inCall) else Step.NONE
    }

    /** A device on the hotspot failed its own checks. */
    fun reported(inCall: Boolean): Step {
        val last = lastStepAt
        if (last != null && now() - last < QUIET_MS) return Step.NONE
        return step(inCall)
    }

    /** The calls are over: the step that waited for them, if any. */
    fun afterCall(): Step = if (waiting) step(inCall = false) else Step.NONE

    private fun step(inCall: Boolean): Step {
        if (inCall) {
            waiting = true
            return Step.WAIT
        }
        waiting = false
        misses = 0
        lastStepAt = now()
        restarted = !restarted
        return if (restarted) Step.RESTART_HOTSPOT else Step.TOGGLE_DATA
    }

    companion object {
        const val QUIET_MS = 120_000L
    }
}

/**
 * The hotspot check (Hotspot.watch): while the hotspot is on and a device uses it, the host pings the set address over
 * its mobile data every few minutes, and the devices on the hotspot report their own failed pings (HostAction
 * HOST_VERB_HOTSPOT_NO_INTERNET, MacControl.hotspot_no_internet). A stuck hotspot keeps its devices connected without
 * internet until it is switched off and on (seen by Kerem, 12 Nov 2026). Never in a call: a Wi-Fi change mid-call can
 * cut the call's Bluetooth audio (Hotspot.decideUnused). Main thread, except the ping.
 */
object HotspotWatch {
    private const val TAG = "HermesSimHost"
    private const val CHECK_ALARM = "hotspot-watch"
    private const val CALL_ALARM = "hotspot-watch-call"
    private const val DATA_ALARM = "hotspot-watch-data"
    private const val CALL_RECHECK_MS = 20_000L
    private const val DATA_OFF_MS = 3_000L
    private lateinit var app: Context
    private val exec = Bg.single("hotspot-watch")
    private val core = HotspotWatchCore(SystemClock::elapsedRealtime)
    private var changed: () -> Unit = {}

    private val prefs get() = app.getSharedPreferences("hotspot_watch", Context.MODE_PRIVATE)

    /** Any thread. */
    @Volatile var setting: HotspotWatchMsg = WatchSettings.DEFAULT; private set

    fun init(ctx: Context, changed: () -> Unit) {
        app = ctx.applicationContext
        this.changed = changed
        prefs.getString("setting", null)?.let { saved ->
            setting = HotspotWatchMsg.parseFrom(android.util.Base64.decode(saved, android.util.Base64.NO_WRAP))
        }
        hotspotChanged(Hotspot.isOn(app))
    }

    /** A setting WatchSettings.valid took. */
    fun setSetting(s: HotspotWatchMsg) {
        setting = s
        prefs.edit().putString("setting", android.util.Base64.encodeToString(s.toByteArray(), android.util.Base64.NO_WRAP)).apply()
        Log.i(TAG, "hotspot check ${if (s.on) "on: ${s.address} every ${s.everyS} s, ${s.misses} misses" else "off"}")
        changed()
        hotspotChanged(Hotspot.isOn(app))
    }

    /** The hotspot went on or off (Relay): the checks run while it is on. */
    fun hotspotChanged(on: Boolean) {
        if (!::app.isInitialized) return
        if (on && setting.on) WakeAlarm.set(app, CHECK_ALARM, setting.everyS * 1000L, ::check)
        else WakeAlarm.cancel(app, CHECK_ALARM)
    }

    /** A device on the hotspot failed its checks ([from] names it for the log). Any thread. */
    fun reported(from: String) = Bg.onMain {
        if (!::app.isInitialized || !setting.on) return@onMain
        Log.i(TAG, "hotspot check: $from reaches no internet through the hotspot")
        run(core.reported(CallRegistry.anyCall()), "$from reported no internet")
    }

    private fun check() {
        if (!setting.on || !Hotspot.isOn(app)) return
        if (HotspotPause.paused) return hotspotChanged(true)
        Hotspot.freshClientCount { devices ->
            if (devices == null || devices == 0) return@freshClientCount hotspotChanged(true)
            val s = setting
            exec.execute {
                val ok = Ping.reaches(cellular(), s.address)
                Bg.onMain {
                    if (!ok) Log.w(TAG, "hotspot check: ${s.address} did not answer over mobile data (miss ${core.misses + 1})")
                    run(core.checked(ok, s.misses, CallRegistry.anyCall()), "${s.address} did not answer")
                    hotspotChanged(Hotspot.isOn(app))
                }
            }
        }
    }

    private fun run(step: HotspotWatchCore.Step, why: String) {
        when (step) {
            HotspotWatchCore.Step.RESTART_HOTSPOT -> restartHotspot(why)
            HotspotWatchCore.Step.TOGGLE_DATA -> toggleData(why)
            HotspotWatchCore.Step.WAIT -> waitForCall()
            HotspotWatchCore.Step.NONE -> {}
        }
    }

    private fun restartHotspot(why: String) {
        if (!Hotspot.isOn(app)) return
        Log.i(TAG, "hotspot check: restarting the hotspot ($why)")
        Diag.event("hotspot_watch", org.json.JSONObject().put("step", "restart"), withLog = false)
        Hotspot.restart(app) { ok, detail -> Log.i(TAG, "hotspot check: hotspot back ${if (ok) "on" else "failed: $detail"}") }
    }

    private fun toggleData(why: String) {
        Log.i(TAG, "hotspot check: the hotspot restart did not help, mobile data off and on ($why)")
        Diag.event("hotspot_watch", org.json.JSONObject().put("step", "mobile_data"), withLog = false)
        if (!MobileData.set(app, false)) return
        WakeAlarm.set(app, DATA_ALARM, DATA_OFF_MS) { MobileData.set(app, true) }
    }

    /** A call is up: look again every 20 s, and take the step once no call is left. */
    private fun waitForCall() {
        if (!WakeAlarm.pending(CALL_ALARM)) Log.i(TAG, "hotspot check: the step waits for the end of the call")
        WakeAlarm.set(app, CALL_ALARM, CALL_RECHECK_MS) {
            if (CallRegistry.anyCall()) waitForCall() else run(core.afterCall(), "after the call")
        }
    }

    @Suppress("DEPRECATION")      // allNetworks: the one way to list networks that are not the default
    private fun cellular(): Network? {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } == true
        }
    }
}

/** ICMP echo over one network (an unprivileged ping socket, as the system ping uses). */
private object Ping {
    private const val TAG = "HermesSimHost"
    private const val TRIES = 3
    private const val WAIT_MS = 3_000L
    private const val ECHO_REPLY = 0

    /** True when [address] answered one of three echoes sent over [net]; false without [net]. */
    fun reaches(net: Network?, address: String): Boolean {
        if (net == null) {
            Log.w(TAG, "hotspot check: no mobile data network")
            return false
        }
        return try {
            val target = net.getAllByName(address).firstOrNull { it is Inet4Address }
                ?: throw IOException("$address has no IPv4 address")
            val fd = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_DGRAM, OsConstants.IPPROTO_ICMP)
            try {
                net.bindSocket(fd)
                Os.setsockoptTimeval(fd, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO, StructTimeval.fromMillis(WAIT_MS))
                (1..TRIES).any { seq -> echo(fd, target, seq) }
            } finally {
                Os.close(fd)
            }
        } catch (e: IOException) {
            Log.w(TAG, "hotspot check: ping $address: $e")
            false
        } catch (e: ErrnoException) {
            Log.w(TAG, "hotspot check: ping $address: $e")
            false
        }
    }

    /** One echo request; the kernel fills the identifier and the checksum. */
    private fun echo(fd: java.io.FileDescriptor, target: java.net.InetAddress, seq: Int): Boolean {
        val request = byteArrayOf(8, 0, 0, 0, 0, 0, (seq shr 8).toByte(), seq.toByte(), 'h'.code.toByte(), 's'.code.toByte())
        Os.sendto(fd, request, 0, request.size, 0, target, 0)
        val reply = ByteArray(64)
        val n = try {
            Os.recvfrom(fd, reply, 0, reply.size, 0, null)
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.EAGAIN) return false      // no answer within WAIT_MS
            throw e
        }
        return n >= 8 && reply[0].toInt() == ECHO_REPLY
    }
}
