package com.keremgok.hermessim.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkRouteTest {
    /** The relay closes the socket on a message above 64 KB: a whole envelope must stay under it once sealed. */
    @Test
    fun anEnvelopeGoesWholeOverTheInternetOnlyWhileItsSealedMessageFitsTheRelay() {
        val sealed = Chunks.INTERNET_WHOLE + 8 + 12 + 16          // time, nonce, tag
        val message = (sealed + 2) / 3 * 4 + 512                  // base64, and the JSON event around it
        assertTrue("a whole envelope makes a $message byte message", message <= 64 * 1024)
        val cases = listOf(Chunks.INTERNET_WHOLE to false, Chunks.INTERNET_WHOLE + 1 to false, 4_096 to true, 4_097 to true)
        assertEquals(listOf(true, false, true, false), cases.map { (size, bluetooth) -> Chunks.goesWhole(size, bluetooth, frame = 4_096) })
    }
}
