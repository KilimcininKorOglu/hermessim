package com.keremgok.hermessim.host

import org.junit.Assert.assertEquals
import org.junit.Test

class BtNameTest {
    @Test
    fun aNameLosesItsOuterSpacesAndAnEmptyOneIsRefused() {
        assertEquals(
            listOf(BtName.Check.Ok("Kerem's host"), BtName.Check.Empty),
            listOf("  Kerem's host  ", "   ").map(BtName::check),
        )
    }

    @Test
    fun theLimitCountsUtf8BytesNotLetters() {
        assertEquals(BtName.Check.Ok("a".repeat(29)), BtName.check("a".repeat(29)))
        assertEquals(BtName.Check.TooLong, BtName.check("a".repeat(30)))
        // 15 letters, 30 bytes: each "ş" takes two bytes
        assertEquals(BtName.Check.TooLong, BtName.check("ş".repeat(15)))
        assertEquals(BtName.Check.Ok("ş".repeat(14)), BtName.check("ş".repeat(14)))
    }

    @Test
    fun theCounterShowsHowManyPlainCharactersStillFit() {
        // 21 plain characters (spaces and "+" included) leave 8 of the 29
        assertEquals(8, BtName.left("Redmi Note 11 Pro+ 5G"))
        // outer spaces are dropped before saving, so they don't count
        assertEquals(8, BtName.left("   Redmi Note 11 Pro+ 5G  "))
        // "Ş" and "ö" take two each: 6 + 2 + 2 = 10 bytes
        assertEquals(19, BtName.left("Şeker öz"))
        // an emoji takes four
        assertEquals(25, BtName.left("📱"))
        assertEquals(29, BtName.left(""))
        assertEquals(0, BtName.left("a".repeat(29)))
        // over the limit: how many too many, as a negative number
        assertEquals(-1, BtName.left("ş".repeat(15)))
    }
}
