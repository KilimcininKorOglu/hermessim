// HermesSIM host. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.bluetooth.BluetoothGatt
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkPriorityTest {
    @Test fun onlyAPhonesLinkDuringACallRunsFast() {
        // (in call, Mac) -> priority. A Mac's link carries no call audio: a call is no reason to wake its radio more often
        val table = mapOf(
            (true to false) to "HIGH",
            (false to false) to "LOW_POWER",
            (true to true) to "LOW_POWER",
            (false to true) to "LOW_POWER",
        )
        for ((link, want) in table) assertEquals("$link", want, LinkPriority.name(LinkPriority.of(link.first, link.second)))
    }

    @Test fun theValuesAreAndroids() {
        assertEquals(
            listOf(BluetoothGatt.CONNECTION_PRIORITY_HIGH, BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER),
            listOf(LinkPriority.HIGH, LinkPriority.LOW_POWER),
        )
    }
}
