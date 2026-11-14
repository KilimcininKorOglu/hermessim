// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * End-of-call audio check: a relayed call that lost part of its audio either way says so in the host log
 * ("call audio poor"), with the numbers that tell where the audio went: the daemon's txdrop, the bridge's backlog drops
 * and slow writes. The daemon counts its side (AUDIO?), the bridge its own here; [judge] decides. Nothing is sent to a
 * server by itself: the line stays in the log.
 *
 * Every relayed call leaves its numbers in the log and in [last], judged poor or not: a diagnostics request from the
 * client carries them (Relay.sendDiagnostics), so the host's side of "the call was choppy" no longer depends on which
 * lines the log excerpt happened to hold (a Galaxy A13's excerpt was the call's last half second, 10 Nov 2026).
 */
object CallAudioCheck {
    /** One call's bridge numbers (AudioBridge, any thread). */
    data class Bridge(val sessions: Long, val framesIn: Long, val framesOut: Long, val writes: Long, val dropped: Long,
                      val slowestMs: Long, val stalls: Long, val bytesOut: Long = 0, val blockedMs: Long = 0,
                      val leanFromIn: Long = -1, val leanFromDropped: Long = 0) {
        /**
         * What going lean ([LeanAudio]) did, for a call's numbers: "no", or the frames dropped before it and after
         * ("after 412 frames: 96 dropped before, 14 of 2600 after"). Pure (LeanAudioTest).
         */
        fun lean(): String = if (leanFromIn < 0) "no" else
            "after $leanFromIn frames: $leanFromDropped dropped before, ${dropped - leanFromDropped} of ${framesIn - leanFromIn} after"
    }

    private val sessions = AtomicLong(); private val framesIn = AtomicLong(); private val framesOut = AtomicLong()
    private val writes = AtomicLong(); private val dropped = AtomicLong(); private val slowest = AtomicLong(); private val stalls = AtomicLong()
    private val bytesOut = AtomicLong(); private val blockedMs = AtomicLong()
    private val leanFromIn = AtomicLong(-1); private val leanFromDropped = AtomicLong()
    @Volatile private var internet = false
    @Volatile private var held = false
    @Volatile private var hotspotAtStart = "off"

    /** The last relayed call's numbers ("what": "ok" or [judge]'s verdict) and when it ended (elapsedRealtime). */
    @Volatile var last: JSONObject? = null; private set
    @Volatile private var lastAt = 0L

    /** What the radios were doing, for a stall's log line and a call's numbers (Daemon.start sets it; any thread). */
    @Volatile var radio: () -> String = { "" }

    /** [last] for a diagnostics report, with how long ago the call ended; null before the first call. */
    fun lastForReport(): JSONObject? =
        last?.let { JSONObject(it.toString()).put("ended_s_ago", (android.os.SystemClock.elapsedRealtime() - lastAt) / 1000) }

    /** A write at least this slow is a stall: the link stopped taking audio. */
    const val STALL_MS = 1000L
    /** A write this slow waited for the link (a frame comes every 10 ms): its time counts as blocked. */
    const val BLOCKED_MS = 40L

    /** A call's audio starts (CALL_START: the daemon's totals restart too). */
    fun begin(hotspot: String) {
        listOf(sessions, framesIn, framesOut, writes, dropped, slowest, stalls, bytesOut, blockedMs).forEach { it.set(0) }
        leanFromIn.set(-1); leanFromDropped.set(0)
        internet = false; held = false; hotspotAtStart = hotspot
    }

    /** The call's Bluetooth audio went lean now ([LeanAudio]); the first time counts. */
    fun leanOn() { if (leanFromIn.compareAndSet(-1, framesIn.get())) leanFromDropped.set(dropped.get()) }

    fun bridgeUp() { sessions.incrementAndGet() }
    fun frameIn() { framesIn.incrementAndGet() }
    fun dropped() { dropped.incrementAndGet() }
    /** One write: its frames, its bytes (each frame with its 2-byte length), how long the socket took it. */
    fun wrote(frames: Int, bytes: Int, ms: Long) {
        framesOut.addAndGet(frames.toLong()); writes.incrementAndGet(); bytesOut.addAndGet(bytes.toLong())
        if (ms >= BLOCKED_MS) blockedMs.addAndGet(ms)
        slowest.accumulateAndGet(ms, ::maxOf)
        if (ms >= STALL_MS) stalls.incrementAndGet()
    }
    fun internetUsed() { internet = true }
    /** A call was put on hold: the client sends nothing then, so its side isn't judged. */
    fun held() { held = true }

    fun bridge() = Bridge(sessions.get(), framesIn.get(), framesOut.get(), writes.get(), dropped.get(), slowest.get(), stalls.get(),
        bytesOut.get(), blockedMs.get(), leanFromIn.get(), leanFromDropped.get())

    /**
     * What went wrong, or null: "both", "to_phone" (caller audio lost on its way to the phone), "from_phone" (the
     * phone's audio missing in the call) or "stall". Pure (CallAudioCheckTest). [d]: the daemon's AUDIO? totals.
     */
    fun judge(seconds: Long, d: Map<String, Long>, b: Bridge, held: Boolean): String? {
        if (seconds < 10) return null
        val toClient = (d["sent"] ?: 0) - (d["noclient"] ?: 0)
        val lostDown = (d["txdrop"] ?: 0) + b.dropped
        val down = (toClient >= 500 && lostDown * 100 >= toClient * 3) || shortCapture(d, held)
        val live = d["live"] ?: 0
        val up = !held && live >= 500 && ((d["plc"] ?: 0) + (d["starved"] ?: 0)) * 100 >= live * 5
        return when {
            down && up -> "both"
            down -> "to_phone"
            up -> "from_phone"
            b.stalls > 0 -> "stall"
            else -> null
        }
    }

    /**
     * The capture handed over less than real time: under 97% of the time its reads took, or read errors for 3% of the
     * frames. That caller audio is gone before the daemon or the bridge could drop it (a Galaxy A13 host's phone got 85
     * of 100 frames a second, 10 Nov 2026). Not judged with a hold in the call (the voice path stops then), nor with
     * under 5 s of capture or a daemon from before these totals.
     */
    private fun shortCapture(d: Map<String, Long>, held: Boolean): Boolean {
        val cap = d["cap"] ?: 0
        val capMs = d["capms"] ?: 0
        if (held || capMs < 5000) return false
        return cap * 1000 <= capMs * 97 || (d["caperr"] ?: 0) * 100 >= cap * 3
    }

    /** The daemon's AUDIO? reply as numbers ("OK call=1 session=1 ... live=2420"). */
    fun totals(reply: String): Map<String, Long> =
        Regex("""\b(\w+)=(-?\d+)""").findAll(reply).associate { it.groupValues[1] to (it.groupValues[2].toLongOrNull() ?: 0) }

    /**
     * A call's numbers: [what] went wrong ("ok": nothing), from [d] (AUDIO?) and the bridge's [b]; [look] is what the
     * Bluetooth stack said right after the call ([RadioLook.look]; null when not asked: no audio went over Bluetooth).
     */
    fun detail(what: String, seconds: Long, d: Map<String, Long>, b: Bridge, reply: String, look: RadioLook.Look? = null): JSONObject {
        val toClient = ((d["sent"] ?: 0) - (d["noclient"] ?: 0)).coerceAtLeast(1)
        val live = (d["live"] ?: 0).coerceAtLeast(1)
        val capMs = d["capms"] ?: 0
        return JSONObject().put("what", what).put("seconds", seconds)
            .put("via", via(b.sessions > 0, internet))
            .put("to_phone_lost_pct", ((d["txdrop"] ?: 0) + b.dropped) * 100 / toClient)
            .put("from_phone_missing_pct", if (held) -1 else ((d["plc"] ?: 0) + (d["starved"] ?: 0)) * 100 / live)
            // The caller's audio the voice path handed over, against the time it took (100 = real time; -1: an older daemon)
            .put("capture_pct", if (capMs >= 1000) (d["cap"] ?: 0) * 1000 / capMs else -1).put("capture_errors", d["caperr"] ?: -1)
            .put("slowest_write_ms", b.slowestMs).put("stalls", b.stalls)
            .put("frames_per_write", if (b.writes > 0) "%.2f".format(java.util.Locale.ROOT, b.framesOut.toDouble() / b.writes) else "-")
            .put("bridges", b.sessions).put("held", held)
            .put("ble_interval_ms", BleLink.lastIntervalMs)
            .radio(look)
            .put("hotspot", hotspotAtStart).put("radio", runCatching { radio() }.getOrDefault(""))
            .put("daemon", reply.removePrefix("OK ").take(340))
            .put("bridge", "in=${b.framesIn} out=${b.framesOut} writes=${b.writes} dropped=${b.dropped}")
            // How big a frame went out on average and how long writes waited for the link in all: a limit in bytes shows
            // in the first (quiet stretches would pass), one in packets doesn't (a Galaxy A13, 11 Nov 2026)
            .put("bytes_per_frame", if (b.framesOut > 0) b.bytesOut / b.framesOut else -1).put("blocked_ms", b.blockedMs)
            // Whether the link sends in small radio packets, and what going lean for it did ([LeanAudio])
            .put("link_small", SmallPackets.text()).put("lean", b.lean())
    }

    /**
     * The link's radio mode (sending/receiving), what was asked and heard about it, the scans sharing the radio, what the
     * chip did with the call's last seconds and what the link agreed on ([HciRing]).
     */
    private fun JSONObject.radio(look: RadioLook.Look?): JSONObject =
        put("phy", LinkRadio.inUse).put("phy_log", LinkRadio.history()).put("bt_scans", look?.scans.orEmpty())
            .put("hci", look?.hci.orEmpty()).put("hci_link", look?.link.orEmpty())

    /**
     * [detail] as words, not JSON: the report's scrubber blanks quoted strings, and a JSON line then read
     * {"<…>":"<…>", …}. The daemon's totals are a few lines before it ("daemon <- AUDIO? -> OK …"). Pure (CallAudioCheckTest).
     */
    fun line(detail: JSONObject): String {
        val what = detail.optString("what")
        return "call audio ${if (what == "ok") "ok" else "poor ($what)"}: " + detail.keys().asSequence()
            .filter { it != "what" && it != "daemon" }
            .joinToString(" ") { k -> detail.opt(k).toString().let { v -> if (' ' in v) "$k=[$v]" else "$k=$v" } }
    }

    private fun via(bluetooth: Boolean, internet: Boolean): String = when {
        bluetooth && internet -> "bluetooth+internet"
        bluetooth -> "bluetooth"
        internet -> "internet"
        else -> "none"
    }

    /** Daemon executor, at the call's end: [reply] is AUDIO?, [seconds] since the call connected, [look] as in [detail]. */
    fun check(reply: String, seconds: Long, look: RadioLook.Look? = null) {
        if (!VoiceGate.fullVoice) return
        val d = totals(reply)
        if (d["live"] == null) return                                      // a daemon from before these totals
        val b = bridge()
        val what = judge(seconds, d, b, held)
        val detail = detail(what ?: "ok", seconds, d, b, reply, look)
        last = detail; lastAt = android.os.SystemClock.elapsedRealtime()
        if (what == null) android.util.Log.i("HermesSimHost", line(detail)) else android.util.Log.w("HermesSimHost", line(detail))
    }
}
