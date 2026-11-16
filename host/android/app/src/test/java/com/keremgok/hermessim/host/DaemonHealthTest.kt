package com.keremgok.hermessim.host

import com.keremgok.hermessim.host.DaemonHealth.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class DaemonHealthTest {
    private val ok = "OK hermessimd 0.1 card=0 call=0 session=0"

    @Test fun `no answer yet is a wait, not a failure`() =
        assertEquals(Verdict.WAIT, DaemonHealth.UNKNOWN.verdict)

    @Test fun `an OK reply is up in the same value that records the probe`() {
        val h = DaemonHealth.UNKNOWN.after(ok, 0)
        assertEquals(Verdict.UP, h.verdict)
        assertEquals(true, h.probed)
    }

    @Test fun `one failed answer on a fresh screen still waits`() =
        assertEquals(Verdict.WAIT, DaemonHealth.UNKNOWN.after(null, 1_000).verdict)

    @Test fun `two failed answers are down`() =
        assertEquals(Verdict.DOWN, DaemonHealth.UNKNOWN.after(null, 1_000).after(null, 3_000).verdict)

    @Test fun `one failed answer on a screen older than the grace is down`() =
        assertEquals(Verdict.DOWN, DaemonHealth.UNKNOWN.after(null, DaemonHealth.FIRST_MS + 1).verdict)

    @Test fun `an OK reply after failures is up and clears the count`() {
        val h = DaemonHealth.UNKNOWN.after(null, 1_000).after(null, 3_000).after(ok, 5_000)
        assertEquals(Verdict.UP, h.verdict)
        assertEquals(0, h.fails)
    }

    @Test fun `an error reply counts as a failed answer`() =
        assertEquals(Verdict.DOWN, DaemonHealth.UNKNOWN.after("ERR busy", 1_000).after("ERR busy", 3_000).verdict)
}
