// HermesSIM host. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.protobuf.ByteString
import com.keremgok.hermessim.host.wire.StateBook
import com.keremgok.hermessim.wire.CancelScheduled
import com.keremgok.hermessim.wire.Code
import com.keremgok.hermessim.wire.ScheduleText
import com.keremgok.hermessim.wire.ScheduledText
import com.keremgok.hermessim.wire.ScheduledTexts as ScheduledGroup

/**
 * The texts the host keeps and sends at their time (protocol/v2/SPEC.md "Requests", `ScheduleText`). Pure: the
 * caller gives the clock and stores [texts].
 */
class ScheduledCore(texts: List<ScheduledText> = emptyList()) {
    /** Why a text is not taken: [code] and the number the reply carries in args[0]. */
    data class Refusal(val code: Code, val arg: Long)

    private val list = texts.sortedBy { it.sendAtMs }.toMutableList()

    /** The texts that wait, the soonest first. */
    val texts: List<ScheduledText> get() = list.toList()

    /**
     * Takes [req] at [nowMs]; [lineOff] is true when the named line is off. Returns null when the text waits now,
     * also when a text with its ref already waits.
     */
    fun add(req: ScheduleText, nowMs: Long, lineOff: Boolean): Refusal? {
        val text = req.text
        if (list.any { it.clientRef == text.clientRef }) return null
        refusal(req, nowMs, lineOff)?.let { return it }
        val entry = ScheduledText.newBuilder().setClientRef(text.clientRef).setTo(text.to).setBody(text.body)
            .setSendAtMs(req.sendAtMs)
        if (text.hasLine()) entry.line = text.line
        list += entry.build()
        list.sortBy { it.sendAtMs }
        return null
    }

    private fun refusal(req: ScheduleText, nowMs: Long, lineOff: Boolean): Refusal? = when {
        req.text.to.isBlank() || req.text.body.isEmpty() -> Refusal(Code.CODE_BAD_VALUE, ScheduleText.TEXT_FIELD_NUMBER.toLong())
        req.sendAtMs <= nowMs || req.sendAtMs > nowMs + MAX_AHEAD_MS ->
            Refusal(Code.CODE_BAD_VALUE, ScheduleText.SEND_AT_MS_FIELD_NUMBER.toLong())
        lineOff -> Refusal(Code.CODE_LINE_OFF, 0)
        list.size >= MAX_TEXTS -> Refusal(Code.CODE_LIMIT_REACHED, MAX_TEXTS.toLong())
        else -> null
    }

    /** Removes and returns the text with [ref], or null when none waits. */
    fun take(ref: ByteString): ScheduledText? {
        val index = list.indexOfFirst { it.clientRef == ref }
        return if (index < 0) null else list.removeAt(index)
    }

    /** Removes and returns the texts due at [nowMs]. */
    fun due(nowMs: Long): List<ScheduledText> = list.filter { it.sendAtMs <= nowMs }.also { list.removeAll(it) }

    /** Removes and returns the texts more than [LATE_MS] late at [nowMs]: they are not sent any more. */
    fun tooLate(nowMs: Long): List<ScheduledText> = list.filter { nowMs - it.sendAtMs > LATE_MS }.also { list.removeAll(it) }

    /** When the soonest text is due, or null when none waits. */
    fun nextAtMs(): Long? = list.firstOrNull()?.sendAtMs

    companion object {
        const val MAX_TEXTS = 50
        const val MAX_AHEAD_MS = 366L * 24 * 3600 * 1000
        const val LATE_MS = 24L * 3600 * 1000
    }
}

/** The host side of scheduled texts: kept in prefs "scheduled", sent by a wake alarm. Main thread. */
object ScheduledTexts {
    private const val TAG = "HermesSimHost"
    private const val ALARM = "scheduled-text"
    private const val KEY = "texts"
    /** The longest wait of one alarm: a clock change of the phone is caught up by the next one. */
    private const val MAX_WAIT_MS = 6L * 3600 * 1000

    private lateinit var app: Context
    private var core = ScheduledCore()
    private var changed: () -> Unit = {}

    /** Loads the texts, drops those too late, sends those due and sets the alarm for the next one. */
    fun init(ctx: Context, onChange: () -> Unit) {
        app = ctx.applicationContext
        changed = onChange
        core = ScheduledCore(load())
        val late = core.tooLate(System.currentTimeMillis())
        late.forEach { text ->
            Log.w(TAG, "scheduled text more than a day late: not sent")
            SmsSender.failQueued(app, CallCore.refText(text.clientRef))
        }
        if (late.isNotEmpty()) save()
        fire()
    }

    /** The `scheduled` State group. */
    fun group(): ScheduledGroup = ScheduledGroup.newBuilder().addAllTexts(core.texts).build()

    /** A peer's ScheduleText: null when the text waits now, else why not. */
    fun add(req: ScheduleText): ScheduledCore.Refusal? {
        val line = if (req.text.hasLine()) req.text.line else -1
        val lineOff = line >= 0 && Sims.active(app).none { it.simSlotIndex == line }
        val refusal = core.add(req, System.currentTimeMillis(), lineOff)
        if (refusal == null) { save(); arm(); changed() }
        return refusal
    }

    /** A peer's CancelScheduled: the text is dropped, or sent at once with `send_now`. Always CODE_OK. */
    fun cancel(req: CancelScheduled): Code {
        val text = core.take(req.clientRef) ?: return Code.CODE_OK
        save()
        if (req.sendNow) send(text)
        arm()
        changed()
        return Code.CODE_OK
    }

    private fun fire() {
        val due = core.due(System.currentTimeMillis())
        if (due.isNotEmpty()) { save(); due.forEach(::send); changed() }
        arm()
    }

    private fun send(text: ScheduledText) {
        val ref = CallCore.refText(text.clientRef)
        val (code, seq) = SmsSender.sendFromPeer(app, ref, text.to, text.body, if (text.hasLine()) text.line else -1)
        Log.i(TAG, "scheduled text sent: $code, text $seq")
        if (code != Code.CODE_OK) SmsSender.failQueued(app, ref)
    }

    private fun arm() {
        val next = core.nextAtMs() ?: return WakeAlarm.cancel(app, ALARM)
        val wait = (next - System.currentTimeMillis()).coerceIn(0L, MAX_WAIT_MS)
        WakeAlarm.set(app, ALARM, wait) { fire() }
    }

    private fun load(): List<ScheduledText> {
        val stored = app.getSharedPreferences("scheduled", Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return stored.split(',').filter { it.isNotEmpty() }.mapNotNull { entry ->
            runCatching { ScheduledText.parseFrom(Base64.decode(entry, Base64.NO_WRAP)) }
                .onFailure { Log.w(TAG, "scheduled text unreadable: dropped ($it)") }.getOrNull()
        }
    }

    @SuppressLint("ApplySharedPref")       // a text kept only in memory would be lost with the app
    private fun save() {
        val value = core.texts.joinToString(",") { Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP) }
        app.getSharedPreferences("scheduled", Context.MODE_PRIVATE).edit().putString(KEY, value).commit()
    }
}
