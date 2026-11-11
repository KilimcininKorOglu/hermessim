// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import java.util.concurrent.ConcurrentHashMap

/**
 * When each call entered its current state, for CallEvent.state_since_ms: the same state keeps its first time, so a
 * replay after a reconnect tells the client when the call became active, not when the replay was built. Pure.
 */
class CallStateClock<S>(private val now: () -> Long) {
    private val since = ConcurrentHashMap<String, Pair<S, Long>>()

    /** The time [id] entered [state]: kept while the state stays, now when it changed. */
    fun since(id: String, state: S): Long {
        val known = since[id]
        if (known != null && known.first == state) return known.second
        val at = now()
        since[id] = state to at
        return at
    }

    fun forget(id: String) {
        since.remove(id)
    }
}
