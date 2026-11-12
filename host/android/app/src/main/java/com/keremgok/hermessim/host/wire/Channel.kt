// HermesSIM host. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
package com.keremgok.hermessim.host.wire

import com.keremgok.hermessim.wire.Packet
import com.keremgok.hermessim.wire.Request

/** Which stream a message travels on (SPEC.md "Which stream"). */
object StreamChoice {
    private val urgentKinds = setOf(
        Packet.KindCase.HELLO, Packet.KindCase.WELCOME, Packet.KindCase.REFUSE, Packet.KindCase.BYE,
        Packet.KindCase.PAIR_START, Packet.KindCase.PAIR_OFFER, Packet.KindCase.PAIR_PROOF,
    )
    private val callOps = setOf(Request.OpCase.DIAL, Request.OpCase.CALL_CONTROL, Request.OpCase.SEND_TONES)

    /** [answers] is the request a Reply answers; a Reply alone does not say what it is about. */
    fun of(packet: Packet, size: Int, answers: Request.OpCase? = null): Int = when {
        packet.kindCase in urgentKinds || answers in callOps -> Wire.URGENT
        packet.hasEvent() && packet.event.hasCall() -> Wire.URGENT
        packet.hasReply() && packet.reply.hasLog() -> Wire.BULK
        size > Wire.BULK_FROM -> Wire.BULK
        else -> Wire.NORMAL
    }
}

/**
 * The host's end of one peer's control channel: the bytes of a link in, whole messages out, and the frames to
 * send with their credit. A session outlives its links; [newLink] starts each one. Not thread-safe.
 */
class Channel(private val clock: () -> Long, newSessionId: () -> Int) {
    private val reader = FrameReader()
    private val assembler = Assembler()
    private val outbox = Outbox()
    private val grant = Grant()
    private val desk = SessionDesk(newSessionId)

    /** The replies of this session, for a request that comes again after a resume. */
    val replies = ReplyCache()

    /** When the last frame of the peer arrived, by [clock]; a link silent for [Wire.DEAD_MS] is dead. */
    var heardAt = 0L
        private set

    /** True from an accepted Hello until the link ends. */
    var serving = false
        private set

    val sessionId: Int get() = desk.id

    /** How many of the peer's messages arrived whole: `Welcome.received` of a resumed session. */
    val received: Long get() = assembler.received

    /** A link came up: nothing of the last one is left, and the byte totals start again. */
    fun newLink() {
        reader.clear()
        assembler.clear()
        outbox.newLink()
        grant.clear()
        serving = false
        heardAt = clock()
    }

    /**
     * Takes bytes of the link and gives the messages they complete. Throws [WireException] for bytes outside the
     * rules; the caller then calls [reset] with its fault.
     */
    fun feed(bytes: ByteArray): List<Arrived> {
        heardAt = clock()
        return reader.feed(bytes).mapNotNull(::take)
    }

    private fun take(frame: Frame): Arrived? = when (frame.type) {
        Wire.DATA -> assembler.add(frame).also { grant.arrived(frame.body.size, whole = it != null) }
        Wire.CREDIT -> null.also { outbox.limit = maxOf(outbox.limit, FrameCodec.creditOf(frame).second) }
        else -> null.also { assembler.clear(); serving = false }       // RESET: the peer says Hello again
    }

    /**
     * A Hello was accepted. True when it goes on with the session [resumeId] names; else a new session starts,
     * with that Hello as its first message and nothing kept from the one before.
     */
    fun open(resumeId: Int?): Boolean {
        val resumed = desk.open(resumeId)
        if (!resumed) {
            assembler.newSession()
            outbox.clear()
            replies.clear()
        }
        serving = true
        return resumed
    }

    /** The peer is not served any more (it was refused, forgotten, or the session's faults ended it). */
    fun close() { serving = false }

    fun send(packet: ByteArray, stream: Int) = outbox.queue(packet, stream)

    /** Tells the peer of a fault (or that the host holds no session); the messages in progress are dropped. */
    fun reset(fault: WireFault) {
        reader.clear()
        assembler.clear()
        outbox.newLink()
        outbox.queueControl(fault.frame)
        serving = false
    }

    /** True when [next] has something to give now: frames that may leave, or a CREDIT that is due. */
    fun ready(): Boolean = grant.due(clock()) || !(outbox.isEmpty() || outbox.blocked())

    /** Up to [max] bytes for one notification or relay message; empty when nothing may leave now. */
    fun next(max: Int): ByteArray {
        val now = clock()
        if (grant.due(now)) outbox.queueControl(grant.frame(assembler.received, now))
        return outbox.take(max)
    }

    /** True when no frame of the peer came for [Wire.DEAD_MS]. */
    fun silent(): Boolean = clock() - heardAt >= Wire.DEAD_MS
}
