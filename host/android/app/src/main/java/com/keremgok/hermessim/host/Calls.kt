// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.Call
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import com.google.protobuf.ByteString
import com.keremgok.hermessim.wire.CallEnd
import com.keremgok.hermessim.wire.CallPhase
import com.keremgok.hermessim.wire.CallState
import com.keremgok.hermessim.wire.CallVerb
import com.keremgok.hermessim.wire.Code
import java.util.concurrent.ConcurrentHashMap

/** The parts of the call rules that need no phone. Pure. */
object CallCore {
    /** The phase a Telecom call state (android.telecom.Call.STATE_*) stands for. An unknown state counts as dialling. */
    fun phase(telecomState: Int): CallPhase = phases[telecomState] ?: CallPhase.CALL_PHASE_DIALING

    private val phases = mapOf(
        Call.STATE_RINGING to CallPhase.CALL_PHASE_RINGING, Call.STATE_ACTIVE to CallPhase.CALL_PHASE_ACTIVE,
        Call.STATE_HOLDING to CallPhase.CALL_PHASE_HELD,
        Call.STATE_DISCONNECTED to CallPhase.CALL_PHASE_ENDED, Call.STATE_DISCONNECTING to CallPhase.CALL_PHASE_ENDED,
    )

    private val ends = mapOf(
        DisconnectCause.LOCAL to CallEnd.CALL_END_LOCAL, DisconnectCause.REMOTE to CallEnd.CALL_END_REMOTE,
        DisconnectCause.REJECTED to CallEnd.CALL_END_REJECTED, DisconnectCause.MISSED to CallEnd.CALL_END_MISSED,
        DisconnectCause.BUSY to CallEnd.CALL_END_BUSY, DisconnectCause.ERROR to CallEnd.CALL_END_FAILED,
    )

    /** How a call ended, from Telecom's DisconnectCause code (null: none given). */
    fun end(disconnectCode: Int?): CallEnd = ends[disconnectCode] ?: CallEnd.CALL_END_UNSPECIFIED

    /** True when a peer must hear of [now]: the first state of a call, another phase, or the far end's ringing changed. */
    fun changed(before: CallState?, now: CallState): Boolean =
        before == null || before.phase != now.phase || before.farEndRings != now.farEndRings || before.farEndKnown != now.farEndKnown

    /** A client ref as the text the host keeps it under. */
    fun refText(ref: ByteString): String = ref.toByteArray().joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    fun refBytes(text: String): ByteString = ByteString.copyFrom(text.chunked(2).map { it.toInt(16).toByte() }.toByteArray())

    private const val TONES = "0123456789*#ABCD"

    fun validTones(digits: String): Boolean = digits.isNotEmpty() && digits.all { it in TONES }
}

/**
 * Bridges Telecom calls (seen by CallMonitor, our non-UI InCallService) to `CallState` events, and
 * carries out the client's CallControl / Dial. Also drives the audio daemon: only an ACTIVE (or DIALING) call has
 * its routes and mutes applied, only with full voice on or for the guided call test (VoiceGate), and only for a call
 * the iPhone owns: one it dialled or answered. Any other call is not relayed (20 Oct 2026: a test phone's own host,
 * restarted mid-call, relayed a call answered on its screen and fed the caller silence); the host is the phone app,
 * so it shows no call screen, and it stays silent through such a call.
 */
object CallRegistry {
    private const val TAG = "HermesSimHost"
    private val ids = ConcurrentHashMap<Call, Long>()
    private val byId = ConcurrentHashMap<Long, Call>()
    private val clientRefs = ConcurrentHashMap<Long, String>()      // call id -> the client's ref of the call (hex)
    private val lastSent = ConcurrentHashMap<Long, CallState>()         // send changes only
    private val clock = CallStateClock<CallPhase>(System::currentTimeMillis)
    @Volatile private var pendingDial: Pair<String, Long>? = null     // client ref, when requested
    private val owned: MutableSet<Long> = ConcurrentHashMap.newKeySet()     // call ids the phone dialled or answered
    private lateinit var app: Context

    fun init(ctx: Context) { app = ctx.applicationContext }

    /** A call id this host never used before, also across restarts: a peer may still hold calls of an earlier run. */
    @Synchronized private fun newId(): Long {
        val id = prefs.getLong("last_id", 0) + 1
        prefs.edit().putLong("last_id", id).apply()
        return id
    }

    // Ownership outlives a host restart mid-call (keep-alive, update, crash): keyed by Telecom's creation time, which
    // the same call keeps, in a small prefs set pruned of anything older than a day.
    private val prefs get() = app.getSharedPreferences("calls", Context.MODE_PRIVATE)
    private fun key(call: Call) = "${call.details.creationTimeMillis}"
    private fun own(call: Call, id: Long) {
        owned.add(id)
        val now = System.currentTimeMillis()
        val keep = prefs.getStringSet("owned", emptySet()).orEmpty().filter { now - (it.toLongOrNull() ?: 0) < 86_400_000L }
        prefs.edit().putStringSet("owned", (keep + key(call)).toSet()).apply()
    }
    /** The iPhone dialled or answered it (a conference: any of its calls). */
    private fun isOwned(call: Call): Boolean =
        ids[call] in owned || call.children.any { ids[it] in owned } || call.parent?.let { ids[it] in owned } == true

    private fun disown(call: Call, id: Long) {
        owned.remove(id)
        prefs.edit().putStringSet("owned", prefs.getStringSet("owned", emptySet()).orEmpty() - key(call)).apply()
    }

    fun added(call: Call) {
        val id = newId()
        ids[call] = id
        byId[id] = call
        val pd = pendingDial
        if (call.details.callDirection == Call.Details.DIRECTION_OUTGOING && pd != null &&
            System.currentTimeMillis() - pd.second < 15_000) {
            clientRefs[id] = pd.first
            pendingDial = null
            own(call, id)
        } else if (key(call) in prefs.getStringSet("owned", emptySet()).orEmpty()) {
            owned.add(id)                                   // the iPhone's call, from before this process started
            Log.i(TAG, "call $id: the phone's, from before a host restart")
        }
        changed(call)
    }

    fun changed(call: Call) {
        send(call)
        if (!dialingPhase()) RootCallState.stop()
        // Audio runs while a call is ACTIVE, and for outgoing calls already while DIALING: the network's early
        // media (ringback, "number unavailable" announcements) then reaches the iPhone instead of the host.
        // The iPhone's call first: a call taken on the host can be active while the iPhone's own waits on hold.
        val active = byId.values.filter { it.stateCompat == Call.STATE_ACTIVE }
        val dialing = byId.values.filter { it.stateCompat == Call.STATE_DIALING }
        val audible = active.firstOrNull(::isOwned) ?: active.firstOrNull() ?: dialing.firstOrNull(::isOwned) ?: dialing.firstOrNull()
        val relayed = audible != null && isOwned(audible) && VoiceGate.allows(ids[audible]?.let { clientRefs[it] })
        HotspotPause.calls(relayed, live = liveCalls())
        if (byId.values.any { it.stateCompat == Call.STATE_HOLDING }) CallAudioCheck.held()
        if (relayed) {
            BasicSilence.set(app, false)
            Daemon.callActive(Daemon.profile()?.sessionForSlot(maxOf(slotOf(audible), 0)) ?: (maxOf(slotOf(audible), 0) + 1),
                connected = audible.stateCompat == Call.STATE_ACTIVE)
        } else {
            Daemon.callIdle()
            // A call without relayed audio (Basic mode, or one the iPhone didn't dial or answer): the host stays quiet.
            // HermesSIM governs every call from install until "Stop using this phone as a host", box mode or not, as
            // the phone app ([BasicSilence.wanted]: another phone app's call is its owner's).
            BasicSilence.set(app, audible != null && BasicSilence.wanted(isOwned(audible), Onboarding.dialerRole(app)))
        }
    }

    fun removed(call: Call) {
        val id = ids.remove(call) ?: return
        if (lastSent.remove(id)?.phase != CallPhase.CALL_PHASE_ENDED) Relay.callEvent(event(call, forceEnded = true, id = id))
        byId.remove(id)
        clock.forget(id.toString())
        clientRefs.remove(id)
        alertingIds.remove(id)
        if (!dialingPhase()) RootCallState.stop()
        disown(call, id)
        if (byId.values.none { it.stateCompat == Call.STATE_ACTIVE || it.stateCompat == Call.STATE_DIALING }) Daemon.callIdle()
        HotspotPause.calls(relayed = false, live = liveCalls())     // a call that's still relayed says so at its next change
    }

    /** Any call not yet over (a held one counts: the hotspot stays paused through a hold). */
    private fun liveCalls() = byId.values.any { it.stateCompat != Call.STATE_DISCONNECTED }

    /** A call is ringing, being dialled, active or held. Safe before [init] and off the main thread. */
    fun live(): Boolean = byId.values.any { CallCore.phase(it.stateCompat) != CallPhase.CALL_PHASE_ENDED }

    /** The call's state goes to the phone when it differs from the last one sent. */
    private fun send(call: Call) {
        event(call).takeIf { CallCore.changed(lastSent.put(it.call, it), it) }?.let(Relay::callEvent)
    }

    // ------------------------------------------------------------------ ringback
    // The iPhone plays no ringback until the far end rings (CallEvent.alerting), like any phone. Two sources: the
    // modem's precise call state (RootCallState, any phone) and Samsung's own connection event. The client relies on
    // it (alerting_known) only on a firmware where an alert was seen before, and not after this dial's watcher failed;
    // otherwise it rings back from the start of dialing, as before 0.12.14.
    private val alertingIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    @Volatile private var watchLost = false
    private const val SAMSUNG_ALERTING = "com.samsung.telecom.Call.EVENT_SET_ALERTING"

    private val dialingStates = setOf(Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_SELECT_PHONE_ACCOUNT)
    /** An outgoing call is on its way (or the client's Dial is, before Telecom adds the call). */
    private fun dialingPhase() = byId.values.any { it.stateCompat in dialingStates } ||
        pendingDial?.let { System.currentTimeMillis() - it.second < 15_000 } == true

    private fun alertingKnown(call: Call): Boolean =
        call.details.callDirection == Call.Details.DIRECTION_OUTGOING && isOwned(call) && !watchLost &&
            prefs.getString("alerting_seen", null) == Build.FINGERPRINT

    /** Main thread: the far end of [call] rings. */
    private fun alerting(call: Call, how: String) {
        val id = ids[call] ?: return
        if (call.stateCompat !in dialingStates || !alertingIds.add(id)) return
        Log.i(TAG, "call $id alerting ($how)")
        if (prefs.getString("alerting_seen", null) != Build.FINGERPRINT) prefs.edit().putString("alerting_seen", Build.FINGERPRINT).apply()
        send(call)
    }

    fun connectionEvent(call: Call, event: String) { if (event == SAMSUNG_ALERTING) alerting(call, "Samsung event") }

    private fun watchAlerting() {
        watchLost = false
        RootCallState.watch(app, onState = { slot, state ->
            if (state == RootCallState.ALERTING) byId.values.filter {
                it.details.callDirection == Call.Details.DIRECTION_OUTGOING && slotOf(it).let { s -> s == slot || s < 0 }
            }.forEach { alerting(it, "precise call state") }
        }, onLost = { why ->
            val dialing = byId.values.filter { it.stateCompat in dialingStates }
            if (dialing.any(::alertingKnown)) {
                Diag.event("alerting_watch_lost", org.json.JSONObject().put("what", why.take(60)), withLog = false)
            }
            watchLost = true                                // the client rings back itself from now on
            dialing.forEach(::send)
        })
    }

    /** Any call at all (ringing, dialling, active or held): cheap, and safe before [init]. */
    fun anyCall(): Boolean = byId.isNotEmpty()

    /** Current calls, for replay after the client reconnects. */
    fun snapshot(): List<CallState> = byId.values.map { event(it) }

    /** A peer's CallControl. CALL_VERB_END rejects a call that still rings and ends any other. */
    fun control(id: Long, verb: CallVerb): Code {
        val call = byId[id] ?: return Code.CODE_NO_SUCH_CALL
        Log.i(TAG, "client $verb on call $id")
        when (verb) {
            CallVerb.CALL_VERB_ANSWER -> { own(call, id); call.answer(VideoProfile.STATE_AUDIO_ONLY) }
            CallVerb.CALL_VERB_END -> if (call.stateCompat == Call.STATE_RINGING) call.reject(false, null) else call.disconnect()
            CallVerb.CALL_VERB_HOLD -> call.hold()
            CallVerb.CALL_VERB_RESUME -> call.unhold()
            else -> return Code.CODE_BAD_VALUE
        }
        return Code.CODE_OK
    }

    /** A peer's SendTones: each digit as one key press on the call. */
    fun tones(id: Long, digits: String): Code {
        val call = byId[id] ?: return Code.CODE_NO_SUCH_CALL
        if (!CallCore.validTones(digits)) return Code.CODE_BAD_VALUE
        digits.forEach { call.playDtmfTone(it); call.stopDtmfTone() }
        return Code.CODE_OK
    }

    /** A peer's Dial. [slot] below 0: the phone system's default line; [ref]: the client's ref of the call (hex). */
    @SuppressLint("MissingPermission") // CALL_PHONE / READ_PHONE_STATE granted by the Magisk module
    fun dial(number: String, slot: Int, ref: String, test: Boolean): Code {
        if (number.isBlank()) return Code.CODE_BAD_VALUE
        if (dialingPhase()) return Code.CODE_BUSY
        val tm = app.getSystemService(TelecomManager::class.java)
        val extras = Bundle()
        val account = accountForSlot(slot)
        if (slot >= 0 && account == null) return Code.CODE_LINE_OFF
        account?.let { extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
        pendingDial = ref to System.currentTimeMillis()
        if (test) VoiceGate.testRef = ref
        Log.i(TAG, "client dial slot=$slot${if (test) " (call test)" else ""}")
        watchAlerting()
        tm.placeCall(Uri.fromParts("tel", number, null), extras)
        return Code.CODE_OK
    }

    // ------------------------------------------------------------------ mapping

    /** An incoming, ringing call the call rules block: its number is blocked, or it is hidden while hidden ones are. */
    fun blocked(call: Call): Boolean {
        val d = call.details
        if (d.callDirection != Call.Details.DIRECTION_INCOMING || call.stateCompat != Call.STATE_RINGING) return false
        val raw = d.handle?.schemeSpecificPart.orEmpty()
        return CallRuleStore.blocksCall(raw, d.handlePresentation != TelecomManager.PRESENTATION_ALLOWED || raw.isEmpty())
    }

    private fun event(call: Call, forceEnded: Boolean = false, id: Long? = null): CallState {
        val d = call.details
        val raw = d.handle?.schemeSpecificPart.orEmpty()
        val slot = slotOf(call)
        val withheld = d.handlePresentation != TelecomManager.PRESENTATION_ALLOWED || raw.isEmpty()
        val phase = if (forceEnded) CallPhase.CALL_PHASE_ENDED else CallCore.phase(call.stateCompat)
        val callId = id ?: ids[call] ?: 0L
        val dialing = phase == CallPhase.CALL_PHASE_DIALING
        val b = CallState.newBuilder()
            .setCall(callId)
            .setPhase(phase)
            .setIncoming(d.callDirection == Call.Details.DIRECTION_INCOMING)
            .setNumber(if (withheld) "" else raw)
            .setNumberE164(if (withheld) "" else e164(raw, slot))
            .setPhaseSinceMs(clock.since(callId.toString(), phase))
            .setClientRef(CallCore.refBytes(clientRefs[callId].orEmpty()))
            .setFarEndRings(dialing && callId in alertingIds)
            .setFarEndKnown(dialing && alertingKnown(call))
        if (slot >= 0) b.line = slot
        if (phase == CallPhase.CALL_PHASE_ENDED) b.end = CallCore.end(d.disconnectCause?.code)
        return b.build()
    }

    /** 0-based SIM slot of the call's account; -1 if unknown. */
    fun slotOf(call: Call): Int = runCatching {
        val subId = app.getSystemService(TelephonyManager::class.java).getSubscriptionId(call.details.accountHandle)
        SubscriptionManager.getSlotIndex(subId)
    }.getOrDefault(-1)

    private fun e164(raw: String, slot: Int): String = Sims.e164(app, raw, slot)

    @SuppressLint("MissingPermission")
    private fun accountForSlot(slot: Int): PhoneAccountHandle? {
        if (slot < 0) return null
        val telecom = app.getSystemService(TelecomManager::class.java)
        val tm = app.getSystemService(TelephonyManager::class.java)
        return telecom.callCapablePhoneAccounts.firstOrNull {
            SubscriptionManager.getSlotIndex(tm.getSubscriptionId(it)) == slot
        }
    }
}

/** Call.Details#getState is API 31; Call#getState (deprecated) gives the same on Android 11. */
@Suppress("DEPRECATION")
val Call.stateCompat: Int get() = if (android.os.Build.VERSION.SDK_INT >= 31) details.state else state

/**
 * Basic mode (no measured audio path yet): an answered call would otherwise use the host's own earpiece and mic, in a
 * bag or a drawer. Without a profile there are no device mutes to apply, so use what every Android phone has: mic mute
 * and the lowest call volume, restored when the call ends. (Hard rule 1: the host stays silent.)
 * The volume to give back is kept on disk: a host restarted mid-call (keep-alive, update, crash) found the minimum,
 * saved that, and made it the phone's call volume for good (audit, 20 Oct 2026).
 */
object BasicSilence {
    private const val TAG = "HermesSimHost"
    private const val KEY = "voice_volume"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("basic_silence", Context.MODE_PRIVATE)

    /**
     * Which calls without relayed audio are silenced: the client's always (it was answered or dialled from there, with
     * the host in a bag or a drawer), any other only while HermesSIM is the phone app. With another phone app holding
     * the role, a call the client didn't dial or answer was taken on this phone by a person holding it (the role given
     * back, or changed by hand): muting its mic cut its owner off (Mi 11 Pro, 4 Nov 2026).
     */
    fun wanted(clientsCall: Boolean, ourPhoneApp: Boolean) = clientsCall || ourPhoneApp

    fun set(ctx: Context, on: Boolean) {
        val am = ctx.getSystemService(android.media.AudioManager::class.java) ?: return
        val p = prefs(ctx)
        val saved = p.getInt(KEY, -1)
        runCatching {
            if (on && saved < 0) {
                p.edit().putInt(KEY, am.getStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL)).commit()
                am.isMicrophoneMute = true
                am.setStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL, am.getStreamMinVolume(android.media.AudioManager.STREAM_VOICE_CALL), 0)
                Log.i(TAG, "basic-mode call: host mic muted, call volume at minimum")
            } else if (!on && saved >= 0) {
                am.isMicrophoneMute = false
                am.setStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL, saved, 0)
                p.edit().remove(KEY).apply()
                Log.i(TAG, "basic-mode call over: host mic and call volume restored")
            }
        }.onFailure { Log.w(TAG, "basic-mode silence: $it") }
    }
}
