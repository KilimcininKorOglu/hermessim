// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.net.LocalSocket
import android.os.SystemClock
import android.util.Log
import com.keremgok.hermessim.host.AudioPathCore.Via
import com.keremgok.hermessim.host.wire.AudioPacket
import com.keremgok.hermessim.host.wire.AudioStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Closes a daemon audio socket so both ends notice at once. close() alone leaves the socket open while one of our
 * threads is blocked reading it (the read holds it): after a Bluetooth call the daemon kept the dead client until the
 * next call's first frame woke that read (1 Nov 2026). shutdown wakes our reader and tells the daemon now.
 */
internal fun LocalSocket.closeNow() {
    runCatching { shutdownInput() }
    runCatching { shutdownOutput() }
    runCatching { close() }
}

/**
 * The call's Bluetooth audio path: a BLE L2CAP CoC channel whose PSM the host names in Welcome.audio_psm. The stream
 * carries `[0xA5][length][packet]` both ways (protocol/README.md "Call audio"); the packets go to and come from
 * [AudioRouter], which holds the daemon's audio socket.
 */
@SuppressLint("MissingPermission")
class AudioBridge(private val adapter: BluetoothAdapter, private val ble: BleLink) {
    companion object {
        private const val TAG = "HermesSimHost"
        private const val READ = 1024
        private const val BACKLOG = 10            // 100 ms of caller audio waiting for the link, then the oldest goes
    }

    private var server: BluetoothServerSocket? = null
    @Volatile private var acceptThread: Thread? = null

    fun start() {
        val t = Thread({ acceptLoop() }, "audio-accept")
        acceptThread = t
        t.isDaemon = true
        t.start()
    }

    private fun acceptLoop() {
        var fails = 0
        while (acceptThread != null) {
            if (!adapter.isEnabled) { Thread.sleep(2000); continue }   // radio off: wait quietly (BleLink rebuilds on STATE_ON)
            if (!ble.allowed()) { Thread.sleep(2000); continue }       // no Bluetooth permission yet: self-repair grants it
            val srv = try {
                adapter.listenUsingL2capChannel()      // secure: needs the bonded, encrypted link
            } catch (e: Exception) {
                // A controller without LE credit-based channels never succeeds: back off to a minute, report once
                fails++
                if (fails == 3) Diag.event("audio_channel_failed", org.json.JSONObject().put("what", e.javaClass.simpleName), withLog = false)
                if (fails <= 3 || fails % 30 == 0) Log.e(TAG, "L2CAP listen failed ($fails): $e")
                Thread.sleep(minOf(60_000L, 2_000L shl minOf(fails - 1, 5))); continue
            }
            fails = 0
            server = srv
            ble.audioPsm = srv.psm
            Log.i(TAG, "audio L2CAP listening psm=${srv.psm}")
            try {
                while (acceptThread != null) {
                    val sock = srv.accept()             // one phone audio connection at a time
                    Log.i(TAG, "audio L2CAP connected ${sock.remoteDevice?.address}")
                    bridge(sock)
                    Log.i(TAG, "audio L2CAP closed")
                }
            } catch (e: Exception) {
                Log.i(TAG, "audio accept ended: $e")
            } finally {
                runCatching { srv.close() }
            }
        }
    }

    /**
     * Run until the channel closes. Blocks the accept thread for the duration of one call's audio.
     *
     * Caller audio goes into a [Backlog] and every packet waiting goes out in ONE write ([pumpSend]). On Android one
     * write is one L2CAP packet: a link that carries one packet per connection event (11.25 ms) can't take 100
     * single-packet writes a second. The write then blocked and audio was dropped silently: a client got 88 frames a
     * second, steadily, through whole calls (a Mi 11 Pro host with a Meizu client, 6 Nov 2026).
     */
    private fun bridge(l2cap: BluetoothSocket) {
        Log.i(TAG, "audio bridge up (L2CAP <-> router)")
        CallAudioCheck.bridgeUp()
        val alive = AtomicBoolean(true)
        val backlog = Backlog()
        var stallLogged = 0L
        AudioRouter.attach(Via.BLUETOOTH) { packet, n ->
            val framed = ByteArray(n + 2)
            AudioPacket.frame(packet, n, framed, 0)
            if (!backlog.add(framed)) return@attach
            AudioRouter.droppedSent.incrementAndGet()
            // A write that never returns logs nothing itself: say so from here, with what the radios were doing (a
            // client got no caller audio for 15 s while its own audio still reached the host, 6 Nov 2026)
            val now = SystemClock.elapsedRealtime()
            if (now - stallLogged > 5000) {
                stallLogged = now
                Log.w(TAG, "audio to the phone: Bluetooth isn't taking it, dropping the oldest (${runCatching { CallAudioCheck.radio() }.getOrDefault("")})")
            }
        }
        val send = Thread({ pumpSend(l2cap, backlog, alive) }, "audio-send").apply { isDaemon = true; start() }
        try {
            pumpUp(l2cap, alive)
        } catch (_: Exception) {
        } finally {
            alive.set(false)
            AudioRouter.detach(Via.BLUETOOTH)
            backlog.wake()
            runCatching { l2cap.close() }
            send.join(500)
            Log.i(TAG, "audio bridge down")
        }
    }

    /** backlog -> L2CAP: every packet waiting, up to the link's largest packet, in one write. */
    private fun pumpSend(l2cap: BluetoothSocket, backlog: Backlog, alive: AtomicBoolean) {
        val out = l2cap.outputStream
        val most = AudioPacket.MAX + 2
        val maxTx = runCatching { l2cap.maxTransmitPacketSize }.getOrDefault(0).let { if (it in 64..4096) it else most }
        val buf = ByteArray(maxOf(maxTx, most))
        try {
            while (alive.get()) {
                val (n, frames) = backlog.take(buf, maxTx, alive)
                if (n == 0) continue
                val t0 = SystemClock.elapsedRealtime()
                out.write(buf, 0, n)
                backlog.wrote(frames, n, (SystemClock.elapsedRealtime() - t0).toInt(), t0)
                    ?.let { Log.i(TAG, "audio to the phone 5s: $it (max $maxTx B)") }
            }
        } catch (_: Exception) {} finally { alive.set(false) }
    }

    /** L2CAP -> router: the stream as it comes, each whole packet to the daemon. Bad bytes are skipped and counted. */
    private fun pumpUp(l2cap: BluetoothSocket, alive: AtomicBoolean) {
        val din = l2cap.inputStream
        val buf = ByteArray(READ)
        var skipped = 0L
        val stream = AudioStream { b, off, len -> AudioRouter.fromPhone(Via.BLUETOOTH, b, off, len) }
        while (alive.get()) {
            val n = din.read(buf)
            if (n < 0) return
            stream.feed(buf, 0, n)
            if (stream.skipped != skipped) {
                AudioRouter.skippedBytes.addAndGet(stream.skipped - skipped)
                skipped = stream.skipped
            }
        }
    }

    /** Caller audio waiting for the Bluetooth link: the router adds packets, [pumpSend] takes them in batches. */
    private class Backlog {
        private val q = ArrayDeque<ByteArray>()
        private val lock = ReentrantLock()
        private val ready = lock.newCondition()
        private val st = IntArray(7)                 // frames in, frames out, writes, dropped, slowest write (ms), bytes out, ms blocked
        private var nextLog = SystemClock.elapsedRealtime() + 5000

        /** Queue one packet; true if the oldest had to go. */
        fun add(frame: ByteArray): Boolean = lock.withLock {
            q.addLast(frame); st[0]++; CallAudioCheck.frameIn()
            var dropped = false
            while (q.size > BACKLOG) { q.removeFirst(); st[3]++; dropped = true; CallAudioCheck.dropped() }
            ready.signal()
            dropped
        }

        /** Wait for packets, then copy as many as fit in [max] bytes (at least one) into [buf]: (bytes, packets). */
        fun take(buf: ByteArray, max: Int, alive: AtomicBoolean): Pair<Int, Int> = lock.withLock {
            while (q.isEmpty() && alive.get()) ready.await(200, TimeUnit.MILLISECONDS)
            var n = 0; var frames = 0
            while (q.isNotEmpty() && (n == 0 || n + q.first().size <= max)) {
                val f = q.removeFirst(); System.arraycopy(f, 0, buf, n, f.size); n += f.size; frames++
            }
            n to frames
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

    fun stop() {
        acceptThread = null
        runCatching { server?.close() }
    }
}
