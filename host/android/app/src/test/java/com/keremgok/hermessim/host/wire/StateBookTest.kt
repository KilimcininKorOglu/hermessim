package com.keremgok.hermessim.host.wire

import com.keremgok.hermessim.wire.Hotspot
import com.keremgok.hermessim.wire.HotspotPhase
import com.keremgok.hermessim.wire.Packet
import com.keremgok.hermessim.wire.Power
import com.keremgok.hermessim.wire.Signal
import com.keremgok.hermessim.wire.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StateBookTest {
    private fun power(battery: Int, charging: Boolean = false) =
        Power.newBuilder().setBattery(battery).setCharging(charging).setBoxMode(true).setChargeLimit(80).setBoxPaused(false).build().toByteArray()

    private val hotspot = Hotspot.newBuilder().setPhase(HotspotPhase.HOTSPOT_PHASE_ON).setName("Hermes").build().toByteArray()

    private fun book() = StateBook(epoch = 7).apply {
        put(StateBook.POWER, power(57))
        put(StateBook.HOTSPOT, hotspot)
        put(StateBook.SIGNAL, Signal.newBuilder().addBars(3).build().toByteArray())
        next()
    }

    /** The target of the rewrite: a battery change is 12 bytes on the link, not a whole status. */
    @Test
    fun aBatteryChangeSendsOnlyTheBatteryAndFitsTwelveBytes() {
        val book = book()
        assertTrue(book.put(StateBook.POWER, power(56)))
        val state = book.next()!!
        val parsed = State.parseFrom(state)
        assertEquals(2, parsed.seq)
        assertEquals(Power.newBuilder().setBattery(56).build(), parsed.power)
        assertFalse(parsed.hasHotspot())
        val frame = FrameCodec.encode(Frame(Wire.DATA, Fields.bytes(6, state), stream = Wire.NORMAL))
        assertTrue("the frame is ${frame.size} bytes", frame.size <= 12)
        assertEquals(parsed, Packet.parseFrom(Fields.bytes(6, state)).state)
    }

    @Test
    fun nothingIsSentWhenNothingChangedAndAValueOfZeroIsAChange() {
        val book = book()
        assertFalse(book.put(StateBook.POWER, power(57)))
        assertNull(book.next())
        assertTrue(book.put(StateBook.POWER, power(0)))
        val sent = State.parseFrom(book.next()!!).power
        assertTrue(sent.hasBattery())
        assertEquals(0, sent.battery)
    }

    @Test
    fun aPeerThatComesBackGetsOnlyTheGroupsThatChangedAfterItsMark() {
        val book = book()                                    // seq 1: power, hotspot, signal
        book.put(StateBook.SIGNAL, Signal.newBuilder().addBars(1).build().toByteArray())
        book.next()                                          // seq 2: signal
        val caughtUp = State.parseFrom(book.since(markEpoch = 7, markSeq = 1))
        assertEquals(listOf(2, true, false, false), listOf(caughtUp.seq, caughtUp.hasSignal(), caughtUp.hasPower(), caughtUp.hasHotspot()))
        // A group sent after a Hello is whole, also one that is compared by field.
        val fresh = State.parseFrom(book.since(markEpoch = 6, markSeq = 2))
        assertEquals(Power.parseFrom(power(57)), fresh.power)
        assertTrue(fresh.hasHotspot() && fresh.hasSignal())
        val asked = State.parseFrom(book.whole(listOf(StateBook.HOTSPOT)))
        assertEquals(listOf(true, false), listOf(asked.hasHotspot(), asked.hasSignal()))
    }
}
