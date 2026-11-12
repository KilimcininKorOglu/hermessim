// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.content.Context
import android.os.Build
import android.util.Log
import com.keremgok.hermessim.wire.CallTestFault
import com.keremgok.hermessim.wire.CallTestReading
import com.keremgok.hermessim.wire.CallTestVerdict
import com.keremgok.hermessim.wire.HostSound
import com.keremgok.hermessim.wire.SupportTier
import com.keremgok.hermessim.wire.Voice
import org.json.JSONObject

/** The call test's rule, apart from the phone. Pure. */
object CallTestCore {
    const val MIN_VOICED_FRAMES = 50
    const val MIN_PLAYED_FRAMES = 100

    /**
     * Why the test did not pass, or null when it did. A pass needs the host's own reading too: the caller's voice
     * reached the capture path and the phone's audio was played into the call.
     */
    fun fault(v: CallTestVerdict, reading: CallTestReading?): CallTestFault? = when {
        !v.passed -> CallTestFault.CALL_TEST_FAULT_NOT_CONFIRMED
        reading == null -> CallTestFault.CALL_TEST_FAULT_NO_READING
        else -> measured(reading) ?: heard(v)
    }

    private fun measured(r: CallTestReading): CallTestFault? = when {
        r.voicedFrames < MIN_VOICED_FRAMES -> CallTestFault.CALL_TEST_FAULT_NO_CALLER_VOICE
        r.playedFrames < MIN_PLAYED_FRAMES -> CallTestFault.CALL_TEST_FAULT_NOTHING_PLAYED
        else -> null
    }

    private fun heard(v: CallTestVerdict): CallTestFault? = when {
        !v.callerHeard -> CallTestFault.CALL_TEST_FAULT_CALLER_HEARD_NOTHING
        v.echo -> CallTestFault.CALL_TEST_FAULT_ECHO
        v.hostSound == HostSound.HOST_SOUND_HEARD -> CallTestFault.CALL_TEST_FAULT_HOST_SOUND
        else -> null
    }

    /** The support table's tier as the wire names it. */
    fun tier(name: String): SupportTier =
        runCatching { SupportTier.valueOf("SUPPORT_TIER_$name") }.getOrDefault(SupportTier.SUPPORT_TIER_UNSPECIFIED)
}

/**
 * Who may relay call audio. Full voice is on only after the guided call test passed on this phone *and firmware
 * build* with the current profile (every phone, verified models included: full voice only where it is measured).
 * Until then calls are Basic (ring, answer, hang up from the phone, no audio), except the test call itself.
 */
object VoiceGate {
    private const val TAG = "HermesSimHost"
    private lateinit var app: Context
    private val prefs get() = app.getSharedPreferences("voice", Context.MODE_PRIVATE)

    /** The client ref (hex) of the test call in progress (Dial.test), null otherwise. */
    @Volatile var testRef: String? = null
    @Volatile private var lastResult: CallTestReading? = null
    @Volatile private var lastResultForReport: CallTestReading? = null   // the verdict's measurement, for [report]

    fun init(ctx: Context) { app = ctx.applicationContext }

    val passed: Boolean
        get() {
            val p = Daemon.profile() ?: return false
            val pass = runCatching { JSONObject(prefs.getString("pass", "") ?: "") }.getOrNull() ?: return false
            return pass.optString("firmware") == Build.FINGERPRINT && pass.optString("profile") == p.id
        }

    val fullVoice get() = Daemon.profile() != null && passed

    /** A call test passed, but on another firmware (an update came in since): the user should test again. */
    val passedBeforeUpdate: Boolean
        get() = runCatching { JSONObject(prefs.getString("pass", "") ?: "") }.getOrNull()
            ?.let { it.optString("firmware").isNotEmpty() && it.optString("firmware") != Build.FINGERPRINT } == true

    /** May the call with this client ref carry audio? */
    fun allows(clientRef: String?): Boolean = fullVoice || (Daemon.hasProfile() && clientRef != null && clientRef == testRef)

    fun status(): Voice {
        val p = Daemon.profile()
        val v = Compat.onThisPhone(Compat.verdict(app), p)
        val pass = runCatching { JSONObject(prefs.getString("pass", "") ?: "") }.getOrNull()
        return Voice.newBuilder()
            .setRelaysAudio(fullVoice).setCanTest(p != null).setPassed(passed)
            .setPassedAtMs(if (passed) pass?.optLong("at") ?: 0 else 0)
            .setProfile(p?.id.orEmpty()).setProfileSource(p?.source.orEmpty())
            .setSupport(CallTestCore.tier(v.tier.name)).setDeviceName(v.name)
            .setFirmware(Build.FINGERPRINT)
            .build()
    }

    /** During the test call: what reached us and what we played (daemon totals since the audio started). */
    fun check(done: (CallTestReading) -> Unit) = Daemon.query("AUDIO?") { reply ->
        fun num(k: String) = reply?.let { Regex("\\b$k=(-?\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
        val p = Daemon.profile()
        val r = CallTestReading.newBuilder()
            .setInCall(num("call") == 1).setSilencedControls(num("silence"))
            .setCapturedFrames(num("cap")).setVoicedFrames(num("voiced")).setSentFrames(num("sent"))
            .setPlayedFrames(num("played")).setPeak(num("peak"))
            .setProfile(p?.id.orEmpty()).setProfileSource(p?.source.orEmpty())
            .build()
        if (r.inCall) lastResult = r
        Log.i(TAG, "call test host check: $reply")
        done(r)
    }

    /**
     * The call test's outcome as the host saw it, for "proven" phones: the user's answers, the host's measurements
     * and its own fault (null = passed). Sent with the phone's exact model, codename, firmware and profile.
     */
    fun report(v: CallTestVerdict, fault: CallTestFault?) {
        val r = lastResultForReport
        val passed = v.passed && fault == null
        val d = JSONObject()
            .put("what", if (passed) "passed" else "failed").put("reason", fault?.name?.removePrefix("CALL_TEST_FAULT_")?.lowercase().orEmpty())
            .put("heard_caller", v.heardCaller).put("caller_heard_us", v.callerHeard).put("echo", v.echo)
            .put("host_sound", v.hostSound.name.removePrefix("HOST_SOUND_").lowercase())
            .put("measured", JSONObject().put("captured", r?.capturedFrames ?: 0).put("voiced", r?.voicedFrames ?: 0)
                .put("sent", r?.sentFrames ?: 0).put("played", r?.playedFrames ?: 0).put("peak", r?.peak ?: 0)
                .put("silence_controls", r?.silencedControls ?: 0))
            .put("audio", Discovery.audio())
        Diag.event("call_test", d, withLog = !passed, passed = passed, kind = "call_test")
        if (passed) Diag.milestone("full_voice")
    }

    /** The user's answers: the test passes when [CallTestCore.fault] finds none. Returns that fault, or null. */
    fun verdict(v: CallTestVerdict): CallTestFault? {
        val r = lastResult
        lastResultForReport = r
        testRef = null
        val fault = CallTestCore.fault(v, r)
        val p = Daemon.profile()
        if (fault == null && p != null) {
            prefs.edit().putString("pass", JSONObject().put("firmware", Build.FINGERPRINT).put("profile", p.id)
                .put("source", p.source).put("at", System.currentTimeMillis()).toString()).apply()
            Log.i(TAG, "call test PASSED: full voice on (${p.id}, ${p.source})")
        } else Log.i(TAG, "call test not passed: $fault")
        lastResult = null
        return fault
    }
}
