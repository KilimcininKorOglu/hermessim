// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import com.keremgok.hermessim.protocol.v1.Box.ChargeState

/** What the charge limit does now (protocol Box.charge_state), from hermessimd's CHARGE? answer and the battery. Pure. */
object ChargeStateCore {
    /** CHARGE? answers "OK <node> target=.. resume=.. drain=.. cutting=.. held=0|1 stuck=0|1": its key=value pairs. */
    fun fields(reply: String?): Map<String, String>? {
        if (reply == null || !reply.startsWith("OK")) return null
        return reply.split(' ').mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
    }

    /**
     * [limit] is the target percent (100 = off); [plugged] says a charger is connected. A phone without charge control,
     * no limit or no charger is NOT_LIMITING; an unreadable answer is UNSPECIFIED.
     */
    fun state(reply: String?, limit: Int, supported: Boolean, plugged: Boolean, level: Int): ChargeState {
        if (!supported || limit >= 100) return ChargeState.NOT_LIMITING
        val f = fields(reply) ?: return ChargeState.CHARGE_STATE_UNSPECIFIED
        val held = f["held"] == "1"
        return when {
            f["stuck"] == "1" -> ChargeState.STUCK
            !plugged -> ChargeState.NOT_LIMITING
            held && level > limit -> ChargeState.DRAINING
            held || level >= limit -> ChargeState.HELD
            else -> ChargeState.CHARGING
        }
    }
}
