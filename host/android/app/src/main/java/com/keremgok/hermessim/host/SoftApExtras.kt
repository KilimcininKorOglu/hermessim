// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

/**
 * Apple's Personal Hotspot element in the hotspot's beacon (Hotspot.apple_marker): a vendor-specific element (id 221)
 * under Apple's OUI 00:17:F2, type 6. A Mac that sees it treats the network as a phone's hotspot. Android takes vendor
 * elements from Android 13 on (SoftApConfiguration.Builder#setVendorElements).
 */
object AppleMarker {
    const val ELEMENT_ID = 221
    private val PREFIX = byteArrayOf(0x00, 0x17, 0xF2.toByte(), 0x06)
    val BODY = PREFIX + byteArrayOf(0x01, 0x01, 0x03, 0x01, 0x00, 0x00)

    /** One information element: its id and its body (without the id and length bytes). */
    class Element(val id: Int, val body: ByteArray)

    private fun isMarker(e: Element) = e.id == ELEMENT_ID && e.body.size >= PREFIX.size &&
        e.body.copyOfRange(0, PREFIX.size).contentEquals(PREFIX)

    fun present(elements: List<Element>): Boolean = elements.any(::isMarker)

    /** [elements] with the marker in or out; every other element stays as it was. */
    fun applied(elements: List<Element>, on: Boolean): List<Element> =
        elements.filterNot(::isMarker) + if (on) listOf(Element(ELEMENT_ID, BODY)) else emptyList()
}

/** The running hotspot's channel frequency from the SoftApInfo list Android reports (Hotspot.frequency_mhz). */
object SoftApFrequency {
    /** The first known frequency; a bridged (2.4 + 5 GHz) hotspot reports two and either one finds it. 0 = unknown. */
    fun of(frequencies: List<Int>): Int = frequencies.firstOrNull { it > 0 } ?: 0
}
