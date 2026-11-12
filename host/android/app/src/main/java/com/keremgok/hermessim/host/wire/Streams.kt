// HermesSIM host. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
package com.keremgok.hermessim.host.wire

import java.io.ByteArrayOutputStream

/** A message that arrived whole: its stream, its bytes and its number in the direction's count. */
class Arrived(val stream: Int, val packet: ByteArray, val number: Long)

/** Joins the DATA frames of one direction into messages; each stream has at most one message in progress. */
class Assembler {
    private val parts = arrayOfNulls<ByteArrayOutputStream>(Wire.STREAMS)

    /** How many messages arrived whole: the `received` of a CREDIT frame. */
    var received = 0L
        private set

    /** The message [frame] completes, or null while its stream's message goes on. */
    fun add(frame: Frame): Arrived? {
        val part = parts[frame.stream] ?: ByteArrayOutputStream(frame.body.size).also { parts[frame.stream] = it }
        if (part.size() + frame.body.size > Wire.MAX_MESSAGE) {
            throw WireException(WireFault.BAD_LENGTH, "message above ${Wire.MAX_MESSAGE} bytes")
        }
        part.write(frame.body)
        if (frame.more) return null
        parts[frame.stream] = null
        return Arrived(frame.stream, part.toByteArray(), ++received)
    }

    /** Drops the messages in progress (RESET, or a new link). With [recount] the count starts again too. */
    fun clear(recount: Boolean = false) {
        parts.fill(null)
        if (recount) received = 0
    }

    /** A new session began with the message that just arrived: that Hello is its message 1. */
    fun newSession() { received = 1 }
}

/**
 * The messages waiting to be sent, as frames by stream. Urgent frames go first, then normal, then bulk, and a
 * DATA frame leaves only while the other side's byte limit allows it (SPEC.md "CREDIT").
 */
class Outbox(private val maxBody: Int = Wire.MAX_BODY) {
    private class Waiting(val frame: Frame, val last: Boolean, val tag: Any?)

    private val queues = Array(Wire.STREAMS) { ArrayDeque<Waiting>() }
    private val control = ArrayDeque<Frame>()
    private var pending = ByteArray(0)
    private var pendingAt = 0

    /** The other side's limit and how many DATA body bytes this side has sent. */
    var limit = Wire.FIRST_LIMIT
    var sentBytes = 0L
        private set

    /** How many messages left whole: the number of the last one. */
    var sentMessages = 0L
        private set

    /** Called with a message's tag and its number when its last frame leaves. */
    var onSent: (tag: Any?, number: Long) -> Unit = { _, _ -> }

    fun queue(packet: ByteArray, stream: Int, tag: Any? = null) {
        require(packet.size <= Wire.MAX_MESSAGE) { "message of ${packet.size} bytes" }
        val count = maxOf(1, (packet.size + maxBody - 1) / maxBody)
        for (i in 0 until count) {
            val from = i * maxBody
            val body = packet.copyOfRange(from, minOf(from + maxBody, packet.size))
            queues[stream].addLast(Waiting(Frame(Wire.DATA, body, more = i < count - 1, stream = stream), i == count - 1, tag))
        }
    }

    /** A CREDIT or RESET frame: it goes before any DATA frame and needs no credit. */
    fun queueControl(frame: Frame) { control.addLast(frame) }

    fun isEmpty(): Boolean = pendingAt == pending.size && control.isEmpty() && queues.all { it.isEmpty() }

    /** True when frames wait only because the other side's limit is used up. */
    fun blocked(): Boolean = pendingAt == pending.size && control.isEmpty() && nextData(peek = true) == null && queues.any { it.isNotEmpty() }

    /** Up to [max] bytes for one notification or write: whole frames while they fit, else the next piece of one. */
    fun take(max: Int): ByteArray {
        val out = ByteArrayOutputStream(max)
        while (out.size() < max) {
            if (pendingAt == pending.size && !load()) break
            val n = minOf(max - out.size(), pending.size - pendingAt)
            out.write(pending, pendingAt, n)
            pendingAt += n
        }
        return out.toByteArray()
    }

    /** Drops everything waiting (the session ended); the counters start again. */
    fun clear() {
        newLink()
        sentMessages = 0
    }

    /** A new link: what waited for the old one is dropped and the byte totals start again; the message count goes on. */
    fun newLink() {
        queues.forEach { it.clear() }
        control.clear()
        pending = ByteArray(0); pendingAt = 0
        limit = Wire.FIRST_LIMIT; sentBytes = 0
    }

    private fun load(): Boolean {
        val frame = control.removeFirstOrNull() ?: nextData(peek = false)?.frame ?: return false
        pending = FrameCodec.encode(frame)
        pendingAt = 0
        return true
    }

    private fun nextData(peek: Boolean): Waiting? {
        val queue = queues.firstOrNull { q -> q.firstOrNull()?.let { sentBytes + it.frame.body.size <= limit } == true } ?: return null
        if (peek) return queue.first()
        val waiting = queue.removeFirst()
        sentBytes += waiting.frame.body.size
        if (waiting.last) onSent(waiting.tag, ++sentMessages)
        return waiting
    }
}

/** Decides when this side grants the other more bytes (SPEC.md "CREDIT"). */
class Grant(private val window: Long = 16_384, private val quietMs: Long = 200, private val idleMs: Long = 60_000) {
    private var taken = 0L
    private var granted = Wire.FIRST_LIMIT
    private var lastSentAt = 0L
    private var unacked = false

    /** A DATA frame of [bodyBytes] arrived; [whole] when it completed a message. Throws when it passes the limit. */
    fun arrived(bodyBytes: Int, whole: Boolean) {
        taken += bodyBytes
        if (taken > granted) throw WireException(WireFault.LIMIT_PASSED, "$taken bytes of $granted")
        if (whole) unacked = true
    }

    /** True when a CREDIT frame must go now. */
    fun due(now: Long): Boolean {
        val halfUsed = granted - taken <= window / 2
        val quiet = unacked && now - lastSentAt >= quietMs
        return halfUsed || quiet || now - lastSentAt >= idleMs
    }

    /** The CREDIT frame for [received] messages; the limit moves a full window above what was taken. */
    fun frame(received: Long, now: Long): Frame {
        granted = taken + window
        lastSentAt = now
        unacked = false
        return FrameCodec.credit(received, granted)
    }

    fun clear() { taken = 0; granted = Wire.FIRST_LIMIT; unacked = false }
}
