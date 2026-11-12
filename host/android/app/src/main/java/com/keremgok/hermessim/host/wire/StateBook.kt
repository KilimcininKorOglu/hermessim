// HermesSIM host. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
package com.keremgok.hermessim.host.wire

import java.io.ByteArrayOutputStream

/** Protobuf field bytes without the generated classes: enough to cut a message into its fields and join them. */
object Fields {
    private const val VARINT = 0
    private const val FIXED64 = 1
    private const val BYTES = 2
    private const val FIXED32 = 5

    /** A length-delimited field: tag, length, [payload]. */
    fun bytes(field: Int, payload: ByteArray): ByteArray =
        Leb128.encode(((field shl 3) or BYTES).toLong()) + Leb128.encode(payload.size.toLong()) + payload

    fun varint(field: Int, value: Long): ByteArray = Leb128.encode((field shl 3).toLong()) + Leb128.encode(value)

    /** The top-level fields of [message] by number, each with its tag; a repeated field's parts stay together. */
    fun split(message: ByteArray): Map<Int, ByteArray> {
        val out = LinkedHashMap<Int, ByteArrayOutputStream>()
        var at = 0
        while (at < message.size) {
            val (tag, valueAt) = Leb128.decode(message, at) ?: throw WireException(WireFault.BAD_PACKET, "cut tag")
            val end = valueEnd(message, valueAt, (tag and 7).toInt())
            if (end > message.size) throw WireException(WireFault.BAD_PACKET, "cut field")
            out.getOrPut((tag ushr 3).toInt()) { ByteArrayOutputStream() }.write(message, at, end - at)
            at = end
        }
        return out.mapValues { it.value.toByteArray() }
    }

    private fun valueEnd(message: ByteArray, at: Int, wireType: Int): Int = when (wireType) {
        VARINT -> (Leb128.decode(message, at) ?: throw WireException(WireFault.BAD_PACKET, "cut number")).second
        FIXED64 -> at + 8
        FIXED32 -> at + 4
        BYTES -> (Leb128.decode(message, at) ?: throw WireException(WireFault.BAD_PACKET, "cut length")).let { it.second + it.first.toInt() }
        else -> throw WireException(WireFault.BAD_PACKET, "wire type $wireType")
    }
}

/**
 * The host's state by group (the fields of `State`, SPEC.md "State"). It takes each group's latest bytes and
 * gives the `State` messages to send: only what changed, with the next seq.
 *
 * A group in [byField] is compared field by field, and only its changed fields are sent (`Power`).
 */
class StateBook(val epoch: Int, private val byField: Set<Int> = setOf(POWER)) {
    private val groups = HashMap<Int, Map<Int, ByteArray>>()     // group -> its fields (one entry 0 when whole)
    private val changedAt = HashMap<Int, Int>()                  // group -> seq of the State with its last change
    private val dirty = LinkedHashMap<Int, LinkedHashMap<Int, ByteArray>>()

    /** The seq of the last `State` that [next] gave; 0 before the first. */
    var seq = 0
        private set

    /** Takes a group's current bytes; true when something in it changed. */
    fun put(group: Int, bytes: ByteArray): Boolean {
        val fresh = if (group in byField) Fields.split(bytes) else mapOf(0 to bytes)
        val known = groups[group].orEmpty()
        val changed = fresh.filter { (field, value) -> !value.contentEquals(known[field]) }
        if (changed.isEmpty() && group in groups) return false
        groups[group] = known + fresh
        dirty.getOrPut(group) { LinkedHashMap() }.putAll(changed)
        return true
    }

    /** The `State` for every peer with the changes since the last call, or null when nothing changed. */
    fun next(): ByteArray? {
        if (dirty.isEmpty()) return null
        seq++
        val out = ByteArrayOutputStream()
        out.write(Fields.varint(SEQ, seq.toLong()))
        for ((group, fields) in dirty.toSortedMap()) {
            changedAt[group] = seq
            out.write(Fields.bytes(group, join(fields)))
        }
        dirty.clear()
        return out.toByteArray()
    }

    /**
     * The `State` that brings one peer up to date after its Hello: every group when its mark is of another
     * epoch, else the groups that changed after the mark. It carries the current seq and is the peer's new base.
     */
    fun since(markEpoch: Int?, markSeq: Int): ByteArray {
        val all = markEpoch != epoch
        return whole(groups.keys.filter { all || (changedAt[it] ?: 0) > markSeq })
    }

    /** The named groups whole, with the current seq (`GetState`); every group when [wanted] is empty. */
    fun whole(wanted: Collection<Int>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(Fields.varint(SEQ, seq.toLong()))
        val named = if (wanted.isEmpty()) groups.keys else wanted
        for (group in named.sorted()) groups[group]?.let { out.write(Fields.bytes(group, join(it))) }
        return out.toByteArray()
    }

    private fun join(fields: Map<Int, ByteArray>): ByteArray =
        fields.toSortedMap().values.fold(ByteArrayOutputStream()) { out, part -> out.apply { write(part) } }.toByteArray()

    companion object {
        const val SEQ = 1
        const val POWER = 2
        const val NETWORK = 3
        const val LINES = 4
        const val SIGNAL = 5
        const val HOTSPOT = 6
        const val HOTSPOT_CLIENTS = 7
        const val WIFI = 8
        const val UPDATE = 9
        const val RULES = 10
        const val VOICE = 11
        const val REMOTE = 12
        const val PEERS = 13
        const val HOTSPOT_LIMITS = 14
        const val HOTSPOT_USAGE = 15
    }
}
