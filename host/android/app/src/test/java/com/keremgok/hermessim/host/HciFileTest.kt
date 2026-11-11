// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import com.keremgok.hermessim.host.HciTestFile.Companion.H
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** A phone maker's own layout: a Galaxy A13's file had the right header and was not readable (11 Nov 2026). */
class HciFileTest {
    private fun call() = HciTestFile.stream(15.0, 4, seconds = 3).bytes()
    private fun deflate(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        java.util.zip.DeflaterOutputStream(out).use { it.write(b) }
        return out.toByteArray()
    }

    @Test fun androidsOwnLayoutSaysNothingAboutHow() {
        val (recs, how) = HciFile.read(call())
        assertEquals(3 * 100 * 2 + 2 + 200, recs.size)                       // packets both ways, the chip's reports
        assertEquals("", how)
    }

    @Test fun recordsThatStartLater() {
        val f = call()
        val odd = f.copyOf(16) + ByteArray(40) { 0x5a } + f.copyOfRange(16, f.size)
        val (recs, how) = HciFile.read(odd)
        assertEquals(HciRing.records(f).size, recs.size)
        assertEquals("records from 56", how)
        assertEquals(300, HciRing.sending(recs)!!.handed)
    }

    @Test fun recordsWithTheirNumbersLittleEndian() {
        val f = call()
        val le = f.copyOf()
        var o = 16
        while (o + 24 <= f.size) {
            val incl = ((f[o + 4].toInt() and 0xff) shl 24) or ((f[o + 5].toInt() and 0xff) shl 16) or ((f[o + 6].toInt() and 0xff) shl 8) or (f[o + 7].toInt() and 0xff)
            for (field in intArrayOf(0, 4, 8, 12)) for (i in 0 until 4) le[o + field + i] = f[o + field + 3 - i]
            for (i in 0 until 8) le[o + 16 + i] = f[o + 16 + 7 - i]
            o += 24 + incl
        }
        val (recs, how) = HciFile.read(le)
        assertEquals("records from 16, little-endian", how)
        assertEquals(300, HciRing.sending(recs)!!.handed)
    }

    @Test fun aCompressedRecord() {
        val f = call()
        val (recs, how) = HciFile.read(f.copyOf(16) + deflate(f.copyOfRange(16, f.size)))
        assertEquals("compressed from 16", how)
        assertEquals(HciRing.records(f).size, recs.size)
        // The whole file compressed after a header of its own
        assertEquals("compressed from 16, records from 16", HciFile.read(f.copyOf(16) + deflate(f)).second)
    }

    @Test fun aCompressedRecordCutShortStillGivesItsStart() {
        val f = call()
        val packed = deflate(f.copyOfRange(16, f.size))
        val (recs, _) = HciFile.read(f.copyOf(16) + packed.copyOf(packed.size * 2 / 3))
        assertTrue("${recs.size} records", recs.size > 100)
    }

    @Test fun android12sCompressedRecord() {
        // version 2, the last packet's time (ms, little-endian), then per packet: kept + 1, real length, ms since the one before, kind
        val body = ByteArrayOutputStream()
        fun le(v: Int, n: Int) { for (i in 0 until n) body.write(v shr (8 * i) and 0xff) }
        fun packet(deltaMs: Int, kind: Int, real: Int, vararg d: Int) { le(d.size + 1, 2); le(real, 2); le(deltaMs, 4); body.write(kind); d.forEach { body.write(it) } }
        repeat(300) {
            packet(10, 0x21, 56, H, 0x00, 52, 0, 48, 0, 0x40, 0)                 // data out: link 2, 52 bytes
            packet(0, 0x10, 7, 0x13, 5, 1, H, 0, 1, 0)                           // the chip: one packet sent
        }
        val pre = ByteArrayOutputStream().apply { write(2); val last = 1_800_000_000_000L; for (i in 0 until 8) write((last shr (8 * i)).toInt() and 0xff) }
        val (recs, how) = HciFile.read(HciTestFile().bytes() + pre.toByteArray() + deflate(body.toByteArray()))
        assertEquals("compressed from 25, old layout 2", how)
        val s = HciRing.sending(recs)!!
        assertEquals(299, s.handed); assertEquals(52, s.bytesAvg); assertEquals(100.0, s.handed / s.seconds, 0.5)
        assertEquals(Triple(0, 0, 0), s.waitMs)
    }

    @Test fun anUnreadableFileSaysWhatItLooksLike() {
        val junk = HciTestFile().bytes() + ByteArray(600) { (it * 7).toByte() }
        assertTrue(HciFile.read(junk).first.isEmpty())
        val p = HciFile.probe(junk)
        assertTrue(p, p.startsWith("616 B, after the header 00070e151c232a31383f464d545b6269"))
        assertTrue(p, p.endsWith("nothing compressed"))
        // A layout the reader doesn't know still shows where its times are: every 30 bytes here
        val f = ByteArrayOutputStream().apply { write(HciTestFile().bytes()) }
        for (i in 0 until 20) { f.write(ByteArray(22) { 1 }); f.write(byteArrayOf(0, 0xe3.toByte(), 0x3d, 1, 2, 3, 4, i.toByte())) }
        val q = HciFile.probe(f.toByteArray())
        assertTrue(q, "times at 38 68 98 128" in q)
    }
}
