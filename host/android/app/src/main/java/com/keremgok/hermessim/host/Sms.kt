// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import com.keremgok.hermessim.host.wire.EventLog
import com.keremgok.hermessim.host.wire.EventStore
import com.keremgok.hermessim.wire.Code
import com.keremgok.hermessim.wire.Event
import com.keremgok.hermessim.wire.TextByHost
import com.keremgok.hermessim.wire.TextIn
import com.keremgok.hermessim.wire.TextOrigin
import com.keremgok.hermessim.wire.TextPhase
import com.keremgok.hermessim.wire.TextStatus

private const val TAG = "HermesSimHost"

/**
 * The texts the host sends and what a peer has heard of each (`TextStatus`). A text a peer asks for twice with one
 * client ref is sent once; a status no peer was there to hear is kept for the next one. Pure.
 */
class TextBook(private val keep: Int = 50) {
    private class Entry(var status: TextStatus, var told: Boolean)

    private val refs = LinkedHashMap<String, Long>()          // client ref (hex) -> seq
    private val entries = LinkedHashMap<Long, Entry>()

    /** The seq of the text a peer already queued under [ref], or null. */
    fun seqOf(ref: String): Long? = refs[ref]

    fun queued(seq: Long, ref: String) {
        if (ref.isNotEmpty()) refs[ref] = seq
        entries[seq] = Entry(status(seq, ref, TextPhase.TEXT_PHASE_QUEUED), told = true)
        while (entries.size > keep) entries.remove(entries.keys.first())
        while (refs.size > keep) refs.remove(refs.keys.first())
    }

    /**
     * The status to send for a text that reached [phase], or null when that is no step forward. A text this book
     * does not know (queued before the host app started again) is taken as queued.
     */
    fun advance(seq: Long, phase: TextPhase, error: Int = 0): TextStatus? {
        val entry = entries.getOrPut(seq) { Entry(status(seq, "", TextPhase.TEXT_PHASE_QUEUED), told = true) }
        if (!forward(entry.status.phase, phase)) return null
        entry.status = entry.status.toBuilder().setPhase(phase).setError(error).build()
        entry.told = false
        return entry.status
    }

    /** A peer was served when the status of [seq] went out. */
    fun told(seq: Long) { entries[seq]?.told = true }

    /** The statuses no peer has heard, oldest first. */
    fun untold(): List<TextStatus> = entries.values.filter { !it.told }.map { it.status }

    private fun status(seq: Long, ref: String, reached: TextPhase): TextStatus = TextStatus.newBuilder().apply {
        text = seq
        clientRef = CallCore.refBytes(ref)
        phase = reached
    }.build()

    companion object {
        /** Phases only move forward; a delivery report may still follow a failure or a missing answer. */
        fun forward(from: TextPhase, to: TextPhase): Boolean = when (from) {
            TextPhase.TEXT_PHASE_QUEUED -> to != TextPhase.TEXT_PHASE_QUEUED
            TextPhase.TEXT_PHASE_DELIVERED -> false
            else -> to == TextPhase.TEXT_PHASE_DELIVERED
        }
    }
}

/**
 * The host's event log on disk (prefs "events"): the seq numbers of texts and calls, and the texts no peer has
 * stored yet. A text stays until a peer acknowledges it (`EventAck`) and is sent again after every Hello.
 */
object Events {
    @Volatile private var made: EventLog? = null

    fun log(ctx: Context): EventLog = made ?: synchronized(this) {
        made ?: EventLog(PrefsStore(ctx.applicationContext)).also { made = it }
    }

    private class PrefsStore(ctx: Context) : EventStore {
        private val prefs = ctx.getSharedPreferences("events", Context.MODE_PRIVATE)
        override fun read(key: String): String? = prefs.getString(key, null)
        @SuppressLint("ApplySharedPref")        // a seq must never be given twice, also when the app dies right after
        override fun write(key: String, value: String?) { prefs.edit().putString(key, value).commit() }
        override fun keys(prefix: String): List<String> = prefs.all.keys.filter { it.startsWith(prefix) }
    }
}

/** Default-SMS-app delivery: write to the provider (we are the default app now), queue, relay. */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        HostService.ensure(ctx)
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return
        val from = parts[0].displayOriginatingAddress.orEmpty()
        val body = parts.joinToString("") { it.displayMessageBody.orEmpty() }
        val subId = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, -1)
        val slot = SubscriptionManager.getSlotIndex(subId)
        val ts = parts[0].timestampMillis

        ctx.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, ContentValues().apply {
            put(Telephony.Sms.ADDRESS, from); put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis()); put(Telephony.Sms.DATE_SENT, ts)
            put(Telephony.Sms.READ, 0); put(Telephony.Sms.SUBSCRIPTION_ID, subId)
        })
        CallRuleStore.init(ctx)
        // A blocked sender's text stays in the phone's inbox only: no client, push or Mac gets it
        if (CallRuleStore.blocksText(from)) { Log.i(TAG, "SMS received on slot $slot from a blocked number: kept, not relayed"); return }
        val seq = TextsIn.take(ctx, from, body, slot, ts)
        Log.i(TAG, "SMS received on slot $slot (${body.length} chars), queued as text $seq")
        if (CallRuleStore.autoReply(from)) {
            Log.i(TAG, "busy: replying to text $seq on slot $slot")
            SmsSender.sendFromHost(ctx, from, CallRuleStore.rules.busyText, slot, TextOrigin.TEXT_ORIGIN_BUSY_REPLY)
        }
    }
}

/** A text that came in: kept in the event log under its seq, then handed to the relay. */
object TextsIn {
    /** [slot] below 0: the line is not known. Returns the text's seq. */
    fun take(ctx: Context, from: String, body: String, slot: Int, atMs: Long): Long {
        val text = TextIn.newBuilder().setFrom(from).setFromE164(Sims.e164(ctx, from, slot)).setBody(body).setAtMs(atMs)
        if (slot >= 0) text.line = slot
        val built = text.build()
        val (seq, event) = Events.log(ctx).addText { seq -> Event.newBuilder().setSeq(seq).setTextIn(built).build().toByteArray() }
        Relay.textIn(seq, built, event)
        return seq
    }
}

/** Required for the SMS role. MMS is not relayed yet (M4 scope: SMS). */
class MmsWapPushReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) { Log.i(TAG, "MMS WAP push received (not relayed yet)") }
}

/** Required for the SMS role ("send to" entry point); the host has no UI. */
class ComposeSmsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); finish() }
}

/** Required for the SMS role ("respond via message" from the incoming-call screen). */
class RespondViaMessageService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { stopSelf(startId); return START_NOT_STICKY }
}

object SmsSender {
    private const val ACTION_SENT = "com.keremgok.hermessim.host.SMS_SENT"
    private const val ACTION_DELIVERED = "com.keremgok.hermessim.host.SMS_DELIVERED"
    /** When each text went out (elapsedRealtime), to tell a timeout from a real refusal. */
    private val sentAt = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    private val book = TextBook()

    /** The statuses no peer has heard yet: the relay sends them after a Hello. */
    fun untold(): List<TextStatus> = synchronized(book) { book.untold() }

    fun told(seq: Long) = synchronized(book) { book.told(seq) }

    /** A text the host sends by itself (the busy reply, a Mac's reply): the peers get it as TextByHost, then its statuses. */
    fun sendFromHost(ctx: Context, to: String, body: String, slot: Int, origin: TextOrigin) {
        val refusal = refusal(ctx, to, body, slot)
        if (refusal != null) { Log.w(TAG, "text from the host ($origin) not sent: $refusal"); return }
        val text = TextByHost.newBuilder().setTo(to).setBody(body).setAtMs(System.currentTimeMillis()).setOrigin(origin)
        if (slot >= 0) text.line = slot
        val (seq, event) = Events.log(ctx).addText { seq -> Event.newBuilder().setSeq(seq).setTextByHost(text).build().toByteArray() }
        synchronized(book) { book.queued(seq, "") }
        Relay.textEvent(event)
        send(ctx, seq, to, body, slot)
    }

    /**
     * A peer's SendText. [ref] is the client's ref of the text (hex): asked again with the same ref, the text is not
     * sent again. Returns CODE_OK with the text's seq, or why it was not sent.
     */
    fun sendFromPeer(ctx: Context, ref: String, to: String, body: String, slot: Int): Pair<Code, Long> {
        synchronized(book) { book.seqOf(ref) }?.let { return Code.CODE_OK to it }
        refusal(ctx, to, body, slot)?.let { return it to 0L }
        val seq = Events.log(ctx).nextText()
        synchronized(book) { book.queued(seq, ref) }
        send(ctx, seq, to, body, slot)
        return Code.CODE_OK to seq
    }

    private fun refusal(ctx: Context, to: String, body: String, slot: Int): Code? = when {
        to.isBlank() || body.isEmpty() -> Code.CODE_BAD_VALUE
        slot >= 0 && Sims.active(ctx).none { it.simSlotIndex == slot } -> Code.CODE_LINE_OFF
        else -> null
    }

    /** Hands the text to the phone system; its statuses follow through [SentReceiver] and [DeliveredReceiver]. */
    fun send(ctx: Context, seq: Long, to: String, body: String, slot: Int) {
        val subId = Sims.subIdForSlot(ctx, slot)
        @Suppress("DEPRECATION")
        val sm = if (android.os.Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
                 else SmsManager.getSmsManagerForSubscriptionId(subId)
        val parts = sm.divideMessage(body)
        val sent = ArrayList<PendingIntent>()
        val delivery = ArrayList<PendingIntent>()
        parts.forEachIndexed { i, _ ->
            val intent = Intent(ACTION_SENT).setPackage(ctx.packageName).putExtra("seq", seq).putExtra("last", i == parts.size - 1)
            sent += PendingIntent.getBroadcast(ctx, ("s$seq-$i").hashCode(), intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            // Delivery reports: MediaTek's VoLTE SMS (Redmi + Vodafone TR, 19 Oct) reported "generic failure" two
            // minutes after the network had accepted and delivered the message; the report is the real answer.
            val d = Intent(ACTION_DELIVERED).setPackage(ctx.packageName).putExtra("seq", seq).putExtra("last", i == parts.size - 1)
            delivery += PendingIntent.getBroadcast(ctx, ("d$seq-$i").hashCode(), d,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)   // mutable: the system adds the report
        }
        sentAt[seq] = android.os.SystemClock.elapsedRealtime()
        if (sentAt.size > 50) sentAt.keys.minOrNull()?.let { sentAt.remove(it) }
        Log.i(TAG, "text $seq: ${parts.size} part(s) on sub $subId")
        sm.sendMultipartTextMessage(to, null, parts, sent, delivery)
        ctx.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, ContentValues().apply {
            put(Telephony.Sms.ADDRESS, to); put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis()); put(Telephony.Sms.SUBSCRIPTION_ID, subId)
        })
    }

    /** The text reached [phase]: the peers hear it when it is a step forward. */
    private fun reached(seq: Long, phase: TextPhase, error: Int = 0) {
        val status = synchronized(book) { book.advance(seq, phase, error) } ?: return
        Relay.textStatus(status)
    }

    /** Delivery report for the last part: delivered (it also overrides an earlier reported failure). */
    class DeliveredReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val seq = intent.getLongExtra("seq", 0)
            if (seq == 0L || !intent.getBooleanExtra("last", false)) return
            val pdu = intent.getByteArrayExtra("pdu") ?: return
            val status = runCatching { android.telephony.SmsMessage.createFromPdu(pdu, intent.getStringExtra("format")).status }.getOrDefault(-1)
            Log.i(TAG, "text $seq delivery report: status 0x${Integer.toHexString(status)}")
            if (status in 0 until 0x20) reached(seq, TextPhase.TEXT_PHASE_DELIVERED)          // TP-Status 0x00-0x1F: delivered
        }
    }

    /** Result of each part; we report the last part (or the first failure). */
    class SentReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val seq = intent.getLongExtra("seq", 0)
            if (seq == 0L) return
            val ok = resultCode == Activity.RESULT_OK
            if (ok && !intent.getBooleanExtra("last", false)) return
            // A generic failure only after a long wait is a timeout, not a refusal: MediaTek's VoLTE SMS (Redmi +
            // Vodafone TR) returns it ~130 s after the network accepted and delivered the message. Real refusals
            // (no service, bad number) come back within seconds. The client shows "not confirmed", not "failed".
            val now = android.os.SystemClock.elapsedRealtime()
            val waited = now - (sentAt[seq] ?: now)
            val unconfirmed = !ok && resultCode == SmsManager.RESULT_ERROR_GENERIC_FAILURE && waited > 60_000
            Log.i(TAG, "text $seq sent=$ok (result $resultCode after ${waited / 1000} s)${if (unconfirmed) ": not confirmed" else ""}")
            when {
                ok -> reached(seq, TextPhase.TEXT_PHASE_SENT)
                unconfirmed -> { failed(ctx, true, resultCode, waited); reached(seq, TextPhase.TEXT_PHASE_UNCONFIRMED) }
                else -> { failed(ctx, false, resultCode, waited); reached(seq, TextPhase.TEXT_PHASE_FAILED, resultCode) }
            }
        }

        /**
         * A failed text's report. A short code refused means Android asked on the host's screen whether to text it, and
         * nobody answered (RootSms): the answer is given again for the next one.
         */
        private fun failed(ctx: Context, unconfirmed: Boolean, result: Int, waited: Long) {
            val shortCode = result == SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED ||
                result == SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED
            val detail = org.json.JSONObject().put("what", if (unconfirmed) "unconfirmed" else "result $result").put("seconds", waited / 1000)
            if (shortCode) detail.put("short_codes", RootSms.last)
            Diag.event("sms_send_failed", detail)
            if (shortCode) RootSms.set(ctx, "allow", "a text was refused")
        }
    }
}
