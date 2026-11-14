// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Caller audio waiting for the Bluetooth link ([AudioBridge]): the router adds packets, the sender takes them in batches. */
internal class AudioBacklog {
    private companion object {
        const val SIZE = 10                       // 100 ms of caller audio waiting for the link, then the oldest goes
    }

    private val q = ArrayDeque<ByteArray>()
    private val lock = ReentrantLock()
    private val ready = lock.newCondition()
    private val st = IntArray(7)                 // frames in, frames out, writes, dropped, slowest write (ms), bytes out, ms blocked
    private var nextLog = SystemClock.elapsedRealtime() + 5000

    /** Lean ([LeanAudio]): two frames a write, never more than two radio packets' worth. */
    @Volatile var lean = false

    /** Queue one packet; true if the oldest had to go. */
    fun add(frame: ByteArray): Boolean = lock.withLock {
        q.addLast(frame); st[0]++; CallAudioCheck.frameIn()
        var dropped = false
        while (q.size > SIZE) { q.removeFirst(); st[3]++; dropped = true; CallAudioCheck.dropped() }
        ready.signal()
        dropped
    }

    /**
     * Wait for packets, then copy as many as fit in [max] bytes (at least one) into [buf]: (bytes, packets). Lean, a write
     * waits a little for its second frame and holds [LeanAudio.WRITE] bytes; a frame from before the cap is larger and
     * goes alone.
     */
    fun take(buf: ByteArray, max: Int, alive: AtomicBoolean): Pair<Int, Int> = lock.withLock {
        while (q.isEmpty() && alive.get()) ready.await(200, TimeUnit.MILLISECONDS)
        val small = lean
        if (small) awaitPair(alive)
        val room = if (small) LeanAudio.WRITE else max
        var n = 0; var frames = 0
        while (q.isNotEmpty() && (n == 0 || n + q.first().size <= room)) {
            val f = q.removeFirst(); System.arraycopy(f, 0, buf, n, f.size); n += f.size; frames++
        }
        n to frames
    }

    /** Under the lock: wait up to [LeanAudio.PAIR_WAIT_MS] for a second frame (one comes every 10 ms). */
    private fun awaitPair(alive: AtomicBoolean) {
        var left = TimeUnit.MILLISECONDS.toNanos(LeanAudio.PAIR_WAIT_MS)
        while (q.size < 2 && alive.get() && left > 0) left = ready.awaitNanos(left)
    }

    /** Wake a sender waiting in [take] (the bridge is closing). */
    fun wake() = lock.withLock { ready.signalAll() }

    /**
     * Count one write of [bytes] that took [ms]. Every 5 s, the window's numbers if the link lagged (packets shared a
     * write, a drop, a slow write), else null: healthy calls already log every 5 s in the daemon's line.
     */
    fun wrote(frames: Int, bytes: Int, ms: Int, now: Long): String? = lock.withLock {
        st[1] += frames; st[2]++; if (ms > st[4]) st[4] = ms
        st[5] += bytes; if (ms >= CallAudioCheck.BLOCKED_MS) st[6] += ms
        CallAudioCheck.wrote(frames, bytes, ms.toLong())
        if (now <= nextLog) return@withLock null
        nextLog = now + 5000
        val lagged = st[3] > 0 || st[2] < st[1] || st[4] >= 40
        val line = if (lagged) "frames=${st[1]}/${st[0]} writes=${st[2]} dropped=${st[3]} slowest write=${st[4]} ms " +
            "bytes=${st[5]} blocked=${st[6]} ms" else null
        st.fill(0)
        line
    }
}

/**
 * Asks the daemon for lean frames once a Bluetooth channel's link needs them ([LeanAudio]); fed every caller frame on the
 * router thread. When the daemon drops the cap by itself ([AudioRouter.capEpoch]: the call moved to the internet, or a
 * new daemon connection), the channel is no longer lean, and new trouble asks again.
 */
internal class LeanAsk(private val backlog: AudioBacklog, private val alive: AtomicBoolean) {
    private companion object {
        const val TAG = "HermesSimHost"
    }

    private var watch = LeanAudio.Watch()
    private var secondFrom = SystemClock.elapsedRealtime()
    private var dropped = 0
    private var epoch = AudioRouter.capEpoch
    private var pending = false

    /** The daemon was asked on this channel: the cap is lifted when the channel ends. */
    @Volatile var asked = false; private set

    fun frame(drop: Boolean, now: Long) {
        if (AudioRouter.capEpoch != epoch) restart()
        if (drop) dropped++
        if (now - secondFrom < 1000) return
        val troubled = watch.second(dropped, AudioRouter.internetReady)
        secondFrom = now; dropped = 0
        if (pending) return
        LeanAudio.why(troubled, SmallPackets.small)?.let(::ask)
    }

    private fun restart() {
        epoch = AudioRouter.capEpoch
        if (pending) Log.i(TAG, "audio to the phone: the daemon dropped the frame cap, no longer lean")
        backlog.lean = false; pending = false; watch = LeanAudio.Watch()
    }

    private fun ask(why: String) {
        pending = true; asked = true
        val at = epoch
        Log.w(TAG, "audio to the phone: $why: asking the daemon for ${LeanAudio.FRAME}-byte frames, two a write")
        Daemon.frameMax(LeanAudio.FRAME) { ok ->
            if (!ok) Log.w(TAG, "audio to the phone: the daemon doesn't cap frames, staying as it is")
            if (ok && alive.get() && AudioRouter.capEpoch == at) { backlog.lean = true; CallAudioCheck.leanOn() }
        }
    }
}
