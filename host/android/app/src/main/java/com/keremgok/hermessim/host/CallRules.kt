// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.keremgok.hermessim.protocol.v1.CallRules as Rules

/**
 * Blocked numbers and the busy reply (protocol "CallRules"). Numbers match on their last 10 digits. While busy, a
 * text gets the reply once an hour per number at most; short codes, senders with letters, hidden and blocked numbers
 * never get one. Pure: [CallRuleStore] keeps the rules and the reply times across host restarts.
 */
class CallRulesCore(rules: Rules = Rules.getDefaultInstance(), replied: Map<String, Long> = emptyMap()) {
    var rules: Rules = rules
        private set
    private val replied = replied.toMutableMap()

    /** The rules a client set, cut to the limits: at most [MAX_NUMBERS] distinct numbers, the reply at most [MAX_TEXT_BYTES]. */
    fun set(wanted: Rules) {
        val numbers = wanted.blockedList.map(String::trim).filter { key(it).isNotEmpty() }.distinctBy(::key).take(MAX_NUMBERS)
        rules = wanted.toBuilder().clearBlocked().addAllBlocked(numbers).setBusyText(cut(wanted.busyText.trim())).build()
    }

    fun blocksCall(number: String, withheld: Boolean): Boolean = (withheld && rules.blockHidden) || blocked(number)

    fun blocksText(from: String): Boolean = blocked(from)

    /** True when [from]'s text gets the busy reply now; the reply then counts as sent for an hour. */
    fun autoReply(from: String, now: Long): Boolean {
        if (!rules.busy || rules.busyText.isEmpty() || !replyable(from) || blocked(from)) return false
        val k = key(from)
        if (now - (replied[k] ?: Long.MIN_VALUE / 2) < HOUR_MS) return false
        replied.entries.removeAll { now - it.value >= HOUR_MS }
        replied[k] = now
        return true
    }

    /** The numbers answered within the last hour, with when. */
    fun replies(): Map<String, Long> = replied.toMap()

    private fun blocked(number: String): Boolean = key(number).let { k -> k.isNotEmpty() && rules.blockedList.any { key(it) == k } }

    companion object {
        const val MAX_NUMBERS = 50
        const val MAX_TEXT_BYTES = 300
        const val HOUR_MS = 3_600_000L
        private const val KEY_DIGITS = 10
        private const val SHORT_CODE_DIGITS = 6

        fun key(number: String): String = number.filter(Char::isDigit).takeLast(KEY_DIGITS)

        /** A real number: an optional leading +, then only digits, more than a short code's. */
        fun replyable(from: String): Boolean {
            val digits = from.removePrefix("+")
            return digits.length > SHORT_CODE_DIGITS && digits.all(Char::isDigit)
        }

        /** [text] cut to [MAX_TEXT_BYTES] of UTF-8 without splitting a character. */
        fun cut(text: String): String {
            var out = text
            while (out.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) out = out.dropLast(1)
            return out
        }
    }
}

/** [CallRulesCore] with its state in the "call_rules" prefs. Main thread. */
object CallRuleStore {
    private lateinit var prefs: SharedPreferences
    private lateinit var core: CallRulesCore

    /** Idempotent: the call monitor, the SMS receiver and the relay may each start first. */
    fun init(ctx: Context) {
        if (::core.isInitialized) return
        val stored = ctx.getSharedPreferences("call_rules", Context.MODE_PRIVATE)
        prefs = stored
        val rules = stored.getString("rules", null)
            ?.let { runCatching { Rules.parseFrom(Base64.decode(it, Base64.NO_WRAP)) }.getOrNull() }
        val replied = stored.getStringSet("replied", emptySet()).orEmpty().mapNotNull { entry ->
            entry.substringAfter(':').toLongOrNull()?.let { entry.substringBefore(':') to it }
        }.toMap()
        core = CallRulesCore(rules ?: Rules.getDefaultInstance(), replied)
    }

    val rules: Rules get() = if (::core.isInitialized) core.rules else Rules.getDefaultInstance()

    fun set(wanted: Rules) {
        core.set(wanted)
        save()
    }

    fun blocksCall(number: String, withheld: Boolean): Boolean = ::core.isInitialized && core.blocksCall(number, withheld)

    fun blocksText(from: String): Boolean = ::core.isInitialized && core.blocksText(from)

    /** True when [from] gets the busy reply now; the reply time is stored. */
    fun autoReply(from: String): Boolean =
        (::core.isInitialized && core.autoReply(from, System.currentTimeMillis())).also { if (it) save() }

    private fun save() = with(prefs.edit()) {
        putString("rules", Base64.encodeToString(core.rules.toByteArray(), Base64.NO_WRAP))
        putStringSet("replied", core.replies().map { (k, at) -> "$k:$at" }.toSet())
        apply()
    }
}
