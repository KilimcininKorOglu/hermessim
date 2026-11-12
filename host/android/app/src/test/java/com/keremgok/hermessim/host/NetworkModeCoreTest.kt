package com.keremgok.hermessim.host

import com.keremgok.hermessim.wire.Code
import com.keremgok.hermessim.wire.NetworkCeiling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkModeCoreTest {
    private val auto = NetworkCeiling.NETWORK_CEILING_NONE
    private val upTo4G = NetworkCeiling.NETWORK_CEILING_4G
    private val upTo3G = NetworkCeiling.NETWORK_CEILING_3G
    private val gsm3g = (1L shl 15) or (1L shl 2) or (1L shl 7)       // GSM, UMTS, HSDPA
    private val fiveG = gsm3g or NetworkModeCore.LTE or NetworkModeCore.NR
    private val fourG = gsm3g or NetworkModeCore.LTE

    @Test fun aFiveGModemOffersEveryCeiling() {
        assertEquals(listOf(auto, upTo4G, upTo3G), NetworkModeCore.modes(fiveG))
    }

    /** "Up to 4G" on a modem without 5G would be the same as automatic. */
    @Test fun aFourGModemHasNoUpTo4G() {
        assertEquals(listOf(auto, upTo3G), NetworkModeCore.modes(fourG))
    }

    @Test fun nothingToChooseLeavesTheSettingOut() {
        assertEquals(emptyList<NetworkCeiling>(), NetworkModeCore.modes(gsm3g))
        assertEquals(emptyList<NetworkCeiling>(), NetworkModeCore.modes(0L))
        assertEquals(emptyList<NetworkCeiling>(), NetworkModeCore.modes(NetworkModeCore.LTE))
    }

    /** A ceiling keeps the lower generations, so a line falls back to them and still takes calls. */
    @Test fun aCeilingKeepsTheGenerationsBelowIt() {
        assertEquals(fourG, NetworkModeCore.mask(upTo4G, fiveG))
        assertEquals(gsm3g, NetworkModeCore.mask(upTo3G, fiveG))
        assertEquals(fiveG, NetworkModeCore.mask(auto, fiveG))
    }

    @Test fun theModeReadBackIsTheModeSet() {
        for (supported in listOf(fiveG, fourG))
            for (mode in NetworkModeCore.modes(supported))
                assertEquals(mode, NetworkModeCore.modeOf(NetworkModeCore.mask(mode, supported), supported))
    }

    /** The phone's own default (every type allowed, more than the modem has) reads as automatic. */
    @Test fun anUnrestrictedLineIsAutomatic() {
        assertEquals(auto, NetworkModeCore.modeOf(-1L, fiveG))
        assertEquals(auto, NetworkModeCore.modeOf(-1L, fourG))
    }

    @Test fun aCallOrAChoiceTheModemLacksIsRefused() {
        assertEquals(Code.CODE_IN_CALL, NetworkModeCore.refusal(upTo3G, fiveG, inCall = true))
        assertEquals(Code.CODE_NOT_OFFERED, NetworkModeCore.refusal(upTo4G, fourG, inCall = false))
        assertNull(NetworkModeCore.refusal(upTo3G, fourG, inCall = false))
    }
}
