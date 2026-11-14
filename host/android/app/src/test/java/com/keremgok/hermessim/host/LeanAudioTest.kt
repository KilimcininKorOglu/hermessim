// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a Bluetooth audio channel goes lean (LeanAudio.Watch, why), what a lean write holds, and which links count as small. */
class LeanAudioTest {
    /** Feeds a second's dropped frames one after another; the 1-based second it fired in, or 0. */
    private fun firesAt(patient: Boolean, vararg dropped: Int): Int {
        val w = LeanAudio.Watch()
        dropped.forEachIndexed { i, d -> if (w.second(d, patient)) return i + 1 }
        return 0
    }

    @Test fun aLeanWriteIsTwoRadioPackets() {
        // two frames, each [0xA5][len][u16 tick][20 bytes of Opus], behind L2CAP's 4-byte header and 2-byte length
        assertEquals(48, LeanAudio.WRITE)
        assertEquals(2 * HciRing.SMALLEST, LeanAudio.WRITE + LeanAudio.L2CAP_HEADER)
        // the cap's bit rate (bytes x 8 x 100 frames a second) stays under the normal 24 kbit/s
        assertTrue(LeanAudio.FRAME * 800 < 24000)
    }

    @Test fun aCleanCallNeverGoesLean() {
        assertEquals(0, firesAt(false, *IntArray(120)))
        assertEquals(0, firesAt(false, 0, 1, 4, 0, 2, 4, 4, 3, 0))                      // a few frames a second is not trouble
    }

    @Test fun aLinkThatStopsKeepingUpGoesLeanWithinSeconds() {
        // a Galaxy A13: 34 to 44 of 100 frames a second dropped in its bad stretches
        assertEquals(2, firesAt(false, 34, 44))
        // its milder stretches: one burst of ~50 every few seconds
        assertEquals(5, firesAt(false, 48, 0, 0, 0, 53))
    }

    @Test fun oneBadSecondAloneIsNotTrouble() {
        assertEquals(0, firesAt(false, 0, 60, 0, 0, 0, 0, 0, 0, 0, 0))
        assertEquals(0, firesAt(false, 60, 0, 0, 0, 0, 0, 60, 0, 0, 0, 0, 0, 60))     // six seconds apart: never two in six
    }

    @Test fun withAnInternetPathThePhonesOwnMoveComesFirst() {
        // the phone moves a fading call to the internet within seconds; lean waits five
        assertEquals(6, firesAt(true, 40, 40, 40, 40, 40, 40, 40))
        assertEquals(0, firesAt(true, 40, 40, 40, 40))                                 // (the call has moved by then)
        // trouble that comes and goes still counts from where it began
        assertEquals(7, firesAt(true, 40, 0, 0, 40, 0, 0, 40))
    }

    @Test fun troubleLongAgoDoesNotShortenTheWait() {
        val quiet = IntArray(30)
        assertEquals(0, firesAt(true, 40, 40, *quiet, 40, 40))
        assertEquals(38, firesAt(true, 40, 40, *quiet, 40, 40, 40, 40, 40, 40))        // five seconds after second 33
    }

    @Test fun anInternetPathThatGoesAwayEndsTheWait() {
        val w = LeanAudio.Watch()
        assertEquals(listOf(false, false, false), listOf(40, 40, 40).map { w.second(it, true) })
        assertTrue(w.second(40, false))
    }

    @Test fun onlyTroubleOnASmallLinkGoesLean() {
        assertEquals("this link sends in small radio packets and isn't keeping up", LeanAudio.why(troubled = true, small = true))
        assertNull(LeanAudio.why(troubled = true, small = false))                       // that phone is leaving Bluetooth range
        assertNull(LeanAudio.why(troubled = false, small = true))
        LeanAudio.always = true
        try {
            assertEquals("lean from the start (debug)", LeanAudio.why(troubled = false, small = false))
        } finally {
            LeanAudio.always = false
        }
    }

    @Test fun onlyWhatTheRecordHoldsMakesALinkSmall() {
        // a Galaxy A13: its own Bluetooth asked for 27 bytes and the length never changed
        assertEquals(true, HciRing.Link(role = "peripheral", askedTxBytes = 27, sinceConnect = true).small)
        assertEquals(true, HciRing.Link(txBytes = 27, rxBytes = 251).small)
        // the usual link: both sides agreed on 251
        assertEquals(false, HciRing.Link(txBytes = 251, rxBytes = 251, askedTxBytes = 251, sinceConnect = true).small)
        assertEquals(false, HciRing.Link(txBytes = 251, askedTxBytes = 27).small)       // what was agreed beats what was asked
        // no change in the record proves nothing: it may leave such events out
        assertNull(HciRing.Link(role = "peripheral", sinceConnect = true).small)
        assertNull(HciRing.Link(askedTxBytes = 251, sinceConnect = true).small)
        assertNull(HciRing.Link().small)
    }

    @Test fun aCallsNumbersSayWhatLeanDid() {
        assertEquals("no", CallAudioCheck.Bridge(1, 3000, 3000, 2980, 0, 4, 0).lean())
        assertEquals("after 412 frames: 96 dropped before, 14 of 2600 after",
            CallAudioCheck.Bridge(1, 3012, 2902, 1600, 110, 930, 0, leanFromIn = 412, leanFromDropped = 96).lean())
    }
}
