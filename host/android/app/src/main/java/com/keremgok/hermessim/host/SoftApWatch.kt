// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/** The hotspot's frequency and vendor elements: both system APIs, reached by reflection as Hotspot does. */
object SoftApWatch {
    private const val TAG = "HermesSimHost"

    /** The running hotspot's frequency in MHz (Hotspot.frequency_mhz); 0 = off or unknown. */
    @Volatile var frequencyMhz = 0
        private set

    /**
     * Follow the hotspot's channel (WifiManager.SoftApCallback). Android 12 lets OVERRIDE_WIFI_CONFIG register it;
     * Android 11 wants NETWORK_SETTINGS, which a privileged app can't hold, so there it stays unknown. [changed] runs
     * on [exec] after each change.
     */
    fun start(ctx: Context, exec: Executor, changed: () -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < 31) return
        runCatching {
            val wm = ctx.getSystemService(WifiManager::class.java)
            val cbClass = Class.forName("android.net.wifi.WifiManager\$SoftApCallback")
            val cb = Proxy.newProxyInstance(cbClass.classLoader, arrayOf(cbClass)) { proxy, m, args ->
                when (m.name) {
                    "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                    "equals" -> return@newProxyInstance proxy === args?.get(0)
                    "toString" -> return@newProxyInstance "HermesSimSoftApCallback"
                    "onInfoChanged" -> if (infoChanged(args?.get(0))) changed()
                }
                null
            }
            WifiManager::class.java.getMethod("registerSoftApCallback", Executor::class.java, cbClass).invoke(wm, exec, cb)
        }.onFailure { Log.w(TAG, "hotspot frequency unavailable: ${cause(it)}") }
    }

    /** onInfoChanged hands one SoftApInfo (the Android 11 form, still called) or a list of them (12+, bridged). */
    private fun infoChanged(info: Any?): Boolean {
        val infos = (info as? Collection<*>)?.toList() ?: listOf(info)
        val mhz = SoftApFrequency.of(infos.mapNotNull { i ->
            i?.let { runCatching { it.javaClass.getMethod("getFrequency").invoke(it) as Int }.getOrNull() }
        })
        if (mhz == frequencyMhz) return false
        frequencyMhz = mhz
        Log.i(TAG, "hotspot frequency: $mhz MHz")
        return true
    }

    /** A SoftApConfiguration's vendor elements (Android 13+); none before it or when unreadable. */
    fun elements(cfg: Any): List<AppleMarker.Element> {
        if (android.os.Build.VERSION.SDK_INT < 33) return emptyList()
        val list = runCatching { cfg.javaClass.getMethod("getVendorElements").invoke(cfg) as List<*> }
            .onFailure { Log.w(TAG, "hotspot vendor elements unreadable: ${cause(it)}") }.getOrNull().orEmpty()
        return list.filterIsInstance<ScanResult.InformationElement>().map { e ->
            AppleMarker.Element(e.id, ByteArray(e.bytes.remaining()).also { e.bytes.duplicate().get(it) })
        }
    }

    /** [cfg]'s vendor elements with the Apple marker in or out, as SoftApConfiguration.Builder#setVendorElements takes them. */
    @android.annotation.TargetApi(33)
    fun marked(cfg: Any, on: Boolean): List<ScanResult.InformationElement> =
        AppleMarker.applied(elements(cfg), on).map { ScanResult.InformationElement(it.id, 0, it.body) }

    private fun cause(t: Throwable): Throwable = (t as? InvocationTargetException)?.targetException ?: t
}
