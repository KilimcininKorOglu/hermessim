// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** After "Stop using this phone as a host" (Retire.Mark) and for calls the host doesn't relay (BasicSilence.wanted). */
class GivenBackTest {
    @Test fun noMarkIsAWorkingHost() {
        assertFalse(Retire.Mark.current(null, 12))
        assertFalse(Retire.Mark.current("", 12))
    }

    @Test fun theMarkHoldsUntilTheRestart() {
        assertTrue(Retire.Mark.current("12", 12))
        assertFalse(Retire.Mark.current("12", 13))          // restarted: the module is gone, or was kept on purpose
    }

    @Test fun withoutABootCountTheMarkHoldsTillTheBootScriptDropsIt() {
        assertTrue(Retire.Mark.current("null", -1))
        assertTrue(Retire.Mark.current("12", -1))
        assertTrue(Retire.Mark.current("null", 12))
    }

    @Test fun theClientsCallsStaySilencedWhoeverIsThePhoneApp() {
        assertTrue(BasicSilence.wanted(clientsCall = true, ourPhoneApp = true))
        assertTrue(BasicSilence.wanted(clientsCall = true, ourPhoneApp = false))
    }

    @Test fun anotherCallIsSilencedOnlyWhileTosbiSimIsThePhoneApp() {
        assertTrue(BasicSilence.wanted(clientsCall = false, ourPhoneApp = true))     // unchanged: a host in a drawer
        assertFalse(BasicSilence.wanted(clientsCall = false, ourPhoneApp = false))   // a person answered it on this phone
    }
}
