package com.keremgok.hermessim.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftApExtrasTest {
    private fun e(id: Int, vararg bytes: Int) = AppleMarker.Element(id, bytes.map { it.toByte() }.toByteArray())
    private fun ids(list: List<AppleMarker.Element>) = list.map { it.id to it.body.toList() }

    @Test
    fun theMarkerIsApplesPersonalHotspotElement() {
        assertEquals(221, AppleMarker.ELEMENT_ID)
        assertEquals(listOf(0x00, 0x17, 0xF2, 0x06, 0x01, 0x01, 0x03, 0x01, 0x00, 0x00).map { it.toByte() },
            AppleMarker.BODY.toList())
    }

    @Test
    fun turningItOnAddsItOnceAndKeepsTheOtherElements() {
        val other = e(221, 0x00, 0x50, 0xF2, 0x04)
        val on = AppleMarker.applied(listOf(other), true)
        assertTrue(AppleMarker.present(on))
        assertEquals(ids(listOf(other, AppleMarker.Element(221, AppleMarker.BODY))), ids(on))
        assertEquals(ids(on), ids(AppleMarker.applied(on, true)))         // again: still one marker
    }

    @Test
    fun turningItOffRemovesAnyAppleHotspotElementButNothingElse() {
        val other = e(221, 0x00, 0x50, 0xF2, 0x04)
        val olderMarker = e(221, 0x00, 0x17, 0xF2, 0x06, 0x01)
        val off = AppleMarker.applied(listOf(olderMarker, other), false)
        assertFalse(AppleMarker.present(off))
        assertEquals(ids(listOf(other)), ids(off))
    }

    @Test
    fun aShortOrForeignElementIsNotTheMarker() {
        assertFalse(AppleMarker.present(listOf(e(221, 0x00, 0x17), e(10, 0x00, 0x17, 0xF2, 0x06))))
    }

    @Test
    fun theFrequencyIsTheFirstKnownOneElseUnknown() {
        assertEquals(2437, SoftApFrequency.of(listOf(0, 2437, 5180)))
        assertEquals(0, SoftApFrequency.of(listOf(0)))
        assertEquals(0, SoftApFrequency.of(emptyList()))
    }
}
