// HermesSIM host. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
package com.keremgok.hermessim.host.wire

import java.util.Base64

/** The version a host that speaks [hostMin]..[hostMax] uses with a peer, or null when they share none. */
fun commonVersion(peerMin: Int, peerMax: Int, hostMin: Int = Wire.VERSION, hostMax: Int = Wire.VERSION): Int? =
    minOf(peerMax, hostMax).takeIf { it >= maxOf(peerMin, hostMin) }

/**
 * The replies the host keeps for a peer that resumes: a request it has answered gets the same reply again and
 * is not run again (SPEC.md "Resume").
 */
class ReplyCache(private val size: Int = 64) {
    private val replies = LinkedHashMap<Int, ByteArray>()

    fun keep(requestId: Int, reply: ByteArray) {
        replies[requestId] = reply
        while (replies.size > size) replies.remove(replies.keys.first())
    }

    fun find(requestId: Int): ByteArray? = replies[requestId]

    fun clear() = replies.clear()
}

/** One peer's session on the host: its id and whether a Hello may go on with it. */
class SessionDesk(private val newId: () -> Int) {
    var id = 0
        private set

    /** True when [resumeId] names the session this desk holds: the counters go on. Else a new session starts. */
    fun open(resumeId: Int?): Boolean {
        if (id != 0 && resumeId == id) return true
        id = generateSequence(newId) { newId() }.first { it != 0 && it != id }
        return false
    }
}

/** Where [EventLog] keeps its numbers and its texts; the app gives one over SharedPreferences. */
interface EventStore {
    fun read(key: String): String?
    fun write(key: String, value: String?)
    fun keys(prefix: String): List<String>
}

/**
 * The seq numbers of texts and calls, kept across restarts, and the text events no peer has stored yet
 * (SPEC.md "Events").
 */
class EventLog(private val store: EventStore) {
    private fun last(kind: String): Long = store.read("$kind.seq")?.toLongOrNull() ?: 0

    private fun next(kind: String): Long = (last(kind) + 1).also { store.write("$kind.seq", it.toString()) }

    val textCursor: Long get() = last(TEXT)
    val callCursor: Long get() = last(CALL)

    fun nextCall(): Long = next(CALL)

    /** The seq of a text the host sends for a peer: it has no event, only statuses. */
    fun nextText(): Long = next(TEXT)

    /** True while no peer has acknowledged the text event [seq]. */
    fun holds(seq: Long): Boolean = store.read("$TEXT.$seq") != null

    /** Keeps a text event until a peer acknowledges it; [body] is given the event's seq and returns its bytes. */
    fun addText(body: (seq: Long) -> ByteArray): Pair<Long, ByteArray> {
        val seq = next(TEXT)
        val bytes = body(seq)
        store.write("$TEXT.$seq", Base64.getEncoder().encodeToString(bytes))
        return seq to bytes
    }

    /** The kept text events above [cursor], oldest first. */
    fun textsAfter(cursor: Long): List<Pair<Long, ByteArray>> = kept()
        .filter { it > cursor }
        .mapNotNull { seq -> store.read("$TEXT.$seq")?.let { seq to Base64.getDecoder().decode(it) } }

    /** A peer stored every text up to [upTo]: they are forgotten. */
    fun ack(upTo: Long) = kept().filter { it <= upTo }.forEach { store.write("$TEXT.$it", null) }

    /** The seq numbers of the kept texts, lowest first. */
    private fun kept(): List<Long> {
        val seqs = java.util.TreeSet<Long>()
        for (key in store.keys("$TEXT.")) key.substringAfter('.').toLongOrNull()?.let(seqs::add)
        return seqs.toList()
    }

    private companion object {
        const val TEXT = "text"
        const val CALL = "call"
    }
}
