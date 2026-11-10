// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import com.keremgok.hermessim.protocol.v1.Hotspot as HotspotMsg

/** The devices on the hotspot, from hermessimd's NEIGH answer. Pure. */
object HotspotClients {
    /**
     * NEIGH answers "OK <n> mac,ip;mac,ip;...": one Client per entry with its MAC and IP. The name is the one Android's
     * TetheredClient gave for that MAC ([names], lowercase MAC keys), or empty. Null for a failed answer.
     */
    fun parse(reply: String?, names: Map<String, String>): List<HotspotMsg.Client>? {
        if (reply == null || !reply.startsWith("OK")) return null
        return reply.removePrefix("OK ").substringAfter(' ', "").split(';').filter { it.contains(',') }.map { e ->
            val mac = e.substringBefore(',').lowercase()
            with(HotspotMsg.Client.newBuilder()) {
                this.mac = mac
                ip = e.substringAfter(',')
                name = names[mac].orEmpty()
                build()
            }
        }
    }
}
