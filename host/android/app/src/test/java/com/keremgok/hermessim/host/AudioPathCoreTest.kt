package com.keremgok.hermessim.host

import com.google.protobuf.ByteString
import com.keremgok.hermessim.host.AudioPathCore.Local
import com.keremgok.hermessim.host.AudioPathCore.Via
import com.keremgok.hermessim.wire.AudioReport
import com.keremgok.hermessim.wire.Candidate
import com.keremgok.hermessim.wire.CandidateKind
import com.keremgok.hermessim.wire.Code
import com.keremgok.hermessim.wire.OpenAudioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules of a call's audio paths: what an OpenAudioPath may carry, where audio goes, the loss and the report. */
class AudioPathCoreTest {
    private val direct4 = candidate(CandidateKind.CANDIDATE_KIND_DIRECT, 4)
    private val relay4 = candidate(CandidateKind.CANDIDATE_KIND_RELAY, 4)
    private val direct6 = candidate(CandidateKind.CANDIDATE_KIND_DIRECT, 16)

    // Any address size, a wrong one too: the phone's candidates are checked by refusal.
    private fun candidate(kind: CandidateKind, size: Int, port: Int = 40000): Candidate =
        AudioPathCore.candidate(kind, ByteArray(4), port).toBuilder().setAddress(ByteString.copyFrom(ByteArray(size) { 1 })).build()

    private fun open(vararg c: Candidate, id: Int = 16, key: Int = 32) = OpenAudioPath.newBuilder()
        .setAudioId(ByteString.copyFrom(ByteArray(id))).setKey(ByteString.copyFrom(ByteArray(key))).addAllCandidates(c.toList()).build()

    @Test fun aPathNeedsItsIdItsKeyAndOneToFourGoodCandidates() {
        assertNull(AudioPathCore.refusal(open(direct4, relay4)))
        assertEquals(Code.CODE_BAD_VALUE to 1, AudioPathCore.refusal(open(direct4, id = 8)))
        assertEquals(Code.CODE_BAD_VALUE to 2, AudioPathCore.refusal(open(direct4, key = 31)))
        assertEquals(Code.CODE_BAD_VALUE to 3, AudioPathCore.refusal(open()))
        assertEquals(Code.CODE_BAD_VALUE to 3, AudioPathCore.refusal(open(direct4, direct4, direct4, direct4, direct4)))
        assertEquals(Code.CODE_BAD_VALUE to 3, AudioPathCore.refusal(open(candidate(CandidateKind.CANDIDATE_KIND_DIRECT, 5))))
        assertEquals(Code.CODE_BAD_VALUE to 3, AudioPathCore.refusal(open(candidate(CandidateKind.CANDIDATE_KIND_DIRECT, 4, port = 0))))
        assertEquals(Code.CODE_BAD_VALUE to 3, AudioPathCore.refusal(open(candidate(CandidateKind.CANDIDATE_KIND_UNSPECIFIED, 4))))
    }

    @Test fun aPathHasAtMostOneRelay() {
        assertEquals(Code.CODE_BAD_VALUE to 3, AudioPathCore.refusal(open(relay4, relay4)))
        assertFalse(AudioPathCore.hostRelays(listOf(direct4, relay4)))     // the phone brought one: the host takes none
        assertTrue(AudioPathCore.hostRelays(listOf(direct4)))
    }

    @Test fun anIpv6OnlyPhoneGetsAnIpv6Relay() {
        assertTrue(AudioPathCore.wantsIpv6(listOf(direct6)))
        assertFalse(AudioPathCore.wantsIpv6(listOf(direct6, direct4)))
        assertFalse(AudioPathCore.wantsIpv6(emptyList()))
    }

    @Test fun callerAudioFollowsThePhonesLatestPacket() {
        assertEquals(Via.INTERNET, AudioPathCore.via(Via.INTERNET, bluetooth = true, internet = true))
        assertEquals(Via.BLUETOOTH, AudioPathCore.via(Via.BLUETOOTH, bluetooth = true, internet = true))
        // The way it came is gone: the other one carries it, so a path change never stops the audio.
        assertEquals(Via.INTERNET, AudioPathCore.via(Via.BLUETOOTH, bluetooth = false, internet = true))
        assertEquals(Via.BLUETOOTH, AudioPathCore.via(Via.INTERNET, bluetooth = true, internet = false))
        // Nothing from the phone yet: Bluetooth first.
        assertEquals(Via.BLUETOOTH, AudioPathCore.via(null, bluetooth = true, internet = true))
        assertEquals(Via.INTERNET, AudioPathCore.via(null, bluetooth = false, internet = true))
        assertNull(AudioPathCore.via(Via.INTERNET, bluetooth = false, internet = false))
    }

    @Test fun theExpectedLossStaysBetweenFiveAndThirtyPercent() {
        // received, lost, the expected loss
        listOf(Triple(500, 0, 5), Triple(450, 50, 10), Triple(100, 400, 30), Triple(0, 0, null)).forEach { (received, lost, want) ->
            assertEquals(want, AudioPathCore.loss(with(AudioReport.newBuilder()) { this.received = received; this.lost = lost; build() }))
        }
    }

    @Test fun theHostsReportCountsSinceTheLastOne() {
        val before = mapOf("recv" to 1000L, "plc" to 10L, "fec" to 5L, "late" to 2L, "txdrop" to 1L)
        val now = mapOf("recv" to 1490L, "plc" to 14L, "fec" to 9L, "late" to 3L, "txdrop" to 4L)
        val r = AudioPathCore.report(before, now, Local(2, 0, 7), Local(5, 1, 7))
        assertEquals(490, r.received)
        assertEquals(8, r.lost)
        assertEquals(1, r.late)
        assertEquals(6, r.droppedSent)
        assertEquals(1, r.sendErrors)
        assertEquals(0, r.skippedBytes)
    }

    @Test fun aNewCallsTotalsCountFromZero() {
        val r = AudioPathCore.report(mapOf("recv" to 9000L), mapOf("recv" to 300L), Local(0, 0, 0), Local(0, 0, 0))
        assertEquals(300, r.received)
    }
}
