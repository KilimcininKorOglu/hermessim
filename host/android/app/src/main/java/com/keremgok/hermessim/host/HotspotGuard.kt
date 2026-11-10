// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.keremgok.hermessim.protocol.v1.Hotspot as HotspotMsg
import org.json.JSONArray
import org.json.JSONObject

/**
 * Hotspot devices on the host: the blocked list (prefs "hotspot_block", kept across restarts and applied each time
 * the hotspot comes up) and each device's data since the hotspot came on. The rules come from [HotspotGuardCore] and
 * run as root on one background thread; a failed run is logged and the next change tries again.
 */
object HotspotGuard {
    private const val TAG = "HermesSimHost"
    private val exec = Bg.single("hotspot-guard")
    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private var onChange: () -> Unit = {}

    @Volatile private var iface: String? = null
    private val counted = mutableSetOf<String>()                     // exec thread only
    @Volatile private var usage: Map<String, HotspotGuardCore.Usage> = emptyMap()

    private val prefs get() = app.getSharedPreferences("hotspot_block", Context.MODE_PRIVATE)
    val ready get() = ::app.isInitialized

    fun init(ctx: Context, changed: () -> Unit) { app = ctx.applicationContext; onChange = changed }

    /** The blocked devices (MAC and the name the client gave), connected or not. */
    fun blocked(): List<HotspotMsg.Client> {
        if (!ready) return emptyList()
        val a = JSONArray(prefs.getString("list", "[]"))
        return (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            with(HotspotMsg.Client.newBuilder()) { mac = o.optString("mac"); name = o.optString("name"); build() }
        }
    }

    /** Main: block or unblock [mac]. False when it isn't a MAC or the list already holds [HotspotGuardCore.MAX_BLOCKED]. */
    fun set(mac: String, block: Boolean, name: String): Boolean {
        if (!ready || !HotspotGuardCore.validMac(mac.lowercase())) return false
        val now = blocked()
        val next = HotspotGuardCore.change(now.map { it.mac }, mac, block) ?: return false
        val names = now.associate { it.mac to it.name } + (mac.lowercase() to name.take(64))
        val a = JSONArray()
        next.forEach { m -> a.put(JSONObject().put("mac", m).put("name", names[m].orEmpty())) }
        prefs.edit().putString("list", a.toString()).apply()
        Log.i(TAG, "hotspot device ${if (block) "blocked" else "unblocked"} (${next.size} blocked)")
        iface?.let { i -> exec.execute { applyBlock(i, next) } }
        onChange()
        return true
    }

    /** The hotspot came up on [wifiIface]; [fresh] = a new session (not the end of a call pause): counts start at 0. */
    fun up(wifiIface: String, fresh: Boolean) {
        if (!ready) return
        iface = wifiIface
        val list = blocked().map { it.mac }
        exec.execute {
            if (fresh) { run("teardown", HotspotGuardCore.teardownScript()); counted.clear(); usage = emptyMap() }
            applyBlock(wifiIface, list)
        }
    }

    /** The hotspot went down; [paused] = for a call, so the rules and counts stay for when it's back. */
    fun down(paused: Boolean) {
        if (!ready || paused) return
        iface = null
        exec.execute { run("teardown", HotspotGuardCore.teardownScript()); counted.clear(); usage = emptyMap() }
    }

    /** The devices on the hotspot now (Hotspot's NEIGH poll): count the new ones, then read every device's bytes. */
    fun seen(clients: List<HotspotMsg.Client>) {
        if (!ready || iface == null) return
        val ips = clients.map { it.ip }
        exec.execute {
            val add = HotspotGuardCore.countScript(ips, counted)
            if (run("count", add)) counted += ips.filter(HotspotGuardCore::validIp)
            val r = Root.sh(HotspotGuardCore.READ, 10_000)
            if (!r.ok) { Log.w(TAG, "hotspot device counters: ${r.last}"); return@execute }
            val read = HotspotGuardCore.parse(r.out)
            if (read != usage) { usage = read; main.post(onChange) }
        }
    }

    /** A device's bytes since the hotspot came on, by its IP. */
    fun usage(ip: String): HotspotGuardCore.Usage = usage[ip] ?: HotspotGuardCore.Usage()

    private fun applyBlock(i: String, list: List<String>) = run("block", HotspotGuardCore.blockScript(i, list))

    private fun run(what: String, script: String): Boolean {
        val r = Root.sh(script, 15_000)
        if (!r.ok) Log.w(TAG, "hotspot device rules ($what) failed: ${r.last}")
        return r.ok
    }
}
