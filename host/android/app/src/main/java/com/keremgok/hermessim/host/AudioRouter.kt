// HermesSIM host. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import com.keremgok.hermessim.host.AudioPathCore.Via
import com.keremgok.hermessim.wire.AudioReport
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The call's single connection to the daemon's audio socket, shared by the phone's two audio paths (protocol/README.md
 * "Ready path"): the Bluetooth channel ([AudioBridge]) and the internet path ([InternetPath]). The phone's packets
 * from either go to the daemon; the caller's go out on the path the phone's latest packet came on, and the daemon
 * makes them 10 ms (Bluetooth) or 20 ms (internet) long. Neither path stops the other's audio when it comes or goes.
 */
object AudioRouter {
    private const val TAG = "HermesSimHost"
    private const val MAX = 512
    private const val RETRY_MS = 500L

    private val senders = ConcurrentHashMap<Via, (ByteArray, Int) -> Unit>()
    @Volatile private var last: Via? = null
    @Volatile private var active: Via? = null
    private val daemonLock = Any()
    private var daemon: LocalSocket? = null                 // guarded by daemonLock
    private var pump: Thread? = null                         // guarded by this

    /** Caller audio a path's queue had no room for. */
    val droppedSent = AtomicLong()
    /** Caller audio a path's transport refused. */
    val sendErrors = AtomicLong()
    /** Bluetooth stream bytes skipped to find a packet. */
    val skippedBytes = AtomicLong()

    /** The caller's audio goes over the internet right now (a hotspot paused for the call comes back: HotspotPause). */
    val overInternet: Boolean get() = active == Via.INTERNET

    /** A path is ready to carry the caller's audio with [send]. */
    fun attach(via: Via, send: (ByteArray, Int) -> Unit) {
        senders[via] = send
        choose()
        synchronized(this) {
            if (pump != null) return
            pump = Thread(::run, "audio-router").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }
        }
    }

    /** A path is gone; with none left the daemon socket closes. */
    fun detach(via: Via) {
        senders.remove(via)
        choose()
        if (senders.isEmpty()) synchronized(daemonLock) { daemon?.closeNow() }
    }

    /** One packet of the phone's, from [via], to the daemon (SEQPACKET: one write is one packet). */
    fun fromPhone(via: Via, b: ByteArray, off: Int, len: Int) {
        if (last != via) { last = via; choose() }
        synchronized(daemonLock) {
            val out = daemon?.outputStream ?: return
            runCatching { out.write(b, off, len) }
        }
    }

    private var reportedTotals = emptyMap<String, Long>()
    private var reportedLocal = AudioPathCore.Local(0, 0, 0)

    /** The host's AudioReport for the time since the last one: [reply] is the daemon's AUDIO? (null: not answered). */
    @Synchronized
    fun report(reply: String?): AudioReport {
        val totals = reply?.let(CallAudioCheck::totals) ?: reportedTotals
        val local = AudioPathCore.Local(droppedSent.get(), sendErrors.get(), skippedBytes.get())
        val r = AudioPathCore.report(reportedTotals, totals, reportedLocal, local)
        reportedTotals = totals
        reportedLocal = local
        return r
    }

    private fun choose() {
        val now = AudioPathCore.via(last, senders.containsKey(Via.BLUETOOTH), senders.containsKey(Via.INTERNET))
        if (now == active) return
        active = now
        now ?: return
        Log.i(TAG, "call audio to the phone over ${now.name.lowercase()}")
        Daemon.frame(if (now == Via.INTERNET) 20 else 10)
        if (now == Via.INTERNET) CallAudioCheck.internetUsed()
        HotspotPause.transportChanged("call audio over ${now.name.lowercase()}")
    }

    /** While a path is attached: connect to the daemon, hand each caller packet to the active path, connect again if it ends. */
    private fun run() {
        val buf = ByteArray(MAX)
        while (true) {
            synchronized(this) { if (senders.isEmpty()) { pump = null; Log.i(TAG, "call audio router stopped"); return } }
            val s = connect()
            if (s != null) {
                active?.let { Daemon.frame(if (it == Via.INTERNET) 20 else 10) }
                runCatching { forward(s, buf) }
                synchronized(daemonLock) { if (daemon === s) daemon = null }
                s.closeNow()
            }
            if (senders.isNotEmpty()) Thread.sleep(RETRY_MS)
        }
    }

    private fun forward(s: LocalSocket, buf: ByteArray) {
        val din = s.inputStream
        while (senders.isNotEmpty()) {
            val n = din.read(buf)
            if (n <= 0) return
            active?.let { senders[it] }?.invoke(buf, n)
        }
    }

    private fun connect(): LocalSocket? = runCatching {
        LocalSocket(LocalSocket.SOCKET_SEQPACKET).apply {
            connect(LocalSocketAddress("hermessimd-audio", LocalSocketAddress.Namespace.ABSTRACT))
        }
    }.onFailure { Log.w(TAG, "daemon audio socket: $it") }.getOrNull()?.also { s ->
        synchronized(daemonLock) { daemon = s }
        Log.i(TAG, "call audio router up (hermessimd)")
    }
}
