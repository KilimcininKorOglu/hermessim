package com.keremgok.hermessim.host

import com.keremgok.hermessim.host.wire.Fields
import com.keremgok.hermessim.host.wire.StateBook
import com.keremgok.hermessim.wire.Code
import com.keremgok.hermessim.wire.Dial
import com.keremgok.hermessim.wire.GetHotspotSecret
import com.keremgok.hermessim.wire.GetState
import com.keremgok.hermessim.wire.HostAction
import com.keremgok.hermessim.wire.HostVerb
import com.keremgok.hermessim.wire.Hotspot
import com.keremgok.hermessim.wire.Lines
import com.keremgok.hermessim.wire.OpenPeerWindow
import com.keremgok.hermessim.wire.Peer
import com.keremgok.hermessim.wire.PeerGrants
import com.keremgok.hermessim.wire.Peers
import com.keremgok.hermessim.wire.Power
import com.keremgok.hermessim.wire.Remote
import com.keremgok.hermessim.wire.Request
import com.keremgok.hermessim.wire.SendText
import com.keremgok.hermessim.wire.SetBox
import com.keremgok.hermessim.wire.SetDataLine
import com.keremgok.hermessim.wire.SetHotspot
import com.keremgok.hermessim.wire.SetHotspotClient
import com.keremgok.hermessim.wire.SetHotspotSetup
import com.keremgok.hermessim.wire.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a Mac may ask and see (SPEC.md "Macs"). */
class MacRulesTest {
    private fun grants(texts: Boolean = false, control: Boolean = false) = PeerGrants.newBuilder().setTexts(texts).setControl(control).build()
    private fun action(verb: HostVerb) = request { hostAction = HostAction.newBuilder().setVerb(verb).build() }

    private val everyMac = listOf(
        request { getState = GetState.getDefaultInstance() },
        request { setHotspot = SetHotspot.newBuilder().setOn(true).build() },
        request { getHotspotSecret = GetHotspotSecret.getDefaultInstance() },
        action(HostVerb.HOST_VERB_HOTSPOT_NO_INTERNET),
    )
    private val withControl = listOf(
        request { setDataLine = SetDataLine.newBuilder().setLine(1).build() },
        request { setHotspotClient = SetHotspotClient.getDefaultInstance() },
        action(HostVerb.HOST_VERB_RESTART),
        action(HostVerb.HOST_VERB_REGISTER_AGAIN),
    )
    private val text = request { sendText = SendText.newBuilder().setTo("5554").setBody("ok").build() }
    private val never = listOf(
        request { dial = Dial.getDefaultInstance() },
        request { setBox = SetBox.getDefaultInstance() },
        request { setHotspotSetup = SetHotspotSetup.getDefaultInstance() },
        request { openPeerWindow = OpenPeerWindow.newBuilder().setOpen(true).build() },
    )

    @Test fun `every Mac may ask for the hotspot, its secret, the state and report a stuck hotspot`() {
        for (req in everyMac) assertNull(req.opCase.name, MacRules.refusal(req, grants()))
    }

    @Test fun `host settings need the control grant`() {
        for (req in withControl) {
            assertEquals(req.opCase.name, Code.CODE_NOT_ALLOWED, MacRules.refusal(req, grants(texts = true)))
            assertNull(req.opCase.name, MacRules.refusal(req, grants(control = true)))
        }
    }

    @Test fun `a text needs the texts grant`() {
        assertEquals(Code.CODE_NOT_ALLOWED, MacRules.refusal(text, grants(control = true)))
        assertNull(MacRules.refusal(text, grants(texts = true)))
    }

    @Test fun `a Mac never makes the phone's other requests`() {
        for (req in never) assertEquals(req.opCase.name, Code.CODE_NOT_ALLOWED, MacRules.refusal(req, grants(texts = true, control = true)))
    }

    @Test fun `a Mac's text goes only to a recent sender and needs a body of at most 500 bytes`() {
        val ok = text.sendText
        assertNull(MacRules.textRefusal(ok, answers = true))
        assertEquals(Code.CODE_NOT_ALLOWED, MacRules.textRefusal(ok, answers = false))
        assertEquals(Code.CODE_BAD_VALUE, MacRules.textRefusal(ok.toBuilder().setBody("").build(), answers = true))
        assertEquals(Code.CODE_BAD_VALUE, MacRules.textRefusal(ok.toBuilder().setBody("ş".repeat(251)).build(), answers = true))
        assertNull(MacRules.textRefusal(ok.toBuilder().setBody("ş".repeat(250)).build(), answers = true))
    }

    private val ownPeers = Peers.newBuilder().addPeers(Peer.newBuilder().setName("Mac").setGrants(grants(texts = true))).build()
    private val phoneState = State.newBuilder().setSeq(9)
        .setPower(Power.newBuilder().setBattery(80))
        .setLines(Lines.getDefaultInstance())
        .setHotspot(Hotspot.newBuilder().setName("Hermes").setDevices(2))
        .setRemote(Remote.newBuilder().setOn(true))
        .setPeers(Peers.newBuilder().setWindowOpen(true).setCode("123456").addPeers(Peer.newBuilder().setName("Other")))
        .build()

    private fun seen(state: State, g: PeerGrants): State =
        State.parseFrom(MacView.state(state.toByteArray(), MacRules.groups(g), ownPeers.toByteArray()))

    @Test fun `a Mac sees its groups with the same seq and only its own peer`() {
        val view = seen(phoneState, grants())
        assertEquals(9, view.seq)
        assertTrue(view.hasPower() && view.hasHotspot())
        assertEquals(2, view.hotspot.devices)
        assertFalse(view.hasLines() || view.hasRemote())
        assertEquals(ownPeers, view.peers)
        assertFalse(view.peers.windowOpen)
        assertEquals("", view.peers.code)
    }

    @Test fun `the lines and the hotspot's devices need the control grant`() {
        assertTrue(seen(phoneState, grants(control = true)).hasLines())
        assertTrue(StateBook.HOTSPOT_CLIENTS in MacRules.groups(grants(control = true)))
        assertFalse(StateBook.HOTSPOT_CLIENTS in MacRules.groups(grants(texts = true)))
    }

    @Test fun `a State with none of the Mac's groups still carries its seq`() {
        val view = MacView.state(State.newBuilder().setSeq(10).setRemote(Remote.getDefaultInstance()).build().toByteArray(),
            MacRules.groups(grants()), ownPeers.toByteArray())
        assertEquals(Fields.varint(StateBook.SEQ, 10).toList(), view.toList())
    }
}
