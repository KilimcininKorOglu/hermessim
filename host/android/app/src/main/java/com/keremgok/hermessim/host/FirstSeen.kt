// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

/** Ids seen before, the last [limit] of them: [first] is true once per id. Thread-safe and pure. */
class FirstSeen(private val limit: Int) {
    private val seen = LinkedHashSet<String>()

    @Synchronized
    fun first(id: String): Boolean {
        if (!seen.add(id)) return false
        while (seen.size > limit) seen.remove(seen.first())
        return true
    }
}
