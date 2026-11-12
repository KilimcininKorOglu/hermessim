// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import com.google.protobuf.ByteString
import com.keremgok.hermessim.protocol.v1.Chunk

/**
 * Protocol 2 chunked envelopes (protocol/README.md "Framing"): an envelope too big for one frame
 * travels as slices of its bytes, each in an envelope of its own.
 */
object Chunks {
    /** Slice bytes per chunk: with the chunk's own fields the envelope stays under a 4096-byte frame. */
    const val SLICE = 4000
    const val MIN_PROTOCOL = 2

    fun split(bytes: ByteArray, transferId: Int, slice: Int = SLICE): List<Chunk> {
        val count = (bytes.size + slice - 1) / slice
        return (0 until count).map { i ->
            val from = i * slice
            Chunk.newBuilder()
                .setTransferId(transferId)
                .setIndex(i + 1)
                .setCount(count)
                .setData(ByteString.copyFrom(bytes, from, minOf(slice, bytes.size - from)))
                .build()
        }
    }
}

/**
 * Joins the chunks of one peer's transfers. A transfer holds at most [maxParts] slices, at most
 * [maxOpen] are open at once, and one not complete within [ttlMs] is dropped. Not thread-safe:
 * the relay calls it on main.
 */
class ChunkJoiner(
    private val now: () -> Long,
    private val maxParts: Int = 64,
    private val maxOpen: Int = 4,
    private val ttlMs: Long = 30_000L,
) {
    private class Transfer(val count: Int, val startedAt: Long) {
        val parts = arrayOfNulls<ByteString>(count)
        var have = 0
    }

    private val open = LinkedHashMap<Int, Transfer>()

    /** Why the last chunk was refused, for the log; null when it was taken. */
    var refused: String? = null
        private set

    /** The whole envelope's bytes once [chunk] completes its transfer, else null. */
    fun add(chunk: Chunk): ByteArray? {
        refused = null
        expire()
        val transfer = transferFor(chunk) ?: return null
        val slot = chunk.index - 1
        if (transfer.parts[slot] == null) transfer.have += 1
        transfer.parts[slot] = chunk.data
        if (transfer.have < transfer.count) return null
        open.remove(chunk.transferId)
        return transfer.parts.fold(ByteString.EMPTY) { all, part -> all.concat(part) }.toByteArray()
    }

    fun clear() = open.clear()

    private fun transferFor(chunk: Chunk): Transfer? {
        if (chunk.count !in 1..maxParts || chunk.index !in 1..chunk.count) {
            refused = "chunk ${chunk.index}/${chunk.count} out of range"
            return null
        }
        val known = open[chunk.transferId]
        if (known != null && known.count == chunk.count) return known
        while (open.size >= maxOpen) open.remove(open.keys.first())
        return Transfer(chunk.count, now()).also { open[chunk.transferId] = it }
    }

    private fun expire() {
        val cutoff = now() - ttlMs
        open.entries.removeAll { it.value.startedAt < cutoff }
    }
}
