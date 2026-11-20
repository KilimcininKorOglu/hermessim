// HermesSIM host. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
package com.keremgok.hermessim.host

/**
 * The advertising of a host waiting for its first phone (protocol/v2/SPEC.md "Bluetooth"). A legacy packet holds
 * 31 bytes: Android's flags take 3, the marker's manufacturer data 6 (length, type, company, 2-byte marker) and the
 * name's header 2, which leaves [NAME_MAX_BYTES] for the name. Pure.
 */
object PairAdvert {
    /** The Bluetooth SIG's company identifier reserved for tests and unassigned use. */
    const val COMPANY_ID = 0xFFFF
    /** "HS": tells a HermesSIM host apart from other devices with the same company identifier. */
    val MARKER = byteArrayOf(0x48, 0x53)
    const val NAME_MAX_BYTES = 31 - 3 - (4 + 2) - 2

    /** True when the Bluetooth name [name] fits in the first packet beside the marker. */
    fun nameFits(name: String?): Boolean {
        val bytes = name?.toByteArray(Charsets.UTF_8)?.size ?: return false
        return bytes in 1..NAME_MAX_BYTES
    }
}
