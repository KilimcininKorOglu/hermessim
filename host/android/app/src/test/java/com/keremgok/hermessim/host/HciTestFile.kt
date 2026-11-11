// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import java.io.ByteArrayOutputStream

/** A btsnoop file as Android writes it: big-endian record headers, each packet led by its kind byte. */
internal class HciTestFile(val datalink: Int = 1002) {
    private val out = ByteArrayOutputStream()
    private fun be32(v: Long) { for (s in intArrayOf(24, 16, 8, 0)) out.write((v shr s).toInt() and 0xff) }
    init { out.write("btsnoop".toByteArray()); out.write(0); be32(1); be32(datalink.toLong()) }
    private val t0 = 0x00E33DA52CE4FDBCL                                        // a time as the emulator wrote it
    fun rec(ms: Double, fromChip: Boolean, kind: Int, body: IntArray, realLen: Int = body.size): HciTestFile {
        val t = t0 + (ms * 1000).toLong()
        be32(realLen + 1L); be32(body.size + 1L); be32((if (fromChip) 1L else 0L) or (if (kind == 2) 0L else 2L)); be32(0)
        be32(t ushr 32); be32(t and 0xffffffffL)
        out.write(kind); body.forEach { out.write(it) }
        return this
    }
    private fun le16(v: Int) = intArrayOf(v and 0xff, v shr 8 and 0xff)
    fun event(ms: Double, code: Int, vararg params: Int) = rec(ms, true, 4, intArrayOf(code, params.size) + params)
    fun command(ms: Double, opcode: Int, vararg params: Int) = rec(ms, false, 1, le16(opcode) + intArrayOf(params.size) + params)
    /** A data packet the way the record keeps it: the first 14 bytes, the real length beside it. */
    fun data(ms: Double, fromChip: Boolean, handle: Int, l2capLen: Int, cid: Int = 0x40, vararg more: Int): HciTestFile {
        val pb = if (fromChip) 2 else 0
        val whole = le16(handle or (pb shl 12)) + le16(l2capLen + 4) + le16(l2capLen) + le16(cid) + more + IntArray(6) { 0 }
        return rec(ms, fromChip, 2, whole.copyOf(14), 4 + 4 + l2capLen)
    }
    fun completed(ms: Double, handle: Int, n: Int) = event(ms, 0x13, 1, *le16(handle), *le16(n))
    fun bytes(): ByteArray = out.toByteArray()
    fun le(v: Int) = le16(v)

    companion object {
        /** The link the tests' audio runs on. */
        const val H = 0x0002

        /** [seconds] of a stream: a packet every 10 ms, the chip reporting [perReport] of them every [reportMs]. */
        fun stream(reportMs: Double, perReport: Int, seconds: Int = 10): HciTestFile {
            val f = HciTestFile()
            var nextReport = reportMs; var waiting = 0
            for (i in 0..seconds * 100) {
                val t = i * 10.0
                while (nextReport <= t) { val n = minOf(perReport, waiting); if (n > 0) f.completed(nextReport, H, n); waiting -= n; nextReport += reportMs }
                f.data(t + 0.5, false, H, 48); waiting++
                f.data(t + 0.7, true, H, 40)
            }
            return f
        }
    }
}
