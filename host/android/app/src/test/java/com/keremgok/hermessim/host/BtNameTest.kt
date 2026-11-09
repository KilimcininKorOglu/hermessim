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
}
