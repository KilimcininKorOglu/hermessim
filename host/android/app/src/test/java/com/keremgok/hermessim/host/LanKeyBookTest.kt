// HermesSIM host. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import com.keremgok.hermessim.host.wire.LanFrames
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Each peer's local network key (protocol/README.md "Local network": one key per peer, the same one after). */
class LanKeyBookTest {
    private var made = 0
    private fun book() = LanKeyBook(HashMap()) { n -> ByteArray(n) { (++made).toByte() } }

    @Test fun `a peer gets its own key, and the same key when it asks again`() {
        val book = book()
        val phone = book.keyFor("phone")
        val mac = book.keyFor("mac")
        assertEquals(LanKeyBook.KEY_BYTES, phone.size)
        assertFalse(phone.contentEquals(mac))
        assertArrayEquals(phone, book.keyFor("phone"))
    }

    @Test fun `a key id finds only the peer its key was made for`() {
        val book = book()
        val phone = book.keyFor("phone")
        val mac = book.keyFor("mac")
        assertEquals("phone", book.peerOf(LanFrames.keyId(phone)))
        assertEquals("mac", book.peerOf(LanFrames.keyId(mac)))
        // A forged id names nobody
        assertNull(book.peerOf(ByteArray(LanFrames.KEY_ID_BYTES)))
    }

    @Test fun `a forgotten peer's key opens nothing`() {
        val book = book()
        val phone = book.keyFor("phone")
        book.forget("phone")
        assertNull(book.peerOf(LanFrames.keyId(phone)))
    }
}
