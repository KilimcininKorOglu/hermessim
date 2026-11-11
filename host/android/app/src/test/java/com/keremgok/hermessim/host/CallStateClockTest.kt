package com.keremgok.hermessim.host

import org.junit.Assert.assertEquals
import org.junit.Test

class CallStateClockTest {
    private var time = 1_000L
    private val clock = CallStateClock<String>({ time })

    @Test
    fun aReplayedStateKeepsTheTimeItBegan() {
        assertEquals(1_000L, clock.since("a", "ACTIVE"))
        time = 90_000L
        assertEquals(1_000L, clock.since("a", "ACTIVE"))
    }

    @Test
    fun aNewStateStartsAtItsOwnTime() {
        clock.since("a", "DIALING")
        time = 5_000L
        assertEquals(5_000L, clock.since("a", "ACTIVE"))
    }

    @Test
    fun callsAreTimedApartAndForgotten() {
        clock.since("a", "ACTIVE")
        time = 7_000L
        assertEquals(7_000L, clock.since("b", "ACTIVE"))
        clock.forget("a")
        assertEquals(7_000L, clock.since("a", "ACTIVE"))
    }
}
