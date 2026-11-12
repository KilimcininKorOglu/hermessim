// HermesSIM host. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import com.keremgok.hermessim.host.wire.Fields
import com.keremgok.hermessim.host.wire.StateBook
import com.keremgok.hermessim.wire.Code
import com.keremgok.hermessim.wire.HostVerb
import com.keremgok.hermessim.wire.PeerGrants
import com.keremgok.hermessim.wire.Request
import com.keremgok.hermessim.wire.SendText
import java.io.ByteArrayOutputStream

/** What a Mac may ask and see (protocol/v2/SPEC.md "Macs"). Pure. */
object MacRules {
    /** The longest text a Mac may send, in UTF-8 bytes. */
    const val MAX_TEXT_BYTES = 500

    private val everyMac = setOf(Request.OpCase.GET_STATE, Request.OpCase.SET_HOTSPOT, Request.OpCase.GET_HOTSPOT_SECRET)
    private val withControl = setOf(Request.OpCase.SET_DATA_LINE, Request.OpCase.SET_HOTSPOT_CLIENT, Request.OpCase.HOST_ACTION)

    private val seen = setOf(StateBook.POWER, StateBook.NETWORK, StateBook.SIGNAL, StateBook.HOTSPOT, StateBook.HOTSPOT_LIMITS)
    private val seenWithControl = setOf(StateBook.LINES, StateBook.HOTSPOT_CLIENTS)

    /** CODE_NOT_ALLOWED when a Mac with [grants] may not make [req], else null. */
    fun refusal(req: Request, grants: PeerGrants): Code? = if (allowed(req, grants)) null else Code.CODE_NOT_ALLOWED

    private fun allowed(req: Request, grants: PeerGrants): Boolean = when {
        req.opCase in everyMac -> true
        // A device on the hotspot failed the hotspot check: taken from every Mac
        req.opCase == Request.OpCase.HOST_ACTION && req.hostAction.verb == HostVerb.HOST_VERB_HOTSPOT_NO_INTERNET -> true
        req.opCase in withControl -> grants.control
        req.opCase == Request.OpCase.SEND_TEXT -> grants.texts
        else -> false
    }

    /**
     * Why a Mac's text is not sent, or null. [answers]: the text goes to the sender of a text the Mac was given, on
     * that text's line (MacTextShelf.allows).
     */
    fun textRefusal(text: SendText, answers: Boolean): Code? = when {
        !answers -> Code.CODE_NOT_ALLOWED
        text.body.isEmpty() || text.body.toByteArray().size > MAX_TEXT_BYTES -> Code.CODE_BAD_VALUE
        else -> null
    }

    /** The State groups a Mac with [grants] gets, apart from `peers`, which it gets as its own entry. */
    fun groups(grants: PeerGrants): Set<Int> = if (grants.control) seen + seenWithControl else seen
}

/** A `State` as one Mac sees it. Pure. */
object MacView {
    /**
     * [state] with only the [groups] the Mac may see and its own [peers] in place of the `peers` group, when that
     * group is in [state]. The seq stays, so the Mac sees no gap; a State with none of its groups carries the seq only.
     */
    fun state(state: ByteArray, groups: Set<Int>, peers: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for ((field, bytes) in Fields.split(state).toSortedMap()) {
            when (field) {
                StateBook.SEQ, in groups -> out.write(bytes)
                StateBook.PEERS -> out.write(Fields.bytes(StateBook.PEERS, peers))
            }
        }
        return out.toByteArray()
    }
}
