// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

/**
 * Call audio for a Bluetooth link that carries very little: the caller's voice at a lower bit rate, two frames a write.
 *
 * A Galaxy A13 host's chip sends in the smallest radio packets, 27 bytes, and in a bad stretch its link passes one write
 * and at most two such packets per connection slot: about 62 writes a second whether a write is short or long, and a pair
 * of normal frames (three packets) takes two slots. The phone got 59 to 72% of the caller. What fits is 20 ms of audio
 * in two radio packets: each frame capped at [FRAME] Opus bytes by the daemon (FRAME_MAX, 16 kbit/s, still wideband),
 * two of them a write ([WRITE] bytes), 50 writes a second.
 *
 * This is for that kind of link only, and only when it struggles:
 *  - the chip's record must have shown the small packets ([SmallPackets]). On every other host a link that
 *    stops taking audio means the phone is leaving Bluetooth range, and nothing here runs;
 *  - the internet goes first. With an internet audio path ready for the call, the phone moves a fading call there by
 *    itself within seconds; the host then waits [PATIENCE_S] of trouble before it goes lean.
 * Lean lasts while the call's audio stays on that Bluetooth channel: a move to the internet or a new daemon connection
 * ends it (the daemon lifts the cap then), and the next call starts normal.
 */
object LeanAudio {
    /** A frame's Opus bytes when lean. */
    const val FRAME = 20
    /** One write when lean: two frames, each with the stream's 2-byte mark and length and the packet's 2-byte tick. */
    const val WRITE = 2 * (2 + 2 + FRAME)
    /** How long a write waits for its second frame (one comes every 10 ms; a hold must not keep a frame back). */
    const val PAIR_WAIT_MS = 15L

    /** A second in which this many frames fell out of the bridge's backlog is a troubled one (50 ms of the caller gone). */
    const val TROUBLED = 5
    /** Trouble is two troubled seconds within this many. */
    const val WINDOW_S = 6
    /** With an internet path ready: how long the trouble must have lasted, the phone's move to the internet coming first. */
    const val PATIENCE_S = 5
    /** L2CAP's header (4 bytes) and SDU length (2) in front of every write. */
    const val L2CAP_HEADER = 6

    init {
        // Two radio packets hold one write: its L2CAP header and length, then the frames
        check(WRITE + L2CAP_HEADER <= 2 * HciRing.SMALLEST)
    }

    /** adb debug (AUDIO_LEAN --ez always true): every Bluetooth audio channel goes lean from its start, on any host. */
    @Volatile var always = false

    /** Why a channel goes lean now, or null: [troubled] is [Watch]'s verdict, [small] the link's packets. Pure. */
    fun why(troubled: Boolean, small: Boolean): String? = when {
        always -> "lean from the start (debug)"
        troubled && small -> "this link sends in small radio packets and isn't keeping up"
        else -> null
    }

    /**
     * When a Bluetooth audio channel goes lean: fed once a second with the frames dropped in it, true once trouble holds.
     * `patient`: an internet audio path is ready, so the phone's own move comes first. Pure (LeanAudioTest).
     */
    class Watch {
        private val recent = ArrayDeque<Boolean>()
        private var second = 0
        private var since = -1                       // the second this trouble began; -1 when the window is clear

        fun second(dropped: Int, patient: Boolean): Boolean {
            second++
            val troubled = dropped >= TROUBLED
            recent.addLast(troubled)
            if (recent.size > WINDOW_S) recent.removeFirst()
            if (recent.none { it }) since = -1
            if (!troubled) return false
            if (since < 0) since = second
            return recent.count { it } >= 2 && (!patient || second - since >= PATIENCE_S)
        }
    }
}
