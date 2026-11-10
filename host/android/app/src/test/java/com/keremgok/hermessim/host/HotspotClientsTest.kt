// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The NEIGH answer gives each device its MAC and IP; the name comes from Android when it knows one. */
class HotspotClientsTest {
    @Test fun eachEntryKeepsItsIpApartFromItsName() {
        val got = HotspotClients.parse("OK 2 AA:BB:CC:00:11:22,192.168.43.10;de:ad:be:ef:00:01,192.168.43.11",
            mapOf("aa:bb:cc:00:11:22" to "MacBook"))!!.map { listOf(it.mac, it.ip, it.name) }
        assertEquals(listOf(listOf("aa:bb:cc:00:11:22", "192.168.43.10", "MacBook"),
            listOf("de:ad:be:ef:00:01", "192.168.43.11", "")), got)
    }

    @Test fun noDevicesIsAnEmptyListAndAFailedAnswerIsNull() {
        assertEquals(0, HotspotClients.parse("OK 0", emptyMap())!!.size)
        assertNull(HotspotClients.parse("ERR no iface", emptyMap()))
        assertNull(HotspotClients.parse(null, emptyMap()))
    }
}
