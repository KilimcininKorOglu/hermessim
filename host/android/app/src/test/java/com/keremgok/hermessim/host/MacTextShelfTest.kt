package com.keremgok.hermessim.host

import com.keremgok.hermessim.wire.TextIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The texts a Mac gets: the 5 newest of the last 10 minutes, and only their senders may be answered. */
class MacTextShelfTest {
    private var clock = 100_000_000L
    private val shelf = MacTextShelf { clock }

    private fun text(minutesAgo: Int, from: String = "+905550000000", line: Int? = 1): TextIn = TextIn.newBuilder()
        .setFrom(from).setBody("Your code is 123456").setAtMs(clock - minutesAgo * 60_000L)
        .apply { if (line != null) setLine(line) }.build()

    private fun seqs() = shelf.recent().map { it.seq }

    @Test
    fun theNewestFiveOfTheLastTenMinutesAreKeptOldestFirst() {
        listOf(1L to 12, 2L to 9, 3L to 1, 4L to 2, 5L to 3, 6L to 4, 7L to 5).forEach { (seq, ago) -> shelf.add(seq, text(ago)) }
        assertEquals(listOf(7L, 6L, 5L, 4L, 3L), seqs())
        clock += 6 * 60_000L
        assertEquals(listOf(5L, 4L, 3L), seqs())                        // 6 and 7 are 10 minutes old or more now
    }

    @Test
    fun aTextAddedTwiceIsKeptOnce() {
        shelf.add(1, text(1)); shelf.add(1, text(1))
        assertEquals(listOf(1L), seqs())
    }

    @Test
    fun onlyTheSenderOfARecentTextOnItsLineMayBeAnswered() {
        shelf.add(1, text(1, from = "5554", line = 0))
        shelf.add(2, text(2, from = "BANK", line = null))
        assertTrue(shelf.allows("5554", 0))
        assertFalse(shelf.allows("5554", 1))                             // another line
        assertFalse(shelf.allows("+905551112233", 0))                   // nobody who texted
        assertTrue(shelf.allows("BANK", null))
        clock += 10 * 60_000L
        assertFalse(shelf.allows("5554", 0))                             // too old now
    }
}
