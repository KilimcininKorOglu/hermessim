// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import java.util.UUID

/**
 * ATT prepared writes (a value longer than one write, sent in parts and then executed): each device's parts of one
 * characteristic's value, in order, at most [max] bytes, until the execute request. Pure.
 */
class PreparedWrites(private val max: Int = MAX_BYTES) {
    companion object { const val MAX_BYTES = 1024 }

    private val parts = HashMap<String, Pair<UUID, ByteArray>>()

    /** Adds a part; false, and the device's parts dropped, when it is not the next part of one value or the value grows too long. */
    fun add(device: String, uuid: UUID, offset: Int, value: ByteArray): Boolean {
        val (target, sofar) = parts[device] ?: (uuid to ByteArray(0))
        if (target != uuid || offset != sofar.size || sofar.size + value.size > max) {
            parts.remove(device)
            return false
        }
        parts[device] = uuid to sofar + value
        return true
    }

    /** The whole value when [run] (null when the write was cancelled or nothing was collected); the parts are gone after. */
    fun execute(device: String, run: Boolean): Pair<UUID, ByteArray>? = parts.remove(device)?.takeIf { run }

    fun drop(device: String) {
        parts.remove(device)
    }
}
