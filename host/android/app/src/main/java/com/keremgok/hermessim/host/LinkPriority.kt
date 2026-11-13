// HermesSIM host. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
package com.keremgok.hermessim.host

/**
 * The connection priority the host asks of a link (BleLink). A phone's link runs fast only while a call is up, for
 * its audio; idle, and on a Mac's link (no call audio), it asks for the low-power interval (about 100-125 ms), which
 * keeps the phone's and the Mac's radio asleep most of the time. Pure (LinkPriorityTest).
 */
object LinkPriority {
    /** BluetoothGatt.CONNECTION_PRIORITY_HIGH. */
    const val HIGH = 1
    /** BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER. */
    const val LOW_POWER = 2

    fun of(inCall: Boolean, mac: Boolean): Int = if (inCall && !mac) HIGH else LOW_POWER

    fun name(priority: Int): String = if (priority == HIGH) "HIGH" else "LOW_POWER"
}
