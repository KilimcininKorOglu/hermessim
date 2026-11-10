// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A call in a report's log excerpt (LogTrim): Telecom's call events only, clean audio lines counted, other tags cut short. */
class LogTrimCallTest {
    @Test fun telecomKeepsItsCallEventsAndWarningsTheRestIsCounted() {
        // A call's end on a Redmi Note 9 Pro (9 Nov 2026): 60 such lines in half a second were the whole excerpt
        val update = "11-09 21:23:21.502 I/Telecom ( 1418): InCallController: Sending updateCall [Call id=TC@15, state=DISCONNECTED, tpac=ComponentInfo{com.android.phone/x}]"
        val route = "11-09 21:23:21.509 I/Telecom ( 1418): CallAudioRouteStateMachine: Message received: SWITCH_FOCUS=4001, arg1=1: CSW.sDc"
        val event = "11-09 21:23:21.576 I/Telecom ( 1418): Event: RecordEntry TC@15: DESTROYED, null: CSW.rC->CM.pR(cap)@CgE"
        val audio = "11-09 21:23:21.557 I/Telecom ( 1418): Logging.Events: Non-call EVENT: AUDIO_ROUTE, Entering state QuiescentEarpieceRoute: CSW.sDc"
        val warn = "11-09 21:23:21.576 W/Telecom ( 1418): CallDiagnosticServiceController: maybeUnbindCallScreeningService - already unbound"
        val ended = "11-09 21:23:21.530 I/HermesSimHost( 6080): call 97f59842 ENDED incoming=true slot=0 cause=REMOTE"
        val out = LogTrim.trim(listOf(update, route, ended, audio, update, event, warn).joinToString("\n"), 12_000)
        assertEquals(listOf("… 2 Telecom lines left out (21:23:21)", ended, audio, "… 1 Telecom lines left out (21:23:21)", event, warn)
            .joinToString("\n") + "\n", out)
    }

    @Test fun aCallsCleanAudioLinesAreTheFirstAndACountTheOddOnesStay() {
        val line = { t: String, sent: Int, txdrop: Int, fps: String ->
            "11-09 21:$t I/hermessimd( 5151): audio 5s: sent=$sent txdrop=$txdrop recv=500 fec=0 plc=0 late=0 catchup=0 caperr=0 peak=20458 played=500 depthmax=4 ($fps fps) | held: jb=22.0ms reopen=0 (tune prime=2 keep=1 period=2)" }
        val stall = "11-09 21:19:40.100 W/HermesSimHost( 6080): audio to the phone: Bluetooth isn't taking it, dropping the oldest (wifi=off hotspot=off voice=3g ble=15.0ms)"
        val log = listOf(line("19:25.000", 500, 0, "100.0"), line("19:30.000", 499, 0, "99.9"), line("19:35.000", 501, 0, "100.1"), stall,
            line("19:40.000", 500, 212, "100.0"), line("19:45.000", 431, 0, "86.2"), line("19:50.000", 500, 0, "100.0"), line("19:55.000", 500, 0, "100.0"))
        assertEquals(listOf(log[0], "… 2 more clean 'audio 5s' lines, last at 21:19:35", stall, log[4], log[5], log[6],
            "… 1 more clean 'audio 5s' lines, last at 21:19:55").joinToString("\n") + "\n", LogTrim.trim(log.joinToString("\n"), 12_000))
    }

    @Test fun otherTagsLongLinesAreCutOursNever() {
        val theirs = "11-09 21:23:21.000 I/Tethering( 1418): " + "x".repeat(900)
        val mine = "11-09 21:23:22.000 W/HermesSimHost( 6080): call audio poor (stall): " + "y".repeat(900)
        val out = LogTrim.trim("$theirs\n$mine", 12_000).split('\n')
        assertEquals(301, out[0].length); assertTrue(out[0].endsWith("x…")); assertEquals(mine, out[1])
    }
}
