// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import com.keremgok.hermessim.host.HciRing.Rec

/**
 * The record file of the chip's traffic, read into [HciRing]'s records.
 *
 * Android writes a btsnoop file: big-endian record headers, each packet led by its kind byte. A Galaxy A13 writes a file
 * with the right name and header to Samsung's folder, and what follows the header is not Android's layout (11 Nov 2026).
 * What its first bytes showed that evening: the entries of Android 12's memory record ([old]), not compressed, straight
 * after the header. The reader also tries what else a phone maker could have done: Android's records starting later or
 * with their numbers little-endian, or the whole record compressed, in either layout. When nothing fits, [probe] says
 * what the bytes look like.
 *
 * Everything here is pure (HciFileTest).
 */
object HciFile {
    /** The file's records and how they were found ("" = in Android's own layout); none when nothing fits. */
    fun read(file: ByteArray): Pair<List<Rec>, String> {
        if (!isBtsnoop(file)) return emptyList<Rec>() to ""
        chain(file, 16, false).let { if (it.isNotEmpty()) return it to "" }
        old(file, 16)?.let { return it }
        resync(file, 16)?.let { return it }
        for ((at, plain) in inflated(file)) unpacked(file, at, plain)?.let { return it }
        return emptyList<Rec>() to ""
    }

    /** A btsnoop file of serial-line (H4) packets. */
    private fun isBtsnoop(file: ByteArray) =
        file.size >= 16 && String(file, 0, 7, Charsets.US_ASCII) == "btsnoop" && be32(file, 12) == 1002L

    /** The records in a stream unpacked from [at]: either layout, or Android 12's own one. */
    private fun unpacked(file: ByteArray, at: Int, plain: ByteArray): Pair<List<Rec>, String>? {
        chain(plain, 0, false).let { if (it.size >= 8) return it to "compressed from $at" }
        resync(plain, 0)?.let { return it.first to "compressed from $at, ${it.second}" }
        return old(plain, 0)?.let { it.first to "compressed from $at, ${it.second}" }
    }

    // ---- btsnoop records

    /** A record's time is microseconds since the year 0: its top two bytes are these from 2024 to 2042. */
    private fun timely(t: Long) = (t ushr 48) == 0xe3L || (t ushr 48) == 0xe4L

    private fun plausible(orig: Long, incl: Long, flags: Long, t: Long) =
        incl in 1..orig && orig <= 70_000 && flags <= 3 && timely(t)

    /**
     * Records one after another from [from], as far as they hold; [le]: the headers' numbers are little-endian. Nothing
     * when the first one already isn't a record (a length that can't be, a time from another age).
     */
    private fun chain(b: ByteArray, from: Int, le: Boolean): List<Rec> {
        val out = ArrayList<Rec>()
        var o = from
        while (o + 24 <= b.size) {
            val (rec, next) = recordAt(b, o, le) ?: break
            out += rec; o = next
        }
        return out
    }

    /** The record whose header starts at [o], and where the next one starts; null when it isn't one or is cut off. */
    private fun recordAt(b: ByteArray, o: Int, le: Boolean): Pair<Rec, Int>? {
        fun u32(at: Int) = if (le) le32(b, at) else be32(b, at)
        val orig = u32(o); val incl = u32(o + 4); val flags = u32(o + 8)
        val t = if (le) le64(b, o + 16) else (be32(b, o + 16) shl 32) or be32(b, o + 20)
        if (!plausible(orig, incl, flags, t)) return null
        val end = o + 24 + incl.toInt()
        if (end > b.size) return null                                       // cut off mid-record: what came before stands
        return Rec(t, flags and 1L == 1L, b[o + 24].toInt() and 0xff, (orig - 1).toInt(), b.copyOfRange(o + 25, end)) to end
    }

    /** The first place within 8 KiB after [from] where eight records in a row start, either byte order. */
    private fun resync(b: ByteArray, from: Int): Pair<List<Rec>, String>? {
        for (le in booleanArrayOf(false, true)) for (o in from until minOf(b.size - 24, from + 8192)) {
            if (o == from && !le) continue
            val r = chain(b, o, le)
            if (r.size >= 8) return r to "records from $o${if (le) ", little-endian" else ""}"
        }
        return null
    }

    // ---- compressed records

    /** Every compressed stream that starts in the first bytes after the header and unpacks to something: (where, what). */
    private fun inflated(file: ByteArray): List<Pair<Int, ByteArray>> {
        val out = ArrayList<Pair<Int, ByteArray>>()
        for (at in 16 until minOf(file.size - 2, 96)) {
            val gzip = le16(file, at) == 0x8b1f
            if (!gzip && !isZlib(file, at)) continue
            val plain = unpack(file, at, gzip)
            if (plain.size >= 64) out += at to plain
        }
        return out
    }

    private fun isZlib(b: ByteArray, at: Int): Boolean {
        val b0 = b[at].toInt() and 0xff; val b1 = b[at + 1].toInt() and 0xff
        return b0 and 0x0f == 8 && (b0 shr 4) <= 7 && ((b0 shl 8) or b1) % 31 == 0
    }

    /**
     * What a stream from [at] unpacks to before it ends or breaks: a stream cut short (the file is copied while it is
     * written) still holds whole records before the cut, so a broken one is an expected input, not a failure.
     */
    private fun unpack(file: ByteArray, at: Int, gzip: Boolean): ByteArray {
        val plain = java.io.ByteArrayOutputStream()
        try {
            val src = file.inputStream(at, file.size - at)
            (if (gzip) java.util.zip.GZIPInputStream(src) else java.util.zip.InflaterInputStream(src)).use { z ->
                val buf = ByteArray(16_384)
                while (plain.size() < MAX_PLAIN) { val n = z.read(buf); if (n <= 0) break; plain.write(buf, 0, n) }
            }
        } catch (_: java.io.IOException) {
            // The stream broke here: what came before stands
        }
        return plain.toByteArray()
    }
    private const val MAX_PLAIN = 8 * 1024 * 1024

    // ---- Android 12's memory record

    /**
     * Android 12's memory record: per packet its kept length + 1, (from version 2) its real length, the milliseconds
     * since the packet before, and a kind (0x10 event, 0x11 data in, 0x20 command, 0x21 data out), all little-endian,
     * then the bytes kept. Read from [from] as far as the entries hold, in the version that gets further; nothing under
     * [least] entries (anything can look like a few). Says where it stopped when that isn't the end, with the two lengths
     * and the kind found there (three numbers, no content).
     */
    private fun old(b: ByteArray, from: Int, least: Int = 8): Pair<List<Rec>, String>? {
        val (recs, end, version) = intArrayOf(2, 1).map { v -> oldEntries(b, from, v).let { Triple(it.first, it.second, v) } }
            .filter { it.first.isNotEmpty() }.maxByOrNull { it.second } ?: return null
        if (recs.size < least) return null
        val stop = if (end + 9 > b.size) ""
            else ", stops at $end of ${b.size} B (lengths ${le16(b, end)} and ${le16(b, end + 2)}, kind ${b[end + 8].toInt() and 0xff})"
        return recs to "old layout $version$stop"
    }

    /**
     * The entries of one [version] from [from] as far as they hold, and where they stop. The entries carry no date:
     * times count from one second.
     */
    private fun oldEntries(b: ByteArray, from: Int, version: Int): Pair<List<Rec>, Int> {
        val head = if (version == 2) 9 else 7
        val out = ArrayList<Rec>()
        var o = from; var t = 1_000_000L
        while (o + head <= b.size) {
            val len = le16(b, o)
            val type = b[o + head - 1].toInt() and 0xff
            val kind = OLD_KINDS[type]
            if (len < 1 || kind == null || o + head + len - 1 > b.size) break
            t += le32(b, o + head - 5) * 1000
            out += Rec(t, type < 0x20, kind, if (version == 2) le16(b, o + 2) else len - 1, b.copyOfRange(o + head, o + head + len - 1))
            o += head + len - 1
        }
        return out to o
    }
    private val OLD_KINDS = mapOf(0x10 to 4, 0x11 to 2, 0x12 to 3, 0x17 to 5, 0x20 to 1, 0x21 to 2, 0x22 to 3, 0x2d to 5)

    // ---- an unreadable file

    /**
     * What an unreadable file looks like, for whoever has to teach the reader: the 32 bytes after the header (a record's
     * header and the first bytes of a packet: its kind, never an address or content), where times stand in the first
     * kilobytes (they give the records' spacing), and how a compressed stream found in it starts.
     */
    fun probe(file: ByteArray): String {
        val packed = inflated(file).firstOrNull()
        // How far Android 12's entries hold from the header on, when they do at all (a Galaxy A13's file starts with them)
        val entries = old(file, 16, least = 1)?.let { ", ${it.first.size} entries of ${it.second}" }.orEmpty()
        return "${file.size} B, after the header ${hex(file, 16, 32)}$entries, ${marks(file)}" +
            (packed?.let { (at, plain) -> ", compressed from $at: ${plain.size} B starting ${hex(plain, 0, 24)}, ${marks(plain)}" }
                ?: ", nothing compressed")
    }

    private fun hex(b: ByteArray, from: Int, n: Int) = (from until minOf(b.size, from + n)).joinToString("") { "%02x".format(b[it]) }

    private fun timeByte(v: Byte) = (v.toInt() and 0xff).let { it == 0xe3 || it == 0xe4 }

    /** Where a time's top bytes stand in [b]'s first 4 KiB, in either byte order. */
    private fun marks(b: ByteArray): String {
        val be = ArrayList<Int>(); val le = ArrayList<Int>()
        for (o in 0 until minOf(b.size - 8, 4096)) {
            if (b[o].toInt() == 0 && timeByte(b[o + 1])) be += o
            if (b[o + 7].toInt() == 0 && timeByte(b[o + 6])) le += o
        }
        return "times at ${be.take(8).joinToString(" ").ifEmpty { "-" }} / reversed ${le.take(8).joinToString(" ").ifEmpty { "-" }}"
    }

    private fun be32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xff) shl 24) or ((b[o + 1].toLong() and 0xff) shl 16) or ((b[o + 2].toLong() and 0xff) shl 8) or (b[o + 3].toLong() and 0xff)
    private fun le16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
    private fun le32(b: ByteArray, o: Int): Long = le16(b, o).toLong() or (le16(b, o + 2).toLong() shl 16)
    private fun le64(b: ByteArray, o: Int): Long = le32(b, o) or (le32(b, o + 4) shl 32)
}
