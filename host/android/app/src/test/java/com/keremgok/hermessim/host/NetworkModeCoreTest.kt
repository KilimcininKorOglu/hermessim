package com.keremgok.hermessim.host

import com.keremgok.hermessim.protocol.v1.Sim.NetworkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkModeCoreTest {
    private val gsm3g = (1L shl 15) or (1L shl 2) or (1L shl 7)       // GSM, UMTS, HSDPA
    private val fiveG = gsm3g or NetworkModeCore.LTE or NetworkModeCore.NR
    private val fourG = gsm3g or NetworkModeCore.LTE

    @Test fun aFiveGModemOffersEveryCeiling() {
        assertEquals(listOf(NetworkMode.AUTO, NetworkMode.UP_TO_4G, NetworkMode.UP_TO_3G), NetworkModeCore.modes(fiveG))
    }

    /** "Up to 4G" on a modem without 5G would be the same as automatic. */
    @Test fun aFourGModemHasNoUpTo4G() {
        assertEquals(listOf(NetworkMode.AUTO, NetworkMode.UP_TO_3G), NetworkModeCore.modes(fourG))
    }

    @Test fun nothingToChooseLeavesTheSettingOut() {
        assertEquals(emptyList<NetworkMode>(), NetworkModeCore.modes(gsm3g))
        assertEquals(emptyList<NetworkMode>(), NetworkModeCore.modes(0L))
        assertEquals(emptyList<NetworkMode>(), NetworkModeCore.modes(NetworkModeCore.LTE))
    }

    /** A ceiling keeps the lower generations, so a line falls back to them and still takes calls. */
    @Test fun aCeilingKeepsTheGenerationsBelowIt() {
        assertEquals(fourG, NetworkModeCore.mask(NetworkMode.UP_TO_4G, fiveG))
        assertEquals(gsm3g, NetworkModeCore.mask(NetworkMode.UP_TO_3G, fiveG))
        assertEquals(fiveG, NetworkModeCore.mask(NetworkMode.AUTO, fiveG))
    }

    @Test fun theModeReadBackIsTheModeSet() {
        for (supported in listOf(fiveG, fourG))
            for (mode in NetworkModeCore.modes(supported))
                assertEquals(mode, NetworkModeCore.modeOf(NetworkModeCore.mask(mode, supported), supported))
    }

    /** The phone's own default (every type allowed, more than the modem has) reads as automatic. */
    @Test fun anUnrestrictedLineIsAutomatic() {
        assertEquals(NetworkMode.AUTO, NetworkModeCore.modeOf(-1L, fiveG))
        assertEquals(NetworkMode.AUTO, NetworkModeCore.modeOf(-1L, fourG))
    }

    @Test fun aCallOrAChoiceTheModemLacksIsRefused() {
        assertNotNull(NetworkModeCore.refusal(NetworkMode.UP_TO_3G, fiveG, inCall = true))
        assertNotNull(NetworkModeCore.refusal(NetworkMode.UP_TO_4G, fourG, inCall = false))
        assertNull(NetworkModeCore.refusal(NetworkMode.UP_TO_3G, fourG, inCall = false))
    }
}
