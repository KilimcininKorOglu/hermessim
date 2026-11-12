// HermesSIM host. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
package com.keremgok.hermessim.host.wire

import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * STUN messages (RFC 8489) with the TURN methods and attributes of RFC 8656: encode, decode, the XOR addresses for
 * IPv4 and IPv6, and MESSAGE-INTEGRITY. Pure; vectors: protocol/v2/vectors/stun.json.
 */
object Stun {
    const val MAGIC = 0x2112A442

    const val BINDING = 0x0001
    const val ALLOCATE = 0x0003
    const val REFRESH = 0x0004
    const val SEND = 0x0006
    const val DATA_METHOD = 0x0007
    const val CREATE_PERMISSION = 0x0008
    const val CHANNEL_BIND = 0x0009

    const val INDICATION = 0x0010
    const val SUCCESS = 0x0100
    const val ERROR = 0x0110

    const val USERNAME = 0x0006
    const val INTEGRITY = 0x0008
    const val ERROR_CODE = 0x0009
    const val CHANNEL_NUMBER = 0x000C
    const val LIFETIME = 0x000D
    const val XOR_PEER = 0x0012
    const val DATA = 0x0013
    const val REALM = 0x0014
    const val NONCE = 0x0015
    const val XOR_RELAYED = 0x0016
    const val ADDRESS_FAMILY = 0x0017
    const val TRANSPORT = 0x0019
    const val XOR_MAPPED = 0x0020

    const val HEADER = 20
    private const val INTEGRITY_SIZE = 20
    private const val FAMILY_V4 = 1
    private const val FAMILY_V6 = 2

    /** A decoded message; [integrityAt] is the offset of its MESSAGE-INTEGRITY attribute, -1 without one. */
    class Message(val type: Int, val tid: ByteArray, val attrs: Map<Int, ByteArray>, val integrityAt: Int, val raw: ByteArray) {
        val method: Int get() = type and 0x3EEF
        val kind: Int get() = type and 0x0110

        fun address(attr: Int): InetSocketAddress? = attrs[attr]?.let { xorAddress(it, tid) }

        /** The error code of an error answer, 0 without one. */
        fun error(): Int = attrs[ERROR_CODE]?.takeIf { it.size >= 4 }?.let { (it[2].toInt() and 7) * 100 + (it[3].toInt() and 0xff) } ?: 0
    }

    /** One attribute, ready to go in a message. */
    class Attr(val type: Int, val value: ByteArray)

    /** The message's bytes; with a [key], MESSAGE-INTEGRITY ends it. */
    fun encode(type: Int, tid: ByteArray, attrs: List<Attr>, key: ByteArray? = null): ByteArray {
        val body = attrs.fold(ByteArray(0)) { all, a -> all + attr(a) }
        if (key == null) return header(type, body.size, tid) + body
        val head = header(type, body.size + 4 + INTEGRITY_SIZE, tid)
        return head + body + attr(Attr(INTEGRITY, hmac(key, head + body)))
    }

    fun decode(d: ByteArray, off: Int = 0, len: Int = d.size): Message? {
        if (len < HEADER || (d[off].toInt() and 0xC0) != 0) return null
        val bb = ByteBuffer.wrap(d, off, len)
        val type = bb.short.toInt() and 0xffff
        val size = bb.short.toInt() and 0xffff
        if (bb.int != MAGIC || size % 4 != 0 || HEADER + size > len) return null
        val raw = d.copyOfRange(off, off + HEADER + size)
        val attrs = HashMap<Int, ByteArray>()
        var integrityAt = -1
        var at = HEADER
        while (at + 4 <= raw.size) {
            val t = u16(raw, at)
            val l = u16(raw, at + 2)
            if (at + 4 + l > raw.size) return null
            if (integrityAt < 0) attrs.putIfAbsent(t, raw.copyOfRange(at + 4, at + 4 + l))
            if (t == INTEGRITY && integrityAt < 0) integrityAt = at
            at += 4 + l + (4 - l % 4) % 4
        }
        return Message(type, raw.copyOfRange(8, HEADER), attrs, integrityAt, raw)
    }

    /** MESSAGE-INTEGRITY holds under [key]: HMAC-SHA1 over the message up to it, with the length ending after it. */
    fun integrityOk(m: Message, key: ByteArray): Boolean {
        val at = m.integrityAt
        if (at < 0 || m.raw.size < at + 4 + INTEGRITY_SIZE) return false
        val head = m.raw.copyOf(at)
        head[2] = ((at - HEADER + 4 + INTEGRITY_SIZE) shr 8).toByte()
        head[3] = (at - HEADER + 4 + INTEGRITY_SIZE).toByte()
        return MessageDigest.isEqual(hmac(key, head), m.raw.copyOfRange(at + 4, at + 4 + INTEGRITY_SIZE))
    }

    /** The long-term credential key: MD5(user:realm:password). */
    fun longTermKey(user: String, realm: ByteArray, password: String): ByteArray =
        MessageDigest.getInstance("MD5").digest("$user:${String(realm)}:$password".toByteArray())

    /** An XOR address attribute's value; null for an unknown family or a short value. */
    fun xorAddress(v: ByteArray, tid: ByteArray): InetSocketAddress? {
        val size = when (v.getOrNull(1)?.toInt()) {
            FAMILY_V4 -> 4
            FAMILY_V6 -> 16
            else -> return null
        }
        if (v.size < 4 + size) return null
        val port = u16(v, 2) xor (MAGIC ushr 16)
        val mask = mask(tid)
        val ip = ByteArray(size) { (v[4 + it].toInt() xor mask[it].toInt()).toByte() }
        return InetSocketAddress(InetAddress.getByAddress(ip), port)
    }

    fun xorValue(a: InetSocketAddress, tid: ByteArray): ByteArray {
        val ip = a.address.address
        val mask = mask(tid)
        return ByteBuffer.allocate(4 + ip.size).put(0).put((if (ip.size == 4) FAMILY_V4 else FAMILY_V6).toByte())
            .putShort((a.port xor (MAGIC ushr 16)).toShort())
            .put(ByteArray(ip.size) { (ip[it].toInt() xor mask[it].toInt()).toByte() }).array()
    }

    fun u32(v: Int): ByteArray = ByteBuffer.allocate(4).putInt(v).array()

    private fun mask(tid: ByteArray) = u32(MAGIC) + tid

    private fun u16(d: ByteArray, at: Int) = ByteBuffer.wrap(d, at, 2).short.toInt() and 0xffff

    private fun header(type: Int, len: Int, tid: ByteArray): ByteArray {
        val h = ByteArray(HEADER)
        ByteBuffer.wrap(h).putShort(type.toShort()).putShort(len.toShort()).putInt(MAGIC)
        System.arraycopy(tid, 0, h, 8, tid.size)
        return h
    }

    private fun attr(a: Attr): ByteArray {
        val pad = (4 - a.value.size % 4) % 4
        return ByteBuffer.allocate(4 + a.value.size + pad).putShort(a.type.toShort()).putShort(a.value.size.toShort()).put(a.value).array()
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray = Tunnel.mac("HmacSHA1", key, data)
}
