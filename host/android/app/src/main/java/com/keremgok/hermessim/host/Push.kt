// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.keremgok.hermessim.wire.CallPhase
import com.keremgok.hermessim.wire.CallState
import com.keremgok.hermessim.wire.Event
import com.keremgok.hermessim.wire.Notice
import com.keremgok.hermessim.wire.Packet
import com.keremgok.hermessim.wire.TextIn
import org.json.JSONObject

/** What a push carries. Pure. */
object PushCore {
    /** An alert push carries at most 4 KB: a long text is cut for the notification (the whole one follows on sync). */
    const val MAX_BODY_BYTES = 1500

    /** The packet of a push: the event as the link would carry it, a long text's body cut to fit. */
    fun packet(event: Event): ByteArray {
        val fitted = if (event.hasTextIn()) event.toBuilder().setTextIn(fitted(event.textIn)).build() else event
        return Packet.newBuilder().setEvent(fitted).build().toByteArray()
    }

    private fun fitted(text: TextIn): TextIn {
        val bytes = text.body.toByteArray()
        if (bytes.size <= MAX_BODY_BYTES) return text
        val cut = String(bytes, 0, MAX_BODY_BYTES, Charsets.UTF_8).trimEnd('�') + "…"
        return text.toBuilder().setBody(cut).build()
    }

    /** The id under which a newer notice replaces an older one of its kind and line. */
    fun noticeId(n: Notice): String = "notice-${n.kindValue}-${if (n.hasLine()) n.line.toString() else "x"}"

    /** The server takes a collapse id of at most 64 letters, digits, dots, dashes and underscores. */
    fun validCollapseId(id: String): Boolean = id.length in 1..64 && id.all { it.isLetterOrDigit() || it in "._-" }
}

/**
 * Push wake-up (protocol/README.md "Push"): when the phone can't hear us, hermessim.keremgok.tr asks FCM to wake it. A
 * ringing call goes as a VoIP push (iOS rings it through CallKit and starts the app, which then reaches us over
 * Bluetooth or the internet); a text the phone didn't store within a few seconds goes as an alert push its
 * notification extension decrypts. Each carries the sealed `Packet` of the event (push key, AAD "h2c"); the server
 * sees only ciphertext. The key is the phone's SetPushKey (internet mode may be off); without one, the key of
 * internet mode is used.
 */
object Push {
    private const val TAG = "HermesSimHost"
    /** A text not acknowledged by then (the phone asleep or out of reach, even if it looks present in the room) is pushed. */
    private const val TEXT_ACK_WAIT_MS = 4000L
    private lateinit var app: Context
    private val wake by lazy {
        app.getSystemService(android.os.PowerManager::class.java)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "hermessim:sms-push").apply { setReferenceCounted(false) }
    }
    private val main = Handler(Looper.getMainLooper())
    private val exec = Bg.single("push")
    private val pushedCalls = LinkedHashSet<Long>()           // main thread

    @Volatile private var crypto: RemoteCrypto? = null

    fun start(ctx: Context) {
        app = ctx.applicationContext
        prefs().getString("key", null)?.let { crypto = RemoteCrypto(Base64.decode(it, Base64.NO_WRAP)) }
    }

    private fun prefs() = app.getSharedPreferences("push", Context.MODE_PRIVATE)

    /** SetPushKey over Bluetooth (32 bytes), or empty to forget it (ForgetHost, Retire). */
    fun setKey(k: ByteArray) {
        if (!::app.isInitialized) return
        if (k.size == 32) {
            prefs().edit().putString("key", Base64.encodeToString(k, Base64.NO_WRAP)).apply()
            crypto = RemoteCrypto(k)
            Log.i(TAG, "push: key set (${keyId()?.let { RemoteCrypto.hex(it) }})")
        } else {
            prefs().edit().remove("key").apply()
            crypto = null
            Log.i(TAG, "push: key forgotten")
        }
    }

    /** First 8 bytes of sha256(key): the phone sends its key again when this isn't its own. */
    fun keyId(): ByteArray? = if (!::app.isInitialized) null else prefs().getString("key", null)?.let {
        java.security.MessageDigest.getInstance("SHA-256").digest(Base64.decode(it, Base64.NO_WRAP)).copyOf(8)
    }

    /** Relay.callEvent, main: an incoming call is ringing and no phone is on Bluetooth. Once per call. */
    fun call(seq: Long, c: CallState) {
        if (!::app.isInitialized || !pushedCalls.add(c.call)) return
        while (pushedCalls.size > 20) pushedCalls.remove(pushedCalls.first())
        // Basic mode: the phone doesn't ring host calls; a VoIP push must ring, so this one is a note instead.
        val voip = VoiceGate.fullVoice
        send(if (voip) "call" else "note", PushCore.packet(Event.newBuilder().setSeq(seq).setCall(c).build()),
            if (voip) null else "basic-${c.call}", "call ${c.call}")
    }

    /** Relay.textIn, main: pushed only if no phone has acknowledged (stored) the text shortly. */
    fun text(seq: Long, event: ByteArray) {
        if (!::app.isInitialized) return
        // The wait counts uptime: the phone wakes for the text and fell back asleep inside it, so one push went out after
        // 131 s, not 4 (31 Oct 2026). Held awake for the wait and the push's request.
        wake.acquire(TEXT_ACK_WAIT_MS + 10_000)
        main.postDelayed({
            if (!Events.log(app).holds(seq)) return@postDelayed
            send("sms", PushCore.packet(Event.parseFrom(event)), "text-$seq", "text $seq")
        }, TEXT_ACK_WAIT_MS)
    }

    /** Relay's notices, main: a health notice no phone on Bluetooth heard. One per kind and line is kept. */
    fun notice(n: Notice) {
        if (!::app.isInitialized) return
        send("notice", PushCore.packet(Event.newBuilder().setNotice(n).build()), PushCore.noticeId(n), "notice ${n.kind}")
    }

    /** adb test: push a made-up call or text whatever the link (Relay's rules are not involved). */
    fun test(kind: String) {
        val now = System.currentTimeMillis()
        if (kind == "sms") send("sms", PushCore.packet(Event.newBuilder().setTextIn(TextIn.newBuilder()
            .setFrom("+905550000000").setFromE164("+905550000000").setBody("HermesSIM Host push test").setAtMs(now)).build()),
            "test-$now", "test text")
        else send("call", PushCore.packet(Event.newBuilder().setCall(CallState.newBuilder().setCall(now)
            .setPhase(CallPhase.CALL_PHASE_RINGING).setIncoming(true).setNumber("+905550000000").setNumberE164("+905550000000")
            .setPhaseSinceMs(now)).build()), null, "test call")
    }

    private fun send(kind: String, packet: ByteArray, collapseId: String?, what: String) {
        val c = crypto ?: RemoteLink.crypto ?: run { Log.i(TAG, "push: $what not sent (no push key from the phone yet)"); return }
        exec.execute {
            val body = JSONObject().put("room_token", RemoteCrypto.hex(c.roomToken)).put("kind", kind)
                .put("sealed", Base64.encodeToString(c.sealPush(packet), Base64.NO_WRAP))
            if (collapseId != null && PushCore.validCollapseId(collapseId)) body.put("collapse_id", collapseId)
            val t = System.currentTimeMillis()
            val (code, _) = Api.postStatus("/push/send", body)
            Log.i(TAG, "push $kind: $what -> HTTP $code in ${System.currentTimeMillis() - t} ms")
        }
    }
}
