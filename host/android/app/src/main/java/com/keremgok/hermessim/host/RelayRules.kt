// HermesSIM host. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import com.keremgok.hermessim.wire.CallVerb
import com.keremgok.hermessim.wire.Code
import com.keremgok.hermessim.wire.Feature
import com.keremgok.hermessim.wire.Hello
import com.keremgok.hermessim.wire.Request
import com.keremgok.hermessim.wire.Role
import com.keremgok.hermessim.host.wire.commonVersion

/** Which requests the host runs, apart from the phone (protocol/v2/SPEC.md "Requests"). Pure. */
object RequestRules {
    const val CALL_AGE_MS = 10_000
    const val CHANGE_AGE_MS = 30_000

    private val callOps = setOf(Request.OpCase.DIAL, Request.OpCase.CALL_CONTROL, Request.OpCase.SEND_TONES)

    /** Requests that change nothing, or that carry their own guard against a second run (SendText.client_ref). */
    private val ageless = setOf(
        Request.OpCase.SEND_TEXT, Request.OpCase.GET_STATE, Request.OpCase.GET_LOG, Request.OpCase.GET_HOTSPOT_SECRET,
        Request.OpCase.SCAN_WIFI, Request.OpCase.CALL_TEST_CHECK, Request.OpCase.SCHEDULE_TEXT,
    )

    /** The requests a test rig's override of the phone's presence makes the host leave alone. */
    val heldByRig = setOf(Request.OpCase.SET_HOTSPOT, Request.OpCase.SET_HOST_WIFI, Request.OpCase.SET_MOBILE_DATA)

    fun tooOld(op: Request.OpCase, ageMs: Int): Boolean = when (op) {
        in callOps -> ageMs > CALL_AGE_MS
        in ageless -> false
        else -> ageMs > CHANGE_AGE_MS
    }

    /** True when the request carries or returns a secret or a network name: it is run over Bluetooth only. */
    fun secret(req: Request): Boolean = when (req.opCase) {
        Request.OpCase.SET_HOTSPOT_SETUP -> req.setHotspotSetup.hasPassphrase()
        Request.OpCase.GET_HOTSPOT_SECRET, Request.OpCase.SCAN_WIFI, Request.OpCase.SAVE_WIFI, Request.OpCase.FORGET_WIFI -> true
        Request.OpCase.SET_REMOTE -> req.setRemote.on
        Request.OpCase.SET_PUSH_KEY -> !req.setPushKey.key.isEmpty
        else -> false
    }

    /** Why the request is not run on this link, or null. */
    fun refusal(req: Request, bluetooth: Boolean): Code? = when {
        tooOld(req.opCase, req.ageMs) -> Code.CODE_TOO_OLD
        !bluetooth && secret(req) -> Code.CODE_BLUETOOTH_ONLY
        // Off over the internet would cut this very link (internet mode runs on the host's mobile data).
        !bluetooth && req.opCase == Request.OpCase.SET_MOBILE_DATA && !req.setMobileData.on -> Code.CODE_CARRIES_LINK
        else -> null
    }

    /**
     * Until the call test has passed, the host stays out of calls except the test call itself: a call answered
     * without relayed audio would play through the host. A test call needs audio routes ([hasProfile]).
     */
    fun voiceRefusal(req: Request, relaysAudio: Boolean, hasProfile: Boolean): Code? = when {
        req.opCase == Request.OpCase.DIAL && req.dial.test -> if (hasProfile) null else Code.CODE_NO_AUDIO_PROFILE
        relaysAudio -> null
        req.opCase == Request.OpCase.DIAL -> Code.CODE_CALL_TEST_NEEDED
        req.opCase == Request.OpCase.CALL_CONTROL && req.callControl.verb == CallVerb.CALL_VERB_ANSWER -> Code.CODE_CALL_TEST_NEEDED
        else -> null
    }
}

/** Whether the host takes a Hello (protocol/v2/SPEC.md "Session"). Pure. */
object HelloRules {
    /** Every feature this host has, as the bits of `Welcome.features`. */
    val FEATURES: Long = listOf(
        Feature.FEATURE_USSD, Feature.FEATURE_HOST_ACTIONS, Feature.FEATURE_CALL_RULES, Feature.FEATURE_NETWORK_MODE,
        Feature.FEATURE_HOTSPOT_CALL_PAUSE, Feature.FEATURE_AUTO_UPDATE, Feature.FEATURE_MAC_PEERS,
        Feature.FEATURE_SCHEDULED_TEXTS,
    ).fold(0L) { bits, f -> bits or (1L shl f.number) }

    /** The install id as the host stores it. */
    fun id(hello: Hello): String = hexOf(hello.installId.toByteArray())

    /**
     * Why the Hello is refused, or null. [pinned]: the install id of the paired phone, null for none; [isMac]: an
     * install id is a paired Mac's; [bluetooth]: the link is a Bluetooth one (a Mac talks over Bluetooth only).
     */
    fun refusal(hello: Hello, pinned: String?, isMac: (String) -> Boolean = { false }, bluetooth: Boolean = true): Code? = when {
        commonVersion(hello.minVersion, hello.maxVersion) == null -> Code.CODE_VERSION
        hello.role == Role.ROLE_MAC -> macRefusal(hello, isMac, bluetooth)
        hello.role != Role.ROLE_PHONE -> Code.CODE_NOT_ALLOWED
        hello.installId.size() != 16 || id(hello) != pinned -> Code.CODE_NOT_PAIRED
        else -> null
    }

    private fun macRefusal(hello: Hello, isMac: (String) -> Boolean, bluetooth: Boolean): Code? = when {
        !bluetooth -> Code.CODE_NOT_ALLOWED
        hello.installId.size() != 16 || !isMac(id(hello)) -> Code.CODE_NOT_PAIRED
        else -> null
    }
}

/**
 * When the lines' signal bars go to the peers (protocol/v2/SPEC.md "State"): at once when a line loses its signal
 * or gets it back, otherwise at most once in [everyMs] and only while a peer watches (it named the group in a
 * GetState at most [watchMs] ago). Pure.
 */
class SignalGate(private val everyMs: Long = 30_000, private val watchMs: Long = 300_000) {
    private var sent: List<Int>? = null
    private var sentAt = 0L
    private var askedAt: Long? = null

    /** A peer named the group in a GetState. */
    fun asked(now: Long) { askedAt = now }

    /** True when [bars] go to the peers now; they are then what the peers hold. */
    fun pass(bars: List<Int>, now: Long): Boolean {
        val before = sent
        val go = before == null || lostOrBack(before, bars) || (before != bars && watched(now) && now - sentAt >= everyMs)
        if (go) { sent = bars; sentAt = now }
        return go
    }

    /** How long until bars that [pass] held back may go, or null when none are held or no peer watches. */
    fun waitMs(bars: List<Int>, now: Long): Long? =
        if (sent == null || sent == bars || !watched(now)) null else (everyMs - (now - sentAt)).coerceAtLeast(0)

    private fun watched(now: Long): Boolean = askedAt?.let { now - it <= watchMs } == true

    private fun lostOrBack(before: List<Int>, now: List<Int>): Boolean =
        before.size != now.size || before.indices.any { (before[it] == 0) != (now[it] == 0) }
}
