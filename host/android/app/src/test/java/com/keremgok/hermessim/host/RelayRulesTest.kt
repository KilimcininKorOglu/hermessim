// HermesSIM host. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import com.google.protobuf.ByteString
import com.keremgok.hermessim.wire.CallControl
import com.keremgok.hermessim.wire.CallVerb
import com.keremgok.hermessim.wire.Code
import com.keremgok.hermessim.wire.Dial
import com.keremgok.hermessim.wire.Feature
import com.keremgok.hermessim.wire.GetHotspotSecret
import com.keremgok.hermessim.wire.Hello
import com.keremgok.hermessim.wire.Request
import com.keremgok.hermessim.wire.Role
import com.keremgok.hermessim.wire.SendText
import com.keremgok.hermessim.wire.SetHotspotSetup
import com.keremgok.hermessim.wire.SetMobileData
import com.keremgok.hermessim.wire.SetPushKey
import com.keremgok.hermessim.wire.SetRemote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which requests and which Hello the host takes: the rules of protocol/v2/SPEC.md that need no phone. */
class RelayRulesTest {
    private val dial = request { dial = Dial.newBuilder().setNumber("5").build() }
    private val testDial = request { dial = Dial.newBuilder().setNumber("5").setTest(true).build() }
    private val answer = request { callControl = CallControl.newBuilder().setVerb(CallVerb.CALL_VERB_ANSWER).build() }
    private val hangUp = request { callControl = CallControl.newBuilder().setVerb(CallVerb.CALL_VERB_END).build() }

    @Test fun `a call request is too old after 10 s, another change after 30 s, a text never`() {
        assertFalse(RequestRules.tooOld(Request.OpCase.DIAL, 10_000))
        assertTrue(RequestRules.tooOld(Request.OpCase.DIAL, 10_001))
        assertFalse(RequestRules.tooOld(Request.OpCase.SET_BOX, 30_000))
        assertTrue(RequestRules.tooOld(Request.OpCase.SET_BOX, 30_001))
        assertFalse(RequestRules.tooOld(Request.OpCase.SEND_TEXT, 3_600_000))
        assertFalse(RequestRules.tooOld(Request.OpCase.GET_STATE, 3_600_000))
        assertEquals(Code.CODE_TOO_OLD, RequestRules.refusal(request(ageMs = 10_001) { dial = Dial.getDefaultInstance() }, bluetooth = true))
    }

    @Test fun `a secret travels over Bluetooth only`() {
        val secrets = listOf(
            request { getHotspotSecret = GetHotspotSecret.getDefaultInstance() },
            request { setHotspotSetup = SetHotspotSetup.newBuilder().setPassphrase("12345678").build() },
            request { setRemote = SetRemote.newBuilder().setOn(true).build() },
            request { setPushKey = SetPushKey.newBuilder().setKey(ByteString.copyFrom(ByteArray(32))).build() },
        )
        for (req in secrets) {
            assertEquals(req.opCase.name, Code.CODE_BLUETOOTH_ONLY, RequestRules.refusal(req, bluetooth = false))
            assertNull(req.opCase.name, RequestRules.refusal(req, bluetooth = true))
        }
    }

    @Test fun `turning internet mode off, forgetting the push key and a setup without the passphrase go over the internet too`() {
        val open = listOf(
            request { setRemote = SetRemote.getDefaultInstance() },
            request { setPushKey = SetPushKey.getDefaultInstance() },
            request { setHotspotSetup = SetHotspotSetup.newBuilder().setHidden(true).build() },
            request { sendText = SendText.getDefaultInstance() },
        )
        for (req in open) assertNull(req.opCase.name, RequestRules.refusal(req, bluetooth = false))
    }

    @Test fun `mobile data is not turned off over the link it carries`() {
        val off = request { setMobileData = SetMobileData.getDefaultInstance() }
        val on = request { setMobileData = SetMobileData.newBuilder().setOn(true).build() }
        assertEquals(Code.CODE_CARRIES_LINK, RequestRules.refusal(off, bluetooth = false))
        assertNull(RequestRules.refusal(off, bluetooth = true))
        assertNull(RequestRules.refusal(on, bluetooth = false))
    }

    @Test fun `until the call test passes only the test call and hanging up reach a call`() {
        fun refusal(req: Request, relays: Boolean = false, profile: Boolean = true) = RequestRules.voiceRefusal(req, relays, profile)
        assertEquals(Code.CODE_CALL_TEST_NEEDED, refusal(dial))
        assertEquals(Code.CODE_CALL_TEST_NEEDED, refusal(answer))
        assertNull(refusal(hangUp))
        assertNull(refusal(testDial))
        assertEquals(Code.CODE_NO_AUDIO_PROFILE, refusal(testDial, relays = true, profile = false))
        assertNull(refusal(dial, relays = true))
        assertNull(refusal(answer, relays = true))
    }

    private val id = ByteString.copyFrom(ByteArray(16) { (it + 1).toByte() })
    private val pinned = "0102030405060708090a0b0c0d0e0f10"

    /** A Hello the host takes, then changed by [fill]. */
    private fun hello(fill: Hello.Builder.() -> Unit = {}): Hello {
        val taken = Hello.newBuilder()
        taken.minVersion = 2
        taken.maxVersion = 2
        taken.role = Role.ROLE_PHONE
        taken.installId = id
        return taken.apply(fill).build()
    }

    @Test fun `only the paired phone's Hello is taken`() {
        assertEquals(pinned, HelloRules.id(hello()))
        assertNull(HelloRules.refusal(hello(), pinned))
        assertNull(HelloRules.refusal(hello { minVersion = 1; maxVersion = 3 }, pinned))
        assertEquals(Code.CODE_NOT_PAIRED, HelloRules.refusal(hello(), null))
        assertEquals(Code.CODE_NOT_PAIRED, HelloRules.refusal(hello(), pinned.reversed()))
        assertEquals(Code.CODE_NOT_PAIRED, HelloRules.refusal(hello { installId = ByteString.copyFrom(ByteArray(15)) }, "00".repeat(15)))
        assertEquals(Code.CODE_VERSION, HelloRules.refusal(hello { minVersion = 3; maxVersion = 4 }, pinned))
    }

    @Test fun `a Mac's Hello is taken over Bluetooth when its install id is a paired Mac's`() {
        val mac = hello { role = Role.ROLE_MAC }
        val listed = { id: String -> id == pinned }
        assertNull(HelloRules.refusal(mac, null, listed))
        assertEquals(Code.CODE_NOT_PAIRED, HelloRules.refusal(mac, pinned, isMac = { false }))
        assertEquals(Code.CODE_NOT_ALLOWED, HelloRules.refusal(mac, null, listed, bluetooth = false))
        assertEquals(Code.CODE_NOT_PAIRED, HelloRules.refusal(hello { role = Role.ROLE_MAC; installId = ByteString.copyFrom(ByteArray(15)) }, null, { true }))
        assertEquals(Code.CODE_NOT_ALLOWED, HelloRules.refusal(hello { role = Role.ROLE_UNSPECIFIED }, pinned))
    }

    @Test fun `the host names each of its features with its own bit`() {
        assertEquals(0xFEL, HelloRules.FEATURES)
        assertTrue(HelloRules.FEATURES and (1L shl Feature.FEATURE_MAC_PEERS.number) != 0L)
    }

    @Test fun `signal bars go at once when a line loses its signal or gets it back`() {
        val gate = SignalGate()
        assertTrue(gate.pass(listOf(3, 2), now = 0))
        assertFalse(gate.pass(listOf(3, 2), now = 1))
        assertTrue(gate.pass(listOf(0, 2), now = 2))
        assertTrue(gate.pass(listOf(1, 2), now = 3))
        assertTrue(gate.pass(listOf(1), now = 4))                    // a line went
    }

    @Test fun `other changes of the bars go at most every 30 s and only while a peer watches`() {
        val gate = SignalGate()
        gate.pass(listOf(3), now = 0)
        assertFalse(gate.pass(listOf(2), now = 40_000))             // nobody watches
        assertNull(gate.waitMs(listOf(2), now = 40_000))
        gate.asked(now = 50_000)
        assertTrue(gate.pass(listOf(2), now = 50_000))
        assertFalse(gate.pass(listOf(4), now = 60_000))
        assertEquals(20_000L, gate.waitMs(listOf(4), now = 60_000))
        assertTrue(gate.pass(listOf(4), now = 80_000))
        assertNull(gate.waitMs(listOf(4), now = 80_001))
        assertFalse(gate.pass(listOf(1), now = 350_001))            // the peer asked more than 5 minutes ago
    }
}
