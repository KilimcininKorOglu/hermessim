// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import com.keremgok.hermessim.wire.Code

/** What a client may run as a USSD code, and how Android's failures read. Pure. */
object UssdCode {
    const val MAX_LENGTH = 32
    private const val DIGITS_AND_MARKS = "0123456789*#"

    /** Starts with * or #, ends with #, only 0-9 * #, at most [MAX_LENGTH] characters. */
    fun valid(code: String): Boolean = code.length in 2..MAX_LENGTH && code.first() in "*#" && code.last() == '#' &&
        code.all { it in DIGITS_AND_MARKS }

    /** TelephonyManager.USSD_ERROR_SERVICE_UNAVAIL means the line can't run it now; anything else is the network's no. */
    fun failure(androidCode: Int): Code =
        if (androidCode == TelephonyManager.USSD_ERROR_SERVICE_UNAVAIL) Code.CODE_NO_SERVICE else Code.CODE_NETWORK_REFUSED
}

/**
 * Runs one USSD code at a time on a line ([TelephonyManager.sendUssdRequest]). The answer comes once: the network's
 * text with CODE_OK, its refusal, or [TIMEOUT_MS] without either. Main thread only.
 */
object Ussd {
    private const val TAG = "Ussd"
    const val TIMEOUT_MS = 30_000L

    /** The answer of the code running now; null when none runs. */
    private var pending: ((Code, String) -> Unit)? = null

    /** [slot] below 0: the phone system's default line for texts. */
    @SuppressLint("MissingPermission")
    fun run(ctx: Context, slot: Int, code: String, main: Handler, answer: (Code, String) -> Unit) {
        if (!UssdCode.valid(code)) return answer(Code.CODE_BAD_VALUE, "")
        if (pending != null) return answer(Code.CODE_BUSY, "")
        val sub = Sims.subIdForSlot(ctx, slot)
        if (!SubscriptionManager.isValidSubscriptionId(sub)) return answer(Code.CODE_NO_SERVICE, "")
        val done = once(main, answer)
        val tm = ctx.getSystemService(TelephonyManager::class.java).createForSubscriptionId(sub)
        val callback = object : TelephonyManager.UssdResponseCallback() {
            override fun onReceiveUssdResponse(t: TelephonyManager, request: String, response: CharSequence) =
                done(Code.CODE_OK, response.toString())

            override fun onReceiveUssdResponseFailed(t: TelephonyManager, request: String, failureCode: Int) {
                Log.i(TAG, "USSD on slot $slot failed: $failureCode")
                done(UssdCode.failure(failureCode), "")
            }
        }
        runCatching { tm.sendUssdRequest(code, callback, main) }.onFailure {
            Log.w(TAG, "USSD could not start: $it")
            done(Code.CODE_FAILED, "")
        }
    }

    /** [answer] wrapped so it runs once, by whichever comes first: the network or the timeout. */
    private fun once(main: Handler, answer: (Code, String) -> Unit): (Code, String) -> Unit {
        val timeout = Runnable { finish(answer, Code.CODE_TIMEOUT, "") }
        pending = answer
        main.postDelayed(timeout, TIMEOUT_MS)
        return { code, text ->
            main.removeCallbacks(timeout)
            finish(answer, code, text)
        }
    }

    private fun finish(answer: (Code, String) -> Unit, code: Code, text: String) {
        if (pending !== answer) return
        pending = null
        answer(code, text)
    }
}
