// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import com.keremgok.hermessim.protocol.v1.MacReply
import com.keremgok.hermessim.protocol.v1.MacText
import org.junit.Assert.assertEquals
import org.junit.Test

/** A Mac answers only a text it can still be shown, to its sender, on the line it came in on. */
class MacReplyCoreTest {
    private var clock = 100_000_000L
    private val shelf = MacTextShelf { clock }.apply {
        add(MacText.newBuilder().setId("t1").setSender("+905551234567").setBody("Are you coming?").setAtMs(clock).build(),
            from = "05551234567", slot = 1)
    }

    private fun write(textId: String, body: String): ByteArray = with(MacReply.newBuilder()) {
        this.textId = textId
        this.body = body
        build().toByteArray()
    }

    @Test fun aReplyToAShownTextGoesToItsSenderOnItsLine() {
        assertEquals(MacReplyCore.Reply("05551234567", 1, "On my way"), MacReplyCore.reply(write("t1", " On my way "), shelf))
    }

    @Test fun anUnknownOrOldTextAnEmptyOrLongBodyOrNoMessageIsRefused() {
        val refused = listOf(
            write("nope", "Hi"), write("t1", "  "), write("t1", "x".repeat(501)), byteArrayOf(0x0a, 0x7f),
        ).map { MacReplyCore.reply(it, shelf) }
        assertEquals(listOf(null, null, null, null), refused)
        assertEquals("x".repeat(500), MacReplyCore.reply(write("t1", "x".repeat(500)), shelf)?.body)
        clock += MacTextShelf.WINDOW_MS
        assertEquals(null, MacReplyCore.reply(write("t1", "Hi"), shelf))
    }
}
