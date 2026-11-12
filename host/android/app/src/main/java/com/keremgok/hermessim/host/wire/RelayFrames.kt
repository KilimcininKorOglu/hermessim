// HermesSIM host. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
package com.keremgok.hermessim.host.wire

import java.nio.ByteBuffer

/** One message of the relay socket (protocol/README.md "Relay"): `[u8 type][u8 room slot][body]`. Pure. */
class RelayFrame(val type: Int, val slot: Int, val body: ByteArray) {
    fun bytes(): ByteArray = byteArrayOf(type.toByte(), slot.toByte()) + body

    /** The body as one byte (JOINED, PEER), or -1 when it is not one byte. */
    fun flag(): Int = if (body.size == 1) body[0].toInt() and 0xFF else -1

    /** The first u16 of the body (KICKED, ERROR, PUSH_RESULT), or -1 when it is shorter. */
    fun code(): Int = if (body.size >= 2) ByteBuffer.wrap(body, 0, 2).short.toInt() and 0xFFFF else -1

    /** The result byte of a PUSH_RESULT, or -1. */
    fun pushResult(): Int = if (type == RelayFrames.PUSH_RESULT && body.size == 3) body[2].toInt() and 0xFF else -1

    companion object {
        /** null when the message is shorter than its header. */
        fun parse(message: ByteArray): RelayFrame? =
            if (message.size < 2) null
            else RelayFrame(message[0].toInt() and 0xFF, message[1].toInt() and 0xFF, message.copyOfRange(2, message.size))
    }
}

object RelayFrames {
    const val JOIN = 1
    const val JOINED = 2
    const val PEER = 3
    const val LEAVE = 4
    const val DATA = 5
    const val PUSH = 6
    const val PUSH_RESULT = 7
    const val KICKED = 8
    const val ERROR = 9

    const val ROLE_HOST = 1
    const val ROLE_PHONE = 2

    /** The largest DATA body the server forwards. */
    const val MAX_DATA = 16_448

    /** Push kinds of a PUSH message. */
    val PUSH_KINDS = mapOf("call" to 1, "sms" to 2, "note" to 3, "notice" to 4)

    fun join(slot: Int, role: Int, roomToken: ByteArray): RelayFrame {
        require(roomToken.size == 32) { "a room token has 32 bytes" }
        return RelayFrame(JOIN, slot, byteArrayOf(role.toByte()) + roomToken)
    }

    fun data(slot: Int, body: ByteArray): RelayFrame {
        require(body.size <= MAX_DATA) { "a DATA body has at most $MAX_DATA bytes" }
        return RelayFrame(DATA, slot, body)
    }

    fun push(request: Int, roomToken: ByteArray, kind: Int, collapseId: String, sealed: ByteArray): RelayFrame {
        val collapse = collapseId.toByteArray()
        require(roomToken.size == 32 && collapse.size <= 255) { "bad push fields" }
        val body = ByteBuffer.allocate(2 + 32 + 2 + collapse.size + sealed.size)
            .putShort(request.toShort()).put(roomToken).put(kind.toByte()).put(collapse.size.toByte())
            .put(collapse).put(sealed)
        return RelayFrame(PUSH, 0, body.array())
    }
}
