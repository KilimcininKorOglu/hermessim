// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

/** A device on the hotspot, or one blocked from it: its MAC as lowercase text, and what the host knows of it. */
data class ApClient(val mac: String, val ip: String = "", val name: String = "", val down: Long = 0, val up: Long = 0) {
    /** The MAC as its 6 bytes; empty when [mac] is not one. */
    fun macBytes(): ByteArray = ApClient.bytes(mac)

    companion object {
        fun bytes(mac: String): ByteArray {
            val parts = mac.split(':')
            if (parts.size != 6 || parts.any { it.length != 2 || it.toIntOrNull(16) == null }) return ByteArray(0)
            return ByteArray(6) { parts[it].toInt(16).toByte() }
        }

        /** Six bytes as "aa:bb:cc:dd:ee:ff"; empty for any other length. */
        fun text(mac: ByteArray): String = if (mac.size != 6) "" else mac.joinToString(":") { "%02x".format(it.toInt() and 0xFF) }
    }
}

/** The devices on the hotspot, from hermessimd's NEIGH answer. Pure. */
object HotspotClients {
    /**
     * NEIGH answers "OK <n> mac,ip;mac,ip;...": one client per entry with its MAC and IP. The name is the one Android's
     * TetheredClient gave for that MAC ([names], lowercase MAC keys), or empty. Null for a failed answer.
     */
    fun parse(reply: String?, names: Map<String, String>): List<ApClient>? {
        if (reply == null || !reply.startsWith("OK")) return null
        return reply.removePrefix("OK ").substringAfter(' ', "").split(';').filter { it.contains(',') }.map { e ->
            val mac = e.substringBefore(',').lowercase()
            ApClient(mac, e.substringAfter(','), names[mac].orEmpty())
        }
    }
}
