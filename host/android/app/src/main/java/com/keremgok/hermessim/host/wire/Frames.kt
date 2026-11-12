// HermesSIM host. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
package com.keremgok.hermessim.host.wire

/** The numbers of protocol/v2/SPEC.md "Frames" and "Limits". */
object Wire {
    const val VERSION = 2
    const val DATA = 0
    const val CREDIT = 1
    const val RESET = 2
    const val URGENT = 0
    const val NORMAL = 1
    const val BULK = 2
    const val STREAMS = 3
    const val MAX_BODY = 1024
    const val MAX_MESSAGE = 262_144
    const val FIRST_LIMIT = 4096L
    const val BULK_FROM = 4096
    const val DEAD_MS = 150_000L
}

/** Why a link is reset; [code] is the reason a RESET frame carries. */
enum class WireFault(val code: Int) {
    BAD_HEADER(1), BAD_LENGTH(2), BAD_PACKET(3), LIMIT_PASSED(4), NO_SESSION(5);

    /** The RESET frame that tells the other side of this fault. */
    val frame: Frame get() = Frame(Wire.RESET, byteArrayOf(code.toByte()))
}

class WireException(val fault: WireFault, detail: String) : Exception("${fault.name}: $detail")

class Frame(val type: Int, val body: ByteArray, val more: Boolean = false, val stream: Int = 0) {
    override fun equals(other: Any?): Boolean =
        other is Frame && type == other.type && more == other.more && stream == other.stream && body.contentEquals(other.body)

    override fun hashCode(): Int = 31 * (31 * (31 * type + more.hashCode()) + stream) + body.contentHashCode()

    override fun toString(): String = "Frame(type=$type, more=$more, stream=$stream, ${body.size} bytes)"
}

/** Unsigned LEB128, the number form of frame lengths and of CREDIT and RESET bodies. */
object Leb128 {
    fun encode(value: Long): ByteArray {
        require(value >= 0) { "negative value" }
        val out = java.io.ByteArrayOutputStream(4)
        var rest = value
        do {
            val low = (rest and 0x7F).toInt()
            rest = rest ushr 7
            out.write(if (rest != 0L) low or 0x80 else low)
        } while (rest != 0L)
        return out.toByteArray()
    }

    /** The value at [at] and the index after it, or null when the bytes end inside the number. */
    fun decode(bytes: ByteArray, at: Int, end: Int = bytes.size): Pair<Long, Int>? {
        var value = 0L
        var shift = 0
        var i = at
        while (i < end) {
            val b = bytes[i++].toInt()
            value = value or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return value to i
            shift += 7
            if (shift > 63) throw WireException(WireFault.BAD_LENGTH, "number longer than 9 bytes")
        }
        return null
    }
}

object FrameCodec {
    private const val RESERVED = 0x07

    fun encode(frame: Frame): ByteArray {
        require(frame.body.size <= Wire.MAX_BODY) { "body of ${frame.body.size} bytes" }
        val header = (frame.type shl 6) or ((if (frame.more) 1 else 0) shl 5) or (frame.stream shl 3)
        return byteArrayOf(header.toByte()) + Leb128.encode(frame.body.size.toLong()) + frame.body
    }

    /** The fields of a header byte; throws when the byte is not one SPEC.md allows. */
    fun header(byte: Int): Triple<Int, Boolean, Int> {
        val type = (byte ushr 6) and 3
        val stream = (byte ushr 3) and 3
        if (byte and RESERVED != 0 || type > Wire.RESET || stream >= Wire.STREAMS) {
            throw WireException(WireFault.BAD_HEADER, "header 0x${byte.toString(16)}")
        }
        return Triple(type, byte and 0x20 != 0, stream)
    }

    fun credit(received: Long, limit: Long): Frame = Frame(Wire.CREDIT, Leb128.encode(received) + Leb128.encode(limit))

    /** The two numbers of a CREDIT body: messages received and the byte limit. */
    fun creditOf(frame: Frame): Pair<Long, Long> {
        val first = Leb128.decode(frame.body, 0) ?: throw WireException(WireFault.BAD_PACKET, "short CREDIT")
        val second = Leb128.decode(frame.body, first.second) ?: throw WireException(WireFault.BAD_PACKET, "short CREDIT")
        return first.first to second.first
    }
}

/**
 * Turns the byte stream of one direction into frames. The values of a link are fed in order; a frame may end in
 * a later value. One buffer is reused, so a long message costs no copy per value.
 */
class FrameReader {
    private var buffer = ByteArray(2048)
    private var start = 0
    private var end = 0

    fun feed(bytes: ByteArray): List<Frame> {
        append(bytes)
        val frames = ArrayList<Frame>()
        while (true) frames += next() ?: break
        if (start == end) { start = 0; end = 0 }
        return frames
    }

    fun clear() { start = 0; end = 0 }

    private fun append(bytes: ByteArray) {
        if (end + bytes.size > buffer.size) {
            val kept = end - start
            val target = if (kept + bytes.size > buffer.size) ByteArray(maxOf(buffer.size * 2, kept + bytes.size)) else buffer
            System.arraycopy(buffer, start, target, 0, kept)
            buffer = target; start = 0; end = kept
        }
        System.arraycopy(bytes, 0, buffer, end, bytes.size)
        end += bytes.size
    }

    private fun next(): Frame? {
        if (start == end) return null
        val (type, more, stream) = FrameCodec.header(buffer[start].toInt() and 0xFF)
        val (length, bodyAt) = Leb128.decode(buffer, start + 1, end) ?: return null
        if (length > Wire.MAX_BODY) throw WireException(WireFault.BAD_LENGTH, "body of $length bytes")
        val bodyEnd = bodyAt + length.toInt()
        if (bodyEnd > end) return null
        val frame = Frame(type, buffer.copyOfRange(bodyAt, bodyEnd), more, stream)
        start = bodyEnd
        return frame
    }
}
