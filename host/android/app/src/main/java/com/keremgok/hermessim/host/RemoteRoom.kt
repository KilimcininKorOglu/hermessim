// HermesSIM host. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
package com.keremgok.hermessim.host

/**
 * When the host keeps its relay socket (RemoteLink). The phone opens its own socket only while its Bluetooth link is
 * down, so the host has nothing to do in its room while the phone is linked over Bluetooth. An open socket then only
 * costs battery: the host and the server each ping every 25 s, which keeps the mobile radio awake. The host leaves
 * the room while a phone is on Bluetooth and joins it again the moment that link ends; pushes sent meanwhile take
 * HTTPS. Pure (RemoteRoomTest).
 */
object RemoteRoom {
    fun wanted(enabled: Boolean, phoneOnBluetooth: Boolean): Boolean = enabled && !phoneOnBluetooth
}
