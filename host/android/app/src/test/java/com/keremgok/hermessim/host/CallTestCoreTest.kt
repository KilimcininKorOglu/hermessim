// HermesSIM host. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import com.keremgok.hermessim.wire.CallTestFault
import com.keremgok.hermessim.wire.CallTestReading
import com.keremgok.hermessim.wire.CallTestVerdict
import com.keremgok.hermessim.wire.HostSound
import com.keremgok.hermessim.wire.SupportTier
import org.junit.Assert.assertEquals
import org.junit.Test

/** Calls carry audio only after a test in which the host measured both directions and the user confirmed them. */
class CallTestCoreTest {
    private val good = CallTestVerdict.newBuilder().setPassed(true).setHeardCaller(true).setCallerHeard(true)
        .setHostSound(HostSound.HOST_SOUND_SILENT).build()
    private val measured = CallTestReading.newBuilder().setInCall(true).setVoicedFrames(50).setPlayedFrames(100).build()

    private fun fault(change: CallTestVerdict.Builder.() -> Unit = {}, reading: CallTestReading? = measured) =
        CallTestCore.fault(good.toBuilder().apply(change).build(), reading)

    @Test fun `the test passes when the reading and every answer are good`() {
        assertEquals(null, fault())
        assertEquals(null, fault({ hostSound = HostSound.HOST_SOUND_NOT_CHECKED }))
    }

    @Test fun `the first thing that is wrong is the fault, the host's own reading before the answers`() {
        val faults = listOf(
            fault({ passed = false }, reading = null),
            fault(reading = null),
            fault({ echo = true }, measured.toBuilder().setVoicedFrames(49).build()),
            fault({ callerHeard = false }, measured.toBuilder().setPlayedFrames(99).build()),
            fault({ callerHeard = false; echo = true }),
            fault({ echo = true; hostSound = HostSound.HOST_SOUND_HEARD }),
            fault({ hostSound = HostSound.HOST_SOUND_HEARD }),
        )
        assertEquals(
            listOf(CallTestFault.CALL_TEST_FAULT_NOT_CONFIRMED, CallTestFault.CALL_TEST_FAULT_NO_READING,
                CallTestFault.CALL_TEST_FAULT_NO_CALLER_VOICE, CallTestFault.CALL_TEST_FAULT_NOTHING_PLAYED,
                CallTestFault.CALL_TEST_FAULT_CALLER_HEARD_NOTHING, CallTestFault.CALL_TEST_FAULT_ECHO,
                CallTestFault.CALL_TEST_FAULT_HOST_SOUND),
            faults,
        )
    }

    @Test fun `every tier of the support table has its wire value`() {
        val tiers = Compat.Tier.values().map { CallTestCore.tier(it.name) }
        assertEquals(SupportTier.values().filter { it.name.startsWith("SUPPORT_TIER_") }.drop(1), tiers)
        assertEquals(SupportTier.SUPPORT_TIER_UNSPECIFIED, CallTestCore.tier("NEW_TIER"))
    }
}
