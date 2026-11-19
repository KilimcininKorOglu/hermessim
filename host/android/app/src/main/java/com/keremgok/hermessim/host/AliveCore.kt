// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import com.keremgok.hermessim.wire.Event
import com.keremgok.hermessim.wire.Notice
import com.keremgok.hermessim.wire.NoticeKind
import com.keremgok.hermessim.wire.Packet

/**
 * The alive push (protocol/README.md "Alive push"). Pure: a host that is off can't say so, so every [EVERY_MS] it hands
 * the server a ready "unreachable" notice, which the server sends when 6 hours pass without a newer one.
 */
object AliveCore {
    /** How often the host says it is on; the server's 6-hour silence starts at the last one. */
    const val EVERY_MS = 15 * 60 * 1000L

    /** The first request after the host starts or gets a new push key. */
    const val FIRST_MS = 60 * 1000L

    /** The phone names its own unreachable notification so, and the two replace each other. */
    const val COLLAPSE_NAME = "notice-unreachable"

    /** The packet of the ready push: an unreachable notice stamped with the time of this request. */
    fun packet(nowMs: Long): ByteArray = Packet.newBuilder()
        .setEvent(Event.newBuilder().setNotice(Notice.newBuilder().setKind(NoticeKind.NOTICE_KIND_UNREACHABLE).setAtMs(nowMs)))
        .build().toByteArray()
}
