// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.keremgok.hermessim.wire.Code
import com.keremgok.hermessim.wire.HotspotBand
import com.keremgok.hermessim.wire.HotspotClient
import com.keremgok.hermessim.wire.HotspotClients as HotspotClientsMsg
import com.keremgok.hermessim.wire.HotspotLimits
import com.keremgok.hermessim.wire.HotspotPhase
import com.keremgok.hermessim.wire.HotspotSecurity
import com.keremgok.hermessim.wire.SetHotspotSetup
import com.keremgok.hermessim.wire.Hotspot as HotspotMsg
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/** The hotspot as the host reads it. [available] false: this phone's hotspot cannot be read or set. */
data class HotspotInfo(
    val available: Boolean,
    val on: Boolean = false,
    val starting: Boolean = false,
    val gateway: String = "",
    val ssid: String = "",
    val passphrase: String = "",
    val band: HotspotBand = HotspotBand.HOTSPOT_BAND_UNSPECIFIED,
    val security: HotspotSecurity = HotspotSecurity.HOTSPOT_SECURITY_UNSPECIFIED,
    val hidden: Boolean = false,
    val idleOff: Boolean = false,
    val idleOffMinutes: Int = 0,
    val appleMarker: Boolean = false,
    val frequencyMhz: Int = 0,
    val clients: List<ApClient> = emptyList(),
)

/** The hotspot settings to save. */
data class ApWanted(
    val ssid: String,
    val passphrase: String,
    val band: HotspotBand,
    val security: HotspotSecurity,
    val hidden: Boolean,
    val idleOff: Boolean,
    val idleOffMinutes: Int,
    val appleMarker: Boolean,
)

/** What a peer is told about the hotspot, and what it may ask of it. Pure. */
object HotspotCore {
    /** The settings after [s]: a field [s] does not carry stays as [now] has it. */
    fun wanted(now: HotspotInfo, s: SetHotspotSetup): ApWanted = ApWanted(
        ssid = if (s.hasName()) s.name else now.ssid,
        passphrase = if (s.hasPassphrase()) s.passphrase else now.passphrase,
        band = if (s.hasBand()) s.band else now.band,
        security = if (s.hasSecurity()) s.security else now.security,
        hidden = if (s.hasHidden()) s.hidden else now.hidden,
        idleOff = if (s.hasIdleOff()) s.idleOff else now.idleOff,
        idleOffMinutes = if (s.hasIdleOffMinutes()) s.idleOffMinutes else now.idleOffMinutes,
        appleMarker = if (s.hasAppleMarker()) s.appleMarker else now.appleMarker,
    )

    /** True when [s] names a setting of the phone's own hotspot (not the month limit or the call pause alone). */
    fun touchesRadio(s: SetHotspotSetup): Boolean = s.hasName() || s.hasPassphrase() || s.hasBand() || s.hasSecurity() ||
        s.hasHidden() || s.hasIdleOff() || s.hasIdleOffMinutes() || s.hasAppleMarker()

    /** Why [w] cannot be saved (the code and, for a bad value, the field of SetHotspotSetup), or null. */
    fun refusal(w: ApWanted): Pair<Code, Int>? = when {
        w.ssid.isBlank() || w.ssid.toByteArray().size > 32 -> Code.CODE_BAD_VALUE to SetHotspotSetup.NAME_FIELD_NUMBER
        w.security == HotspotSecurity.HOTSPOT_SECURITY_WPA3 || w.security == HotspotSecurity.HOTSPOT_SECURITY_WPA2_WPA3 ->
            Code.CODE_NOT_OFFERED to SetHotspotSetup.SECURITY_FIELD_NUMBER
        w.security != HotspotSecurity.HOTSPOT_SECURITY_OPEN && w.passphrase.length !in 8..63 ->
            Code.CODE_BAD_VALUE to SetHotspotSetup.PASSPHRASE_FIELD_NUMBER
        else -> null
    }

    fun phase(info: HotspotInfo, paused: Boolean, failed: Boolean): HotspotPhase = when {
        info.on -> HotspotPhase.HOTSPOT_PHASE_ON
        paused -> HotspotPhase.HOTSPOT_PHASE_PAUSED
        info.starting -> HotspotPhase.HOTSPOT_PHASE_STARTING
        failed -> HotspotPhase.HOTSPOT_PHASE_FAILED
        else -> HotspotPhase.HOTSPOT_PHASE_OFF
    }

    /** The `Hotspot` group. The passphrase is not in it: a peer asks for it apart, over Bluetooth. */
    fun message(info: HotspotInfo, phase: HotspotPhase, monthLimit: Long, limitReached: Boolean, pauseInCalls: Boolean): HotspotMsg =
        HotspotMsg.newBuilder().setAvailable(info.available).setPhase(phase).setName(info.ssid).setBand(info.band)
            .setSecurity(info.security).setHidden(info.hidden).setIdleOff(info.idleOff).setIdleOffMinutes(info.idleOffMinutes)
            .setMonthLimitBytes(monthLimit).setLimitReached(limitReached).setPauseInCalls(pauseInCalls)
            .setFrequencyMhz(info.frequencyMhz).setGateway(info.gateway).setAppleMarker(info.appleMarker).build()

    /** The `HotspotClients` group: the devices on the hotspot, then the blocked ones that are not on it. */
    fun clients(connected: List<ApClient>, blocked: List<ApClient>): HotspotClientsMsg {
        val barred = blocked.associateBy { it.mac }
        val here = connected.map { c -> entry(c, connected = true, blocked = c.mac in barred, name = c.name.ifEmpty { barred[c.mac]?.name.orEmpty() }) }
        val away = blocked.filter { b -> connected.none { it.mac == b.mac } }.map { entry(it, connected = false, blocked = true, name = it.name) }
        return HotspotClientsMsg.newBuilder().addAllClients(here + away).build()
    }

    private fun entry(c: ApClient, connected: Boolean, blocked: Boolean, name: String): HotspotClient =
        HotspotClient.newBuilder().setMac(com.google.protobuf.ByteString.copyFrom(c.macBytes())).setName(name).setIp(c.ip)
            .setConnected(connected).setBlocked(blocked).setDownBytes(c.down).setUpBytes(c.up).build()
}

/**
 * Wi-Fi hotspot with internet sharing. TetheringManager / SoftApConfiguration are system APIs (not in
 * the public SDK), hence reflection; they need TETHER_PRIVILEGED + OVERRIDE_WIFI_CONFIG (whitelisted in
 * the module). `cmd wifi start-softap` is not an option: it starts the radio without tethering.
 */
object Hotspot {
    private const val TAG = "HermesSimHost"
    private const val TETHERING_WIFI = 0
    // SoftApConfiguration constants
    private const val BAND_2GHZ = 1
    private const val BAND_5GHZ = 2
    private const val BAND_6GHZ = 4
    private const val SEC_OPEN = 0
    private const val SEC_WPA2 = 1
    private const val SEC_WPA3_TRANSITION = 2
    private const val SEC_WPA3 = 3

    private val exec = Bg.single("hotspot")
    @Volatile private var clients: List<ApClient> = emptyList()
    /** Device names Android's TetheredClient gave, by lowercase MAC: NEIGH has MAC and IP only. */
    @Volatile private var names: Map<String, String> = emptyMap()
    @Volatile private var tetheredWifi = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var listener: (() -> Unit)? = null
    /** Every change is reported on the main thread, whichever thread noticed it (tethering callbacks, the daemon). */
    private fun changed() { listener?.let { main.post(it) } }

    /** Follow tethering state and connected clients (TetheringEventCallback); [changed] on every change, on main. */
    fun watch(ctx: Context, onChange: () -> Unit) {
        listener = onChange
        exec.execute {
            val cfg = apConfig(ctx, wait = true)          // Android 11: have the root-read settings ready
            if (cfg != null) syncVendorTimeout(ctx, cfg.autoOff, (cfg.timeoutMs / 60_000L).toInt())
        }
        dropWpa3(ctx)
        SoftApWatch.start(ctx, exec, ::changed)
        runCatching {
            val tm = ctx.getSystemService("tethering") ?: return
            val cbClass = Class.forName("android.net.TetheringManager\$TetheringEventCallback")
            val cb = Proxy.newProxyInstance(cbClass.classLoader, arrayOf(cbClass)) { proxy, m, args ->
                when (m.name) {
                    // The framework puts the callback in maps: answer Object's methods properly.
                    "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                    "equals" -> return@newProxyInstance proxy === args?.get(0)
                    "toString" -> return@newProxyInstance "HermesSimTetheringCallback"
                    "onClientsChanged" -> {
                        val raw = (args?.get(0) as? Collection<*>).orEmpty()
                        if (raw.isNotEmpty()) clients = raw.mapNotNull { c -> client(c) }   // usually empty on Android 13
                        names = clients.filter { it.name.isNotEmpty() }.associate { it.mac to it.name }
                        Log.i(TAG, "hotspot clients: ${clients.size} (raw ${raw.size}) ${clients.map { it.name.ifEmpty { "?" } }}")
                        changed()
                    }
                    "onTetheredInterfacesChanged" -> {
                        // Android 13 passes Set<TetheringInterface> (getInterface()); older ones List<String>.
                        val ifaces = (args?.get(0) as? Collection<*>).orEmpty().map { e ->
                            e as? String ?: runCatching { e!!.javaClass.getMethod("getInterface").invoke(e) as String }
                                .getOrDefault(e.toString())
                        }
                        Log.i(TAG, "tethered interfaces: $ifaces")
                        val wifi = ifaces.any(::isWifiAp)
                        DataUsage.tetheringChanged(ifaces.filter(::isWifiAp))
                        tethered = ifaces
                        if (wifi != tetheredWifi) { tetheredWifi = wifi; Log.i(TAG, "hotspot tethered=$wifi $ifaces"); changed() }
                        if (!wifi) whenDown?.invoke()
                    }
                }
                when (m.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    else -> null
                }
            }
            tm.javaClass.getMethod("registerTetheringEventCallback", java.util.concurrent.Executor::class.java, cbClass)
                .invoke(tm, exec, cb)
        }.onFailure { Log.w(TAG, "tethering events unavailable: ${cause(it)}") }
    }

    /**
     * Devices on the hotspot from the kernel ARP table via hermessimd (Android 13 hides TetheredClient details). A failed
     * query keeps the last list: read as "no devices", it could switch an off-once-unused hotspot off under a laptop
     * (audit, 1 Nov 2026). [done] (main) hears whether the list is fresh.
     */
    fun refreshClients(iface: String, done: ((Boolean) -> Unit)? = null) = Daemon.query("NEIGH $iface") { reply ->
        val list = HotspotClients.parse(reply, names)
        val fresh = list != null
        if (list != null) {
            HotspotGuard.seen(list)
            if (list != clients) {
                clients = list
                Log.i(TAG, "hotspot devices: ${list.size}")
                changed()
                if (done == null && offUnusedMs > 0) main.post { decideUnused() }
            }
        } else Log.w(TAG, "hotspot devices: query failed (${reply?.take(60)}), keeping the last list")
        done?.let { d -> main.post { d(fresh) } }
    }

    /** Devices on the hotspot right now, asked for afresh; [done] (main) gets null if it's off or the query failed. */
    fun freshClientCount(done: (Int?) -> Unit) {
        val iface = tethered.firstOrNull(::isWifiAp)
        if (iface == null) { main.post { done(null) }; return }
        refreshClients(iface) { fresh -> done(if (fresh) clients.size else null) }
    }

    fun clearClients() { if (clients.isNotEmpty()) { clients = emptyList(); changed() } }

    /**
     * Samsung runs its own "turn the hotspot off when nothing is connected" timer next to Android's (secure setting
     * wifi_ap_timeout_setting, minutes, 0 = never; the carrier default is 20). It switched the hotspot off after 20 idle
     * minutes while HermesSIM's auto off was off (22 Oct 2026): it follows HermesSIM's setting now. Other makers: no-op.
     */
    private fun syncVendorTimeout(ctx: Context, autoOff: Boolean, minutes: Int) {
        if (!android.os.Build.MANUFACTURER.equals("samsung", ignoreCase = true)) return
        val want = if (autoOff) minutes.coerceIn(1, 60) else 0
        runCatching {
            val cr = ctx.contentResolver
            if (android.provider.Settings.Secure.getInt(cr, "wifi_ap_timeout_setting", -1) == want) return
            android.provider.Settings.Secure.putInt(cr, "wifi_ap_timeout_setting", want)
            Log.i(TAG, "Samsung hotspot timeout -> ${if (want == 0) "never" else "$want min"} (HermesSIM's auto off)")
        }.onFailure { Log.w(TAG, "Samsung hotspot timeout: $it") }
    }

    // ---- off once unused (SetHotspot.off_when_unused_s)
    // The iPhone's automatic hotspot moved to another Wi-Fi: the hotspot goes off once no device has used it for a
    // while. The iPhone used to wait for that minute itself, but iOS gives a background app no network events: it
    // went off only when something next woke the iPhone (13 minutes late, 22 Oct 2026). The host holds the timer,
    // on a WakeAlarm (deep sleep too). Any later SetHotspot cancels it.
    @Volatile private var offUnusedMs = 0L
    @Volatile private var unusedSince = 0L
    @Volatile private var appCtx: Context? = null
    @Volatile private var waitedForCall = false
    private const val UNUSED_ALARM = "hotspot-unused"

    fun offWhenUnused(ctx: Context, seconds: Int) {
        appCtx = ctx.applicationContext
        if (HotspotPause.deferOffWhenUnused(seconds.coerceIn(30, 3600))) return     // counted from when it's back on
        val ms = seconds.coerceIn(30, 3600) * 1000L
        if (ms == offUnusedMs) return                  // already counting: the iPhone asks again after a reconnect
        offUnusedMs = ms
        unusedSince = 0
        Log.i(TAG, "hotspot: off once unused for ${offUnusedMs / 1000} s")
        main.post { checkUnused() }
    }

    /** The "off once unused" countdown running now, in seconds (0: none): a call pause carries it over. */
    fun offWhenUnusedSeconds(): Int = (offUnusedMs / 1000).toInt()

    fun cancelOffWhenUnused() {
        if (offUnusedMs == 0L) return
        offUnusedMs = 0; waitedForCall = false
        appCtx?.let { WakeAlarm.cancel(it, UNUSED_ALARM) }
        Log.i(TAG, "hotspot: off-when-unused cancelled")
    }

    /** Main thread: the devices asked for afresh (the usage poll that also lists them stops in deep sleep), then decide. */
    private fun checkUnused() {
        val ctx = appCtx ?: return
        if (offUnusedMs == 0L) return
        if (!isOn(ctx)) { offUnusedMs = 0; return }
        val iface = tethered.firstOrNull(::isWifiAp) ?: return decideUnused()
        refreshClients(iface) { fresh -> if (fresh) decideUnused() else WakeAlarm.set(ctx, UNUSED_ALARM, 15_000) { checkUnused() } }
    }

    /** Main thread: turn off once unused long enough, else look again (at the deadline, or in a minute while used). */
    private fun decideUnused() {
        val ctx = appCtx ?: return
        if (offUnusedMs == 0L || !isOn(ctx)) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (clients.isNotEmpty()) unusedSince = 0 else if (unusedSince == 0L) unusedSince = now
        val left = if (unusedSince == 0L) offUnusedMs else offUnusedMs - (now - unusedSince)
        if (unusedSince != 0L && left <= 0) {
            // Never in a call: a host that turned its unused hotspot off mid-call (its Wi-Fi then went off entirely)
            // faded the call's Bluetooth audio 4 s later and the call moved to the internet (8 Nov 2026). Wi-Fi
            // changes wait for the call's end, as a call pause's do (HotspotPause).
            if (HotspotPause.callUp) {
                if (!waitedForCall) { waitedForCall = true; Log.i(TAG, "hotspot unused: off once the call is over") }
                WakeAlarm.set(ctx, UNUSED_ALARM, 20_000L) { checkUnused() }
                return
            }
            waitedForCall = false
            offUnusedMs = 0
            Log.i(TAG, "hotspot off: unused for ${(now - unusedSince) / 1000} s (the phone is on another Wi-Fi)")
            set(ctx, false) { _, _ -> }
            return
        }
        WakeAlarm.set(ctx, UNUSED_ALARM, if (unusedSince == 0L) 60_000L else maxOf(left, 5_000L)) { checkUnused() }
    }

    // TetheredClient -> (name, mac). Never drop a client because a field can't be read: the automatic
    // hotspot relies on "nothing connected" being right.
    private fun client(c: Any?): ApClient? {
        if (c == null) return null
        val mac = runCatching { c.javaClass.getMethod("getMacAddress").invoke(c).toString() }.getOrDefault("")
        val name = runCatching {
            (c.javaClass.getMethod("getAddresses").invoke(c) as List<*>).firstNotNullOfOrNull { a ->
                a?.javaClass?.getMethod("getHostname")?.invoke(a) as? String
            }
        }.getOrNull()
        return ApClient(mac.lowercase(), name = name.orEmpty())
    }

    /** The soft AP settings we show and change. */
    private data class ApCfg(val ssid: String, val passphrase: String, val security: Int, val band: Int, val bands: IntArray?,
                             val hidden: Boolean, val maxClients: Int, val autoOff: Boolean, val timeoutMs: Long,
                             val appleMarker: Boolean = false) {
        fun same(o: ApCfg) = copy(bands = null) == o.copy(bands = null) && bands.contentEquals(o.bands)
    }

    /**
     * The idle timeout in force: 0 (Android 11-12) and -1 (13+) mean the framework's default, 10 minutes in AOSP. Taken
     * at face value they reported 0 minutes and set Samsung's own idle timer to 1 minute on any Samsung host whose
     * hotspot settings were never saved from HermesSIM (audit, 1 Nov 2026).
     */
    private fun timeout(ms: Long?): Long = ms?.takeIf { it > 0 } ?: 600_000L

    @Volatile private var rootCfg: Pair<ApCfg, Long>? = null
    @Volatile private var rootCfgLoading = false

    /** On, starting, restarting with new settings or paused for a call: the host Wi-Fi rule must not switch Wi-Fi on in the gap. */
    fun isOnOrStarting(ctx: Context): Boolean = upOrStarting(ctx) || HotspotPause.paused

    /** The radio's own state: on, starting or restarting with new settings (a call pause doesn't count). */
    fun upOrStarting(ctx: Context): Boolean =
        isOn(ctx) || whenDown != null || android.os.SystemClock.elapsedRealtime() - startingSince < 15_000

    /**
     * Pause during calls is offered (HotspotPause): this host relays call audio, and its hotspot can only use 2.4 GHz,
     * the band its Bluetooth uses too. A host that can run its hotspot on 5 GHz keeps the two apart: there the band is
     * the answer, not pausing.
     */
    fun callPauseOffered(ctx: Context): Boolean = testOffered ?: (VoiceGate.fullVoice &&
        runCatching { !ctx.getSystemService(WifiManager::class.java).is5GHzBandSupported }.getOrDefault(false))

    /** adb test hook (HostService PAUSE_TEST, root only, this process only): pretend the host is (not) offered it. */
    @Volatile var testOffered: Boolean? = null

    /** On or off (cheap: no settings read, never a root call). */
    fun isOn(ctx: Context): Boolean = runCatching {
        WifiManager::class.java.getMethod("isWifiApEnabled").invoke(ctx.getSystemService(WifiManager::class.java)) as Boolean
    }.getOrDefault(false)

    /**
     * The hotspot's settings: from WifiManager where we may (Android 12+), else (Android 11: NETWORK_SETTINGS only) from
     * the root helper. That one takes seconds, so callers get the cached copy and a stale one is re-read on this
     * object's executor (a change is then reported); only that executor ever waits for it ([wait]).
     */
    private fun apConfig(ctx: Context, wait: Boolean = false): ApCfg? {
        val wm = ctx.getSystemService(WifiManager::class.java)
        val direct = runCatching { WifiManager::class.java.getMethod("getSoftApConfiguration").invoke(wm)!! }
        direct.getOrNull()?.let { cfg ->
            fun <T> get(name: String): T? = runCatching { @Suppress("UNCHECKED_CAST") (cfg.javaClass.getMethod(name).invoke(cfg) as T) }.getOrNull()
            return ApCfg(get<String>("getSsid").orEmpty(), get<String>("getPassphrase").orEmpty(), get<Int>("getSecurityType") ?: SEC_WPA2,
                get<Int>("getBand") ?: BAND_2GHZ, get<IntArray>("getBands"), get<Boolean>("isHiddenSsid") ?: false,
                get<Int>("getMaxNumberOfClients") ?: 0, get<Boolean>("isAutoShutdownEnabled") ?: false, timeout(get<Long>("getShutdownTimeoutMillis")),
                AppleMarker.present(SoftApWatch.elements(cfg)))
        }
        if (cause(direct.exceptionOrNull() ?: return null) !is SecurityException) return null
        val cached = rootCfg
        if (cached != null && android.os.SystemClock.elapsedRealtime() - cached.second < 60_000) return cached.first
        if (!wait) {
            if (!rootCfgLoading) { rootCfgLoading = true; exec.execute { apConfig(ctx, wait = true); rootCfgLoading = false } }
            return cached?.first
        }
        val m = RootWifi.run(ctx, "get") ?: return cached?.first
        fun s(k: String) = String(android.util.Base64.decode(m[k].orEmpty(), android.util.Base64.NO_WRAP))
        val c = ApCfg(s("ssid"), s("passphrase"), m["security"]?.toIntOrNull() ?: SEC_WPA2, m["band"]?.toIntOrNull() ?: BAND_2GHZ,
            m["bands"]?.split(",")?.mapNotNull { it.toIntOrNull() }?.toIntArray(), m["hidden"] == "1", m["max"]?.toIntOrNull() ?: 0,
            m["autoOff"] == "1", timeout(m["timeout"]?.toLongOrNull()))
        rootCfg = c to android.os.SystemClock.elapsedRealtime()
        if (cached == null || !cached.first.same(c)) changed()      // new settings (or the first): push status
        return c
    }

    fun info(ctx: Context, wait: Boolean = false): HotspotInfo = runCatching {
        val wm = ctx.getSystemService(WifiManager::class.java)
        val on = WifiManager::class.java.getMethod("isWifiApEnabled").invoke(wm) as Boolean
        // Android 11, before the root read of the settings is back (a second or two after start): on/off only.
        val cfg = apConfig(ctx, wait) ?: return@runCatching HotspotInfo(available = true, on = on)
        val starting = !on && startingSince > 0 && android.os.SystemClock.elapsedRealtime() - startingSince < 15_000
        HotspotInfo(available = true, on = on, starting = starting, gateway = if (on) gateway() else "",
            ssid = cfg.ssid, passphrase = cfg.passphrase, band = bandOf(cfg), security = securityOf(cfg.security),
            hidden = cfg.hidden, idleOff = cfg.autoOff, idleOffMinutes = (cfg.timeoutMs / 60_000L).toInt(),
            appleMarker = cfg.appleMarker, frequencyMhz = if (on) SoftApWatch.frequencyMhz else 0,
            clients = if (on) clients.map(::withUsage) else emptyList())
    }.getOrElse { Log.w(TAG, "hotspot info unavailable: ${cause(it)}"); HotspotInfo(available = false) }

    private fun bandOf(cfg: ApCfg): HotspotBand = when {
        cfg.bands != null && cfg.bands.size >= 2 -> HotspotBand.HOTSPOT_BAND_2G_AND_5G
        cfg.band == BAND_2GHZ -> HotspotBand.HOTSPOT_BAND_2G
        cfg.band == BAND_5GHZ -> HotspotBand.HOTSPOT_BAND_5G
        cfg.band == BAND_6GHZ -> HotspotBand.HOTSPOT_BAND_6G
        else -> HotspotBand.HOTSPOT_BAND_AUTO
    }

    private val securities = mapOf(SEC_OPEN to HotspotSecurity.HOTSPOT_SECURITY_OPEN,
        SEC_WPA3_TRANSITION to HotspotSecurity.HOTSPOT_SECURITY_WPA2_WPA3, SEC_WPA3 to HotspotSecurity.HOTSPOT_SECURITY_WPA3)

    private fun securityOf(android: Int): HotspotSecurity = securities[android] ?: HotspotSecurity.HOTSPOT_SECURITY_WPA2

    /** A device with its bytes since the hotspot came on (HotspotGuard's counters, by its IP). */
    private fun withUsage(c: ApClient): ApClient = HotspotGuard.usage(c.ip).let { u -> c.copy(down = u.down, up = u.up) }

    /**
     * Offered settings. WPA2 only (18 Oct): WPA3 hotspots depend on the phone (the Redmi Note 8 Pro's station
     * has WPA3, its hotspot refuses SAE and silently never starts) and nobody needs them for a phone-to-phone link.
     * Not offered (31 Oct 2026), both for the same reason: 6 GHz (Android allows a 6 GHz hotspot only with WPA3 or OWE)
     * and a device limit (Android won't start a hotspot with a limit unless the hotspot hardware can disconnect
     * clients, SoftApCapability CLIENT_FORCE_DISCONNECT, which the S20 FE's lacks; apps can't read the capability).
     */
    fun limits(ctx: Context): HotspotLimits = runCatching {
        val wm = ctx.getSystemService(WifiManager::class.java)
        val b = HotspotLimits.newBuilder().setHiddenName(true).setIdleOff(true).setOffWhenUnused(true)
            .setCallPause(callPauseOffered(ctx)).setAppleMarker(android.os.Build.VERSION.SDK_INT >= 33).setMonthLimit(true)
            .setClientUsage(HotspotGuard.ready).setBlockClients(HotspotGuard.ready)
        b.addBands(HotspotBand.HOTSPOT_BAND_2G)
        if (wm.is5GHzBandSupported) b.addBands(HotspotBand.HOTSPOT_BAND_5G).addBands(HotspotBand.HOTSPOT_BAND_AUTO)
        if (wm.is5GHzBandSupported && android.os.Build.VERSION.SDK_INT >= 31 && wm.isBridgedApConcurrencySupported)
            b.addBands(HotspotBand.HOTSPOT_BAND_2G_AND_5G)
        b.addSecurities(HotspotSecurity.HOTSPOT_SECURITY_WPA2).build()
    }.getOrElse { Log.w(TAG, "hotspot limits unavailable: ${cause(it)}"); HotspotLimits.getDefaultInstance() }

    /** A hotspot left on WPA3 (by the OEM or an older host version) goes back to WPA2, same name and password. */
    private fun dropWpa3(ctx: Context) = exec.execute {
        val h = info(ctx, wait = true)
        val wpa3 = h.security == HotspotSecurity.HOTSPOT_SECURITY_WPA3 || h.security == HotspotSecurity.HOTSPOT_SECURITY_WPA2_WPA3
        if (!h.available || h.on || !wpa3) return@execute
        val wanted = HotspotCore.wanted(h, SetHotspotSetup.newBuilder().setSecurity(HotspotSecurity.HOTSPOT_SECURITY_WPA2).build())
        configureNow(ctx, wanted) { code ->
            Log.i(TAG, "hotspot security ${h.security} -> WPA2: $code")
            changed()
        }
    }

    /**
     * Save the hotspot's settings; a running hotspot starts again with them. On this object's executor (Android 11
     * saves them as root, seconds); [reply] on main: CODE_OK, or CODE_FAILED when the phone refused them.
     */
    fun configure(ctx: Context, w: ApWanted, reply: (Code) -> Unit) = exec.execute { configureNow(ctx, w, reply) }

    private fun configureNow(ctx: Context, w: ApWanted, reply: (Code) -> Unit) {
        val done: (Boolean, String) -> Unit = { ok, detail ->
            if (!ok) Log.w(TAG, "hotspot settings not applied: $detail")
            main.post { reply(if (ok) Code.CODE_OK else Code.CODE_FAILED) }
        }
        runCatching {
            val wm = ctx.getSystemService(WifiManager::class.java)
            val wasOn = WifiManager::class.java.getMethod("isWifiApEnabled").invoke(wm) as Boolean
            val current = runCatching { WifiManager::class.java.getMethod("getSoftApConfiguration").invoke(wm) }.getOrNull()
            if (current == null) saveAsRoot(ctx, w) else saveDirect(wm, current, w)
            syncVendorTimeout(ctx, w.idleOff, w.idleOffMinutes)
            Log.i(TAG, "hotspot settings saved${if (current == null) " as root (Android 11)" else ""} band=${w.band} " +
                "sec=${w.security} hidden=${w.hidden} idleOff=${w.idleOff}/${w.idleOffMinutes}min appleMarker=${w.appleMarker} (was on: $wasOn)")
            if (wasOn) restart(ctx, done) else done(true, "saved")
        }.onFailure { done(false, cause(it).message ?: cause(it).toString()) }
    }

    private fun androidSecurity(s: HotspotSecurity): Int = if (s == HotspotSecurity.HOTSPOT_SECURITY_OPEN) SEC_OPEN else SEC_WPA2

    /** The bands of [band] as SoftApConfiguration takes them: one mask, or one per radio for both at once. */
    private fun androidBands(band: HotspotBand): IntArray = when (band) {
        HotspotBand.HOTSPOT_BAND_2G_AND_5G -> intArrayOf(BAND_2GHZ, BAND_5GHZ)
        HotspotBand.HOTSPOT_BAND_5G -> intArrayOf(BAND_5GHZ)
        HotspotBand.HOTSPOT_BAND_6G -> intArrayOf(BAND_6GHZ)
        HotspotBand.HOTSPOT_BAND_AUTO -> intArrayOf(BAND_2GHZ or BAND_5GHZ)
        else -> intArrayOf(BAND_2GHZ)
    }

    private fun idleMs(w: ApWanted): Long = if (w.idleOff && w.idleOffMinutes > 0) w.idleOffMinutes * 60_000L else 0L

    /** Android 11: the settings can only be saved as root (RootWifi). */
    private fun saveAsRoot(ctx: Context, w: ApWanted) {
        fun b64(x: String) = android.util.Base64.encodeToString(x.toByteArray(), android.util.Base64.NO_WRAP)
        RootWifi.run(ctx, "set", b64(w.ssid), b64(w.passphrase), "${androidSecurity(w.security)}", androidBands(w.band).joinToString(","),
            if (w.hidden) "1" else "0", "0", if (w.idleOff) "1" else "0", "${idleMs(w)}")
            ?: error("the phone rejected these settings")
        rootCfg = null
    }

    private fun saveDirect(wm: WifiManager, current: Any, w: ApWanted) {
        val cfgClass = Class.forName("android.net.wifi.SoftApConfiguration")
        val bClass = Class.forName("android.net.wifi.SoftApConfiguration\$Builder")
        val b = bClass.getConstructor(cfgClass).newInstance(current)
        fun call(name: String, types: Array<Class<*>>, vararg args: Any?) = bClass.getMethod(name, *types).invoke(b, *args)
        val sec = androidSecurity(w.security)
        val bands = androidBands(w.band)
        call("setSsid", arrayOf(String::class.java), w.ssid)
        call("setPassphrase", arrayOf(String::class.java, Int::class.javaPrimitiveType!!), if (sec == SEC_OPEN) null else w.passphrase, sec)
        if (bands.size > 1) call("setBands", arrayOf(IntArray::class.java), bands)
        else call("setBand", arrayOf(Int::class.javaPrimitiveType!!), bands[0])
        call("setHiddenSsid", arrayOf(Boolean::class.javaPrimitiveType!!), w.hidden)
        call("setMaxNumberOfClients", arrayOf(Int::class.javaPrimitiveType!!), 0)     // no limit (see limits)
        call("setAutoShutdownEnabled", arrayOf(Boolean::class.javaPrimitiveType!!), w.idleOff)
        if (idleMs(w) > 0) call("setShutdownTimeoutMillis", arrayOf(Long::class.javaPrimitiveType!!), idleMs(w))
        if (android.os.Build.VERSION.SDK_INT >= 33)
            call("setVendorElements", arrayOf(List::class.java), SoftApWatch.marked(current, w.appleMarker))
        val cfg = bClass.getMethod("build").invoke(b)
        val ok = WifiManager::class.java.getMethod("setSoftApConfiguration", cfgClass).invoke(wm, cfg) as Boolean
        if (!ok) error("the phone rejected these settings")
    }

    @Volatile private var tethered: List<String> = emptyList()
    @Volatile private var whenDown: (() -> Unit)? = null

    /** New settings on a running hotspot: stop it, start it again once Android reports Wi-Fi tethering gone. */
    private fun restart(ctx: Context, reply: (Boolean, String) -> Unit) {
        val again = Runnable { whenDown = null; set(ctx, true, reply) }
        whenDown = { main.removeCallbacks(again); main.post(again) }
        main.postDelayed(again, 5_000)                 // no tethering event (callback unavailable): go anyway
        set(ctx, false) { _, _ -> }
    }

    /** A Wi-Fi hotspot interface by its usual names (wlan1 / swlan0 / ap0 / softap0, by maker): one list for both uses. */
    private fun isWifiAp(name: String) = name.startsWith("wlan") || name.startsWith("swlan") || name.startsWith("ap") || name.startsWith("softap")

    /** The soft AP's own IPv4 (e.g. 192.168.x.1): on the interfaces Android reports as tethered, else by usual names. */
    private fun gateway(): String = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && (it.name in tethered || (isWifiAp(it.name) && it.name != "wlan0")) }
            .sortedByDescending { it.name in tethered }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull().orEmpty()

    /** Start/stop Wi-Fi tethering; [done] gets (ok, detail) once the system answers. */
    /** A start is in flight since (elapsedRealtime); 0 = none. */
    @Volatile private var startingSince = 0L

    /**
     * Idempotent: the iPhone may ask several times at once (widget taps + automatic hotspot), and a second
     * startTethering while one is in flight fails with TETHER_ERROR_INTERNAL_ERROR (5) although the hotspot
     * comes up (seen 16 Oct 2026). So "on" while on or starting is OK, and a failure while the hotspot is
     * actually up counts as success.
     */
    fun set(ctx: Context, on: Boolean, reply: (Boolean, String) -> Unit) {
        val done: (Boolean, String) -> Unit = { ok, d -> main.post { reply(ok, d) } }     // answers come on main
        if (on) cancelOffWhenUnused()
        val now = android.os.SystemClock.elapsedRealtime()
        val isOn = isOn(ctx)
        if (on && (isOn || now - startingSince < 15_000)) {
            Log.i(TAG, "hotspot start: already ${if (isOn) "on" else "starting"}, nothing to do")
            done(true, if (isOn) "already on" else "starting"); return
        }
        if (!on && !isOn && now - startingSince >= 15_000) { done(true, "already off"); return }
        // (a stop while starting goes through: stopTethering cancels the pending start)
        runCatching {
            val tm = ctx.getSystemService("tethering") ?: error("no tethering service")
            val tmClass = tm.javaClass
            if (!on) {
                startingSince = 0
                tmClass.getMethod("stopTethering", Int::class.javaPrimitiveType).invoke(tm, TETHERING_WIFI)
                Log.i(TAG, "hotspot stop requested")
                done(true, "stopped")
                return
            }
            MobileData.ensureOn(ctx)                    // the hotspot shares mobile data: it must be on
            val builderClass = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
            val builder = builderClass.getConstructor(Int::class.javaPrimitiveType).newInstance(TETHERING_WIFI)
            builderClass.getMethod("setShouldShowEntitlementUi", Boolean::class.javaPrimitiveType).invoke(builder, false)
            val request = builderClass.getMethod("build").invoke(builder)
            val cbClass = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
            val cb = Proxy.newProxyInstance(cbClass.classLoader, arrayOf(cbClass)) { _, m, args ->
                when (m.name) {
                    // Fires when the request is accepted, ~2 s before the AP is up: keep the in-flight window open
                    // (isWifiApEnabled covers it once up), or a second request in the gap fails with error 5.
                    "onTetheringStarted" -> { Log.i(TAG, "hotspot started"); done(true, "started") }
                    "onTetheringFailed" -> {
                        startingSince = 0
                        val upAnyway = isOn(ctx)
                        Log.w(TAG, "hotspot start failed: error ${args?.get(0)}${if (upAnyway) " (but it is on: OK)" else ""}")
                        if (!upAnyway) Diag.event("hotspot_failed", org.json.JSONObject().put("what", "error ${args?.get(0)}"))
                        done(upAnyway, if (upAnyway) "started" else "error ${args?.get(0)}")
                    }
                }
                null
            }
            startingSince = now
            tmClass.getMethod("startTethering", request.javaClass, java.util.concurrent.Executor::class.java, cbClass)
                .invoke(tm, request, exec, cb)
            Log.i(TAG, "hotspot start requested")
        }.onFailure {
            if (on) startingSince = 0
            Log.e(TAG, "hotspot ${if (on) "start" else "stop"} failed: ${cause(it)}")
            done(false, cause(it).toString())
        }
    }

    private fun cause(t: Throwable): Throwable = (t as? InvocationTargetException)?.targetException ?: t
}
