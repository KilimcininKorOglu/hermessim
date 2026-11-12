// HermesSIM host. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import com.google.protobuf.ByteString
import com.keremgok.hermessim.wire.AudioReport
import com.keremgok.hermessim.wire.Candidate
import com.keremgok.hermessim.wire.CandidateKind
import com.keremgok.hermessim.wire.Code
import com.keremgok.hermessim.wire.OpenAudioPath

/** The rules of a call's audio paths (protocol/README.md "Call audio"). Pure (AudioPathCoreTest). */
object AudioPathCore {
    enum class Via { BLUETOOTH, INTERNET }

    const val MAX_CANDIDATES = 4
    const val LOSS_MIN = 5
    const val LOSS_MAX = 30

    /** Why an OpenAudioPath can't be taken: its code and the field at fault, or null. */
    fun refusal(o: OpenAudioPath): Pair<Code, Int>? = when {
        o.audioId.size() != 16 -> Code.CODE_BAD_VALUE to OpenAudioPath.AUDIO_ID_FIELD_NUMBER
        o.key.size() != 32 -> Code.CODE_BAD_VALUE to OpenAudioPath.KEY_FIELD_NUMBER
        o.candidatesCount !in 1..MAX_CANDIDATES || !o.candidatesList.all(::valid) ||
            o.candidatesList.count { it.kind == CandidateKind.CANDIDATE_KIND_RELAY } > 1 ->
            Code.CODE_BAD_VALUE to OpenAudioPath.CANDIDATES_FIELD_NUMBER
        else -> null
    }

    private fun valid(c: Candidate) = (c.address.size() == 4 || c.address.size() == 16) && c.port in 1..65535 &&
        (c.kind == CandidateKind.CANDIDATE_KIND_DIRECT || c.kind == CandidateKind.CANDIDATE_KIND_RELAY)

    /** A candidate to offer: an IPv4 or IPv6 [address] and its [port]. */
    fun candidate(kind: CandidateKind, address: ByteArray, port: Int): Candidate {
        require(address.size == 4 || address.size == 16) { "an address of ${address.size} bytes" }
        return Candidate.newBuilder().setKind(kind).setAddress(ByteString.copyFrom(address)).setPort(port).build()
    }

    /** The host takes an allocation only when the phone brought no relay: a path has at most one. */
    fun hostRelays(phone: List<Candidate>): Boolean = phone.none { it.kind == CandidateKind.CANDIDATE_KIND_RELAY }

    /** The phone can be reached only over IPv6: the host's relay must be IPv6 too. */
    fun wantsIpv6(phone: List<Candidate>): Boolean = phone.isNotEmpty() && phone.all { it.address.size() == 16 }

    /**
     * Where the caller's audio goes: the way the phone's latest packet came, while it is still there; else Bluetooth,
     * else the internet path; null with neither.
     */
    fun via(last: Via?, bluetooth: Boolean, internet: Boolean): Via? {
        val up = { v: Via -> if (v == Via.BLUETOOTH) bluetooth else internet }
        return when {
            last != null && up(last) -> last
            bluetooth -> Via.BLUETOOTH
            internet -> Via.INTERNET
            else -> null
        }
    }

    /** The encoder's expected loss from the phone's report, 5 to 30 percent; null when the report counted nothing. */
    fun loss(r: AudioReport): Int? {
        val all = r.received.toLong() + r.lost
        if (all == 0L) return null
        return (r.lost * 100L / all).toInt().coerceIn(LOSS_MIN, LOSS_MAX)
    }

    /** The host's own counters, apart from the daemon's. */
    data class Local(val droppedSent: Long, val sendErrors: Long, val skippedBytes: Long)

    /**
     * The host's report for the time since the last one: the daemon's AUDIO? totals [now] against [before], and the
     * host's own counters. A total that went down started again (a new call): it counts from zero.
     */
    fun report(before: Map<String, Long>, now: Map<String, Long>, localBefore: Local, local: Local): AudioReport {
        fun d(key: String) = since(before[key] ?: 0, now[key] ?: 0)
        return AudioReport.newBuilder()
            .setReceived(d("recv").toInt())
            .setLost((d("plc") + d("fec")).toInt())
            .setLate(d("late").toInt())
            .setDroppedSent((d("txdrop") + since(localBefore.droppedSent, local.droppedSent)).toInt())
            .setSendErrors(since(localBefore.sendErrors, local.sendErrors).toInt())
            .setSkippedBytes(since(localBefore.skippedBytes, local.skippedBytes).toInt())
            .build()
    }

    private fun since(before: Long, now: Long) = if (now >= before) now - before else now
}
