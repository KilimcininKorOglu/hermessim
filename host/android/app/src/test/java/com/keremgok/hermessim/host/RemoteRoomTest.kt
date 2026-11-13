// HermesSIM host. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteRoomTest {
    /** The room is kept only with internet mode on and no phone on Bluetooth: the phone uses the room only then. */
    @Test fun theHostWaitsInItsRoomOnlyWhileThePhoneIsAwayFromBluetooth() {
        val seen = listOf(true to false, true to true, false to false, false to true)
            .map { (enabled, near) -> RemoteRoom.wanted(enabled, near) }
        assertEquals(listOf(true, false, false, false), seen)
    }
}
