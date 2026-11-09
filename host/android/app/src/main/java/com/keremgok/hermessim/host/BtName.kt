// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

/**
 * The name this phone shows over Bluetooth, which client phones see when they add the host. The scan response carries
 * it only when it fits ([BleLink] scanResponse: 31 bytes less the 2-byte header), so a longer name is refused here
 * rather than silently left out of the advertising.
 */
object BtName {
    const val MAX_BYTES = 29

    sealed interface Check {
        data class Ok(val name: String) : Check
        data object Empty : Check
        data object TooLong : Check
    }

    /** [input] without its outer spaces, if it is not empty and fits in [MAX_BYTES] UTF-8 bytes. */
    fun check(input: String): Check {
        val name = input.trim()
        return when {
            name.isEmpty() -> Check.Empty
            name.toByteArray(Charsets.UTF_8).size > MAX_BYTES -> Check.TooLong
            else -> Check.Ok(name)
        }
    }
}
