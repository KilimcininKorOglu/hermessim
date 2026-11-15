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
 * This is for that kind of link only: the chip's record must have shown the small packets ([SmallPackets]), and on every
 * other host nothing here runs. On such a host a Bluetooth audio channel is lean from its first frame. Waiting for the
 * link to fall behind (frames dropped from the bridge's backlog) came far too late: Android's Bluetooth takes about 500
 * frames into its own queue before a write ever blocks, so the host saw nothing for the first 12 s of a call while the
 * phone got 58 frames of 100 a second, and the queued audio then had to drain before lean helped (14 Nov 2026).
 *
 * Lean lasts while the call's audio stays on that Bluetooth channel: a move to the internet or a new daemon connection
 * ends it (the daemon lifts the cap then), and the channel asks again when the audio comes back to it. The phone's own
 * rule still moves a fading call to the internet, lean or not.
 */
object LeanAudio {
    /** A frame's Opus bytes when lean. */
    const val FRAME = 20
    /** One write when lean: two frames, each with the stream's 2-byte mark and length and the packet's 2-byte tick. */
    const val WRITE = 2 * (2 + 2 + FRAME)
    /** How long a write waits for its second frame (one comes every 10 ms; a hold must not keep a frame back). */
    const val PAIR_WAIT_MS = 15L
    /** L2CAP's header (4 bytes) and SDU length (2) in front of every write. */
    const val L2CAP_HEADER = 6

    init {
        // Two radio packets hold one write: its L2CAP header and length, then the frames
        check(WRITE + L2CAP_HEADER <= 2 * HciRing.SMALLEST)
    }

    /** adb debug (AUDIO_LEAN --ez always true): every Bluetooth audio channel is lean, on any host. */
    @Volatile var always = false

    /** Why a Bluetooth audio channel is lean, for the log; null: it is not. [small]: [SmallPackets.small]. Pure. */
    fun why(small: Boolean, always: Boolean = this.always): String? = when {
        always -> "lean on every host (debug)"
        small -> "this host's Bluetooth sends in small radio packets"
        else -> null
    }
}
