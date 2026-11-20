package com.keremgok.hermessim.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairAdvertTest {
    /** 31 bytes - flags 3 - marker 6 - name header 2: a name fits the iPhone-read packet up to 20 bytes. */
    @Test
    fun aNameFitsBesideTheMarkerUpToTwentyBytes() {
        assertEquals(20, PairAdvert.NAME_MAX_BYTES)
        assertTrue(PairAdvert.nameFits("Redmi Note 8 Pro"))
        assertTrue(PairAdvert.nameFits("a".repeat(20)))
        assertFalse(PairAdvert.nameFits("a".repeat(21)))
        assertFalse(PairAdvert.nameFits("ş".repeat(11)))      // 22 bytes in UTF-8
        assertFalse(PairAdvert.nameFits(""))
        assertFalse(PairAdvert.nameFits(null))
    }

    /** The marker sits under the company identifier the iPhone's sheet and the client check. */
    @Test
    fun theMarkerIsHsUnderTheReservedCompany() {
        assertEquals(0xFFFF, PairAdvert.COMPANY_ID)
        assertEquals(listOf<Byte>(0x48, 0x53), PairAdvert.MARKER.toList())
    }
}
