// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private typealias File = HciTestFile

class HciRingTest {
    private val H = HciTestFile.H

    @Test fun notARecordFile() {
        assertTrue(HciRing.records(ByteArray(0)).isEmpty())
        assertTrue(HciRing.records("hello, this is no btsnoop file at all".toByteArray()).isEmpty())
        assertTrue(HciRing.records(File(datalink = 1001).event(0.0, 0x13, 0).bytes()).isEmpty())   // not serial-line packets
    }

    @Test fun aFileCutMidRecordKeepsWhatCameBefore() {
        val whole = File().completed(0.0, H, 1).completed(5.0, H, 1).bytes()
        assertEquals(2, HciRing.records(whole).size)
        assertEquals(1, HciRing.records(whole.copyOf(whole.size - 3)).size)
    }

    @Test fun whatALinkAgreed() {
        val f = File()
        // LE enhanced connection complete: status 0, handle, role 1 = peripheral, address type + 3 addresses, interval 24 x 1.25 ms
        f.event(0.0, 0x3e, 0x0a, 0, *f.le(H), 1, 0, *IntArray(18) { 0x11 }, *f.le(24), 0, 0, 0xf4, 0x01, 0)
        f.command(300.0, 0x2022, *f.le(H), *f.le(251), *f.le(2120))                               // LE Set Data Length
        f.event(320.0, 0x3e, 0x07, *f.le(H), *f.le(251), *f.le(2120), *f.le(251), *f.le(2120))   // data length change
        f.event(400.0, 0x3e, 0x0c, 0, *f.le(H), 2, 2)                                             // radio mode 2M both ways
        f.event(900.0, 0x3e, 0x03, 0, *f.le(H), *f.le(12), 0, 0, 0xf4, 0x01)                     // interval 12 x 1.25 ms
        val l = HciRing.links(HciRing.records(f.bytes()))
        assertEquals(setOf(H), l.keys)
        assertEquals("peripheral, every 15 ms, packets out 251 B / 2120 us, in 251 B / 2120 us, host asked 251 B, 2M/2M", l[H]!!.text())
    }

    @Test fun aLinkThatNeverChangedItsPacketLengthSaysSo() {
        val f = File()
        f.event(0.0, 0x3e, 0x01, 0, *f.le(H), 1, 0, *IntArray(6) { 0x11 }, *f.le(36), 0, 0, 0xf4, 0x01, 0)
        assertEquals("peripheral, every 45 ms, packets 27 B both ways (length never changed)", HciRing.links(HciRing.records(f.bytes()))[H]!!.text())
        // Without the link's start in the record nothing can be said about its packet length
        val later = File().event(0.0, 0x3e, 0x03, 0, *f.le(H), *f.le(12), 0, 0, 0xf4, 0x01)
        assertEquals("every 15 ms, packet length not in the record", HciRing.links(HciRing.records(later.bytes()))[H]!!.text())
    }

    @Test fun anEarlierLookFillsWhatACallPushedOut() {
        val known = mapOf(H to HciRing.Link(role = "peripheral", intervalMs = 30.0, txBytes = 27, txUs = 328, rxBytes = 251, rxUs = 2120))
        val f = File()
        f.event(0.0, 0x3e, 0x03, 0, *f.le(H), *f.le(12), 0, 0, 0xf4, 0x01)
        assertEquals("peripheral, every 15 ms, packets out 27 B / 328 us, in 251 B / 2120 us", HciRing.links(HciRing.records(f.bytes()), known)[H]!!.text())
        // The link ended: nothing is known of the next one on that number
        val gone = File().event(0.0, 0x05, 0, *f.le(H), 0x13)
        assertTrue(HciRing.links(HciRing.records(gone.bytes()), known).isEmpty())
    }

    @Test fun theChipsRoom() {
        val f = File()
        f.event(0.0, 0x0e, 1, *f.le(0x2002), 0, *f.le(251), 8)
        assertEquals("251 B x 8", HciRing.buffers(HciRing.records(f.bytes())))
        assertEquals("", HciRing.buffers(HciRing.records(File().completed(0.0, H, 1).bytes())))
    }

    private fun stream(reportMs: Double, perReport: Int) = HciTestFile.stream(reportMs, perReport)

    @Test fun aChipThatKeepsUp() {
        // Every 15 ms the chip reports all it holds: nothing waits longer than that
        val s = HciRing.sending(HciRing.records(stream(15.0, 4).bytes()))!!
        assertEquals(H, s.handle)
        assertEquals(10.0, s.seconds, 0.01)
        assertEquals(1000, s.handed); assertEquals(52, s.bytesAvg); assertEquals(52, s.bytesMax)
        assertTrue(s.sent in 995..1000)
        assertTrue("waited ${s.waitMs}", s.waitMs.third <= 15)
        assertTrue(s.queueMax <= 2)
        assertEquals(50 to 50, s.handedHalf); assertEquals(10, s.handPauseMs)
        assertEquals(1000, s.received)
        assertFalse(s.late)
        assertTrue(s.text(), s.text().startsWith("last 10.0 s: handed 100/s (52 B, longest 52 B, per half second 50-50, longest pause 10 ms), chip sent 100/s in 67 reports/s"))
    }

    @Test fun aChipThatSendsHalf() {
        // One packet every 20 ms whatever it holds: the queue grows and every packet waits longer
        val s = HciRing.sending(HciRing.records(stream(20.0, 1).bytes()))!!
        assertEquals(1000, s.handed)
        assertTrue("sent ${s.sent}", s.sent in 495..500)
        assertTrue(s.waitMs.second > 1000)
        assertTrue(s.queueMax > 400)
        assertTrue(s.late)
    }

    @Test fun packetsAlreadyInTheChipWhenTheRecordStarts() {
        // Three packets from before the record are reported first: the record's own first packet is the fourth out
        val f = File()
        f.completed(1.0, H, 3)
        for (i in 0 until 300) { f.data(10.0 + i * 10, false, H, 48); f.completed(10.0 + i * 10 + 4, H, 1) }
        val s = HciRing.sending(HciRing.records(f.bytes()))!!
        assertEquals(Triple(4, 4, 4), s.waitMs)
        assertEquals(1, s.queueMax)
    }

    @Test fun aReportWithThePacketsOwnTimeFollowsIt() {
        // A record in milliseconds gives a packet and the chip's report of it the same time: the order says which came first
        val f = File()
        for (i in 0 until 300) { f.data(i * 10.0, false, H, 48); f.completed(i * 10.0, H, 1) }
        val s = HciRing.sending(HciRing.records(f.bytes()))!!
        assertEquals(Triple(0, 0, 0), s.waitMs)
        assertEquals(1, s.queueMax)
        assertEquals(299, s.sent)                                            // the window starts after the first record
    }

    @Test fun aHostThatHandsOverInClumps() {
        // The chip is quick, but the host's Bluetooth gives it nothing for 400 ms every second, then the backlog
        val f = File()
        for (sec in 0 until 6) for (i in 0 until 100) {
            val t = sec * 1000.0 + if (i < 40) 400.0 + i * 0.5 else i * 10.0
            f.data(t, false, H, 48); f.completed(t + 3, H, 1)
        }
        val recs = HciRing.records(f.bytes()).sortedBy { it.t }
        val s = HciRing.sending(recs)!!
        assertEquals(50 to 50, s.handedHalf)                               // too short a gap for the half seconds to show
        assertTrue("pause ${s.handPauseMs}", s.handPauseMs in 390..420)
        assertTrue(s.waitMs.second < 10)
        assertTrue(s.late)
    }

    @Test fun creditPacketsAreCounted() {
        val f = File()
        for (i in 0 until 300) {
            f.data(i * 10.0, false, H, 48); f.completed(i * 10.0 + 2, H, 1)
            if (i % 50 == 0) f.data(i * 10.0 + 1, true, H, 8, 0x0005, 0x16, 1, 4, 0, 0x40, 0)   // LE flow control credit, cut after 14 bytes
        }
        assertEquals(6, HciRing.sending(HciRing.records(f.bytes()))!!.credits)
    }

    @Test fun theBusiestLinkIsTheOneLookedAt() {
        val f = File()
        for (i in 0 until 300) { f.data(i * 10.0, false, 0x0007, 48); f.completed(i * 10.0 + 2, 0x0007, 1) }
        for (i in 0 until 5) f.data(i * 100.0, false, H, 20, 0x0004)
        assertEquals(0x0007, HciRing.sending(HciRing.records(f.bytes()))!!.handle)
    }

    @Test fun aRecordWithoutDataPacketsStillSaysWhatTheChipSent() {
        val f = File()
        for (i in 0 until 400) f.completed(i * 20.0, H, 1)
        val s = HciRing.sending(HciRing.records(f.bytes()))!!
        assertEquals(0, s.handed); assertEquals(399, s.sent); assertEquals(20, s.pauseMs)
        assertFalse(s.late)
        assertTrue(s.text(), s.text().startsWith("last 8.0 s: handed: no data packets in the record, chip sent 50/s in 50 reports/s (per half second 25-25)"))
    }

    @Test fun nothingSent() {
        assertNull(HciRing.sending(HciRing.records(File().event(0.0, 0x0e, 1, 0x03, 0x0c, 0).bytes())))
        assertEquals("1 records over 0.0 s: 0 commands, 1 events (0 of them sent reports, 0 the chip maker's own), 0 data out, 0 data in", HciRing.size(HciRing.records(File().event(0.0, 0x0e, 1, 0x03, 0x0c, 0).bytes())))
    }
}
