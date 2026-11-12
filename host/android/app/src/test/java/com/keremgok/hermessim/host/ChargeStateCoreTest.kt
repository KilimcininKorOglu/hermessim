package com.keremgok.hermessim.host

import com.keremgok.hermessim.wire.ChargeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChargeStateCoreTest {
    private fun reply(held: Int, stuck: Int = 0) = "OK cut target=80 resume=77 drain=82 cutting=$held held=$held stuck=$stuck"
    private fun state(reply: String?, level: Int, plugged: Boolean = true, limit: Int = 80, supported: Boolean = true) =
        ChargeStateCore.state(reply, limit, supported, plugged, level)

    @Test fun theAnswerIsReadAsKeyValuePairs() {
        assertEquals("1", ChargeStateCore.fields(reply(held = 1))!!["held"])
        assertNull(ChargeStateCore.fields("ERR unsupported"))
        assertNull(ChargeStateCore.fields(null))
    }

    @Test fun noControlNoLimitOrNoChargerIsNotLimiting() {
        assertEquals(ChargeState.CHARGE_STATE_NOT_LIMITING, state(reply(0), 50, supported = false))
        assertEquals(ChargeState.CHARGE_STATE_NOT_LIMITING, state(reply(0), 50, limit = 100))
        assertEquals(ChargeState.CHARGE_STATE_NOT_LIMITING, state(reply(0), 50, plugged = false))
    }

    @Test fun belowTheLimitItCharges() {
        assertEquals(ChargeState.CHARGE_STATE_CHARGING, state(reply(0), 60))
    }

    @Test fun atTheLimitItHoldsAndAboveItDrains() {
        assertEquals(ChargeState.CHARGE_STATE_HELD, state(reply(1), 80))
        assertEquals(ChargeState.CHARGE_STATE_HELD, state(reply(0), 80))      // a cap node stops it in the kernel
        assertEquals(ChargeState.CHARGE_STATE_DRAINING, state(reply(1), 85))
    }

    @Test fun aKernelThatUndoesTheCutIsStuckEvenUnplugged() {
        assertEquals(ChargeState.CHARGE_STATE_STUCK, state(reply(0, stuck = 1), 90, plugged = false))
    }

    @Test fun anUnreadableAnswerIsUnknown() {
        assertEquals(ChargeState.CHARGE_STATE_UNSPECIFIED, state(null, 60))
    }
}
