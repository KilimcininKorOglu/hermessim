// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.telephony.TelephonyManager
import com.keremgok.hermessim.wire.Code
import org.junit.Assert.assertEquals
import org.junit.Test

/** Only real USSD codes run, so a client can't make the host dial or send anything else. */
class UssdCodeTest {
    @Test fun onlyStarOrHashCodesEndingInHashWithDigitsRun() {
        val longest = "*" + "1".repeat(30) + "#"                  // 32 characters
        val codes = listOf("*123#", "#100#", "*100*2*1#", "*#06#", longest, "123#", "*123", "*12a#", "*123# ", "#", "*1$longest")
        assertEquals(listOf(true, true, true, true, true, false, false, false, false, false, false), codes.map(UssdCode::valid))
    }

    @Test fun anUnavailableServiceIsNoServiceAndEveryOtherFailureIsTheNetworks() {
        val failures = listOf(TelephonyManager.USSD_ERROR_SERVICE_UNAVAIL, TelephonyManager.USSD_RETURN_FAILURE, 99)
            .map(UssdCode::failure)
        assertEquals(listOf(Code.CODE_NO_SERVICE, Code.CODE_NETWORK_REFUSED, Code.CODE_NETWORK_REFUSED), failures)
    }
}
