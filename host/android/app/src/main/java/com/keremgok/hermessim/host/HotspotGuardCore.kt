// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

/**
 * Hotspot devices: blocking a device's internet and counting each device's data, as iptables rules (run as root).
 * Two chains hang at the top of FORWARD: [BLOCK] drops what a blocked MAC sends (IPv4 and IPv6; the device stays on
 * the hotspot), [COUNT] holds one target-less rule per direction and device IP, so iptables counts its bytes and the
 * packet goes on. New devices only add count rules, so earlier counts stay; the chains go when the hotspot stops.
 * Counts are IPv4 only (NEIGH gives IPv4 addresses). Pure: builds the scripts and reads the counters.
 */
object HotspotGuardCore {
    const val BLOCK = "hermessim_hsblock"
    const val COUNT = "hermessim_hscount"
    const val MAX_BLOCKED = 32

    private val MAC = Regex("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")
    private val IPV4 = Regex("^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$")
    private val IFACE = Regex("^[A-Za-z0-9_.-]{1,15}$")

    fun validMac(mac: String) = MAC.matches(mac)
    fun validIp(ip: String) = IPV4.matches(ip)

    /** The blocked list after one change: lowercase, valid MACs only, no repeats, at most [MAX_BLOCKED]; null when full. */
    fun change(list: List<String>, mac: String, blocked: Boolean): List<String>? {
        val m = mac.lowercase()
        if (!validMac(m)) return list
        if (!blocked) return list - m
        if (m in list) return list
        return if (list.size >= MAX_BLOCKED) null else list + m
    }

    /** A chain exists, is empty, and FORWARD jumps to it at [position] (once). For iptables and ip6tables alike. */
    private fun hook(tool: String, chain: String, position: Int, flush: Boolean) = listOfNotNull(
        "$tool -w -N $chain 2>/dev/null",
        if (flush) "$tool -w -F $chain" else null,
        "$tool -w -C FORWARD -j $chain 2>/dev/null || $tool -w -I FORWARD $position -j $chain",
    )

    /** The block chain holds exactly [blocked] (valid MACs only) on [iface], in both IP families. */
    fun blockScript(iface: String, blocked: List<String>): String {
        require(IFACE.matches(iface)) { "bad interface" }
        val macs = blocked.filter(::validMac)
        return listOf("iptables", "ip6tables").flatMap { tool ->
            hook(tool, BLOCK, 1, flush = true) + macs.map { "$tool -w -A $BLOCK -i $iface -m mac --mac-source $it -j DROP" }
        }.joinToString("\n")
    }

    /** Count rules for [ips] (valid IPv4 only) that [counted] doesn't have yet; the chain and its hook if missing. */
    fun countScript(ips: Collection<String>, counted: Set<String>): String {
        val fresh = ips.filter { validIp(it) && it !in counted }.distinct()
        return (hook("iptables", COUNT, 2, flush = false) +
            fresh.flatMap { listOf("iptables -w -A $COUNT -s $it", "iptables -w -A $COUNT -d $it") }).joinToString("\n")
    }

    /** Both chains unhooked and gone, in both families. */
    fun teardownScript(): String = listOf("iptables", "ip6tables").flatMap { tool ->
        listOf(BLOCK, COUNT).flatMap { chain ->
            listOf("$tool -w -D FORWARD -j $chain 2>/dev/null", "$tool -w -F $chain 2>/dev/null", "$tool -w -X $chain 2>/dev/null")
        }
    }.joinToString("\n")

    const val READ = "iptables -w -nvxL $COUNT"

    /** Bytes per device IP from [READ]'s output: down (to the device) and up (from it). Unreadable lines are skipped. */
    fun parse(output: String): Map<String, Usage> {
        val out = mutableMapOf<String, Usage>()
        for (line in output.lines()) {
            val t = line.trim().split(Regex("\\s+"))
            val bytes = t.getOrNull(1)?.toLongOrNull() ?: continue
            if (t.size < 4) continue
            val (src, dst) = t[t.size - 2] to t[t.size - 1]
            when {
                validIp(src) && dst == ANY -> out[src] = (out[src] ?: Usage()).copy(up = bytes)
                validIp(dst) && src == ANY -> out[dst] = (out[dst] ?: Usage()).copy(down = bytes)
            }
        }
        return out
    }

    private const val ANY = "0.0.0.0/0"

    data class Usage(val down: Long = 0, val up: Long = 0)
}
