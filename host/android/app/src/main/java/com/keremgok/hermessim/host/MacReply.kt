// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import com.keremgok.hermessim.protocol.v1.MacReply

/**
 * A Mac's reply (protocol/README.md "Mac", Reply): it answers only a text the Mac can still be shown, so a Mac never
 * texts a number of its own choosing; the reply goes out to that text's sender on the line it came in on. Pure.
 */
object MacReplyCore {
    const val MAX_BODY_BYTES = 500

    data class Reply(val to: String, val slot: Int, val body: String)

    /** The text to send for the written [value], or null when it is refused. */
    fun reply(value: ByteArray, shelf: MacTextShelf): Reply? {
        val reply = runCatching { MacReply.parseFrom(value) }.getOrNull() ?: return null
        val body = reply.body.trim()
        if (body.isEmpty() || body.toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES) return null
        val (to, slot) = shelf.origin(reply.textId) ?: return null
        return Reply(to, slot, body).takeIf { to.isNotBlank() }
    }
}
