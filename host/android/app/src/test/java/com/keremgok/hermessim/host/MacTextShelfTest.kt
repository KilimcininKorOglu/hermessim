package com.keremgok.hermessim.host

import com.keremgok.hermessim.protocol.v1.MacText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MacTextShelfTest {
    private var clock = 100_000_000L
    private val shelf = MacTextShelf { clock }

    private fun text(id: String, minutesAgo: Int, body: String = "Your code is 123456"): MacText = with(MacText.newBuilder()) {
        this.id = id
        sender = "+905550000000"
        this.body = body
        atMs = clock - minutesAgo * 60_000L
        build()
    }

    private fun ids() = shelf.value().textsList.map { it.id }

    @Test
    fun theNewestFiveOfTheLastTenMinutesAreShownNewestFirst() {
        listOf("a" to 12, "b" to 9, "c" to 1, "d" to 2, "e" to 3, "f" to 4, "g" to 5).forEach { (id, ago) -> shelf.add(text(id, ago)) }
        assertEquals(listOf("c", "d", "e", "f", "g"), ids())
        clock += 6 * 60_000L
        assertEquals(listOf("c", "d", "e"), ids())                       // f and g are 10 minutes old or more now
    }

    @Test
    fun aTextAddedTwiceIsShownOnce() {
        shelf.add(text("a", 1)); shelf.add(text("a", 1))
        assertEquals(listOf("a"), ids())
    }

    @Test
    fun theOldestDropFirstAndALoneLongTextIsCutToFit() {
        val long = "Ş".repeat(150)                                      // 300 bytes of body each
        shelf.add(text("old", 2, long)); shelf.add(text("new", 1, long))
        assertEquals(listOf("new"), ids())
        shelf.add(text("huge", 0, "Kod 4321 " + "x".repeat(900)))
        val value = shelf.value()
        assertEquals(listOf("huge"), value.textsList.map { it.id })
        assertTrue(value.serializedSize <= MacTextShelf.MAX_BYTES)
        assertTrue(value.getTexts(0).body.startsWith("Kod 4321"))
    }
}
