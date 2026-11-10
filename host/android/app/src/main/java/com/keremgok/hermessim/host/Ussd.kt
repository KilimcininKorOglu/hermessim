// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import com.keremgok.hermessim.protocol.v1.UssdRequest
import com.keremgok.hermessim.protocol.v1.UssdResult

/** What a client may run as a USSD code, and how Android's failures read (protocol "UssdRequest"). Pure. */
object UssdCode {
    const val MAX_LENGTH = 32
    private const val DIGITS_AND_MARKS = "0123456789*#"

    /** Starts with * or #, ends with #, only 0-9 * #, at most [MAX_LENGTH] characters. */
    fun valid(code: String): Boolean = code.length in 2..MAX_LENGTH && code.first() in "*#" && code.last() == '#' &&
        code.all { it in DIGITS_AND_MARKS }

    /** TelephonyManager.USSD_ERROR_SERVICE_UNAVAIL means the line can't run it now; anything else is the network's no. */
    fun failure(androidCode: Int): UssdResult.Failure =
        if (androidCode == TelephonyManager.USSD_ERROR_SERVICE_UNAVAIL) UssdResult.Failure.NO_SERVICE else UssdResult.Failure.NETWORK

    fun failed(why: UssdResult.Failure): UssdResult = UssdResult.newBuilder().setFailure(why).build()

    fun answered(text: CharSequence): UssdResult = UssdResult.newBuilder().also {
        it.ok = true
        it.text = text.toString()
    }.build()
}

/**
 * Runs one USSD code at a time on a line ([TelephonyManager.sendUssdRequest]). The answer comes once: the network's
 * text, its refusal, or [TIMEOUT_MS] without either. Main thread only.
 */
object Ussd {
    private const val TAG = "Ussd"
    const val TIMEOUT_MS = 30_000L

    /** The answer of the code running now; null when none runs. */
    private var pending: ((UssdResult) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun run(ctx: Context, req: UssdRequest, main: Handler, answer: (UssdResult) -> Unit) {
        if (!UssdCode.valid(req.code)) return answer(UssdCode.failed(UssdResult.Failure.BAD_CODE))
        if (pending != null) return answer(UssdCode.failed(UssdResult.Failure.NETWORK))
        val sub = Sims.subIdForSlot(ctx, req.slot)
        if (!SubscriptionManager.isValidSubscriptionId(sub)) return answer(UssdCode.failed(UssdResult.Failure.NO_SERVICE))
        val done = once(main, answer)
        val tm = ctx.getSystemService(TelephonyManager::class.java).createForSubscriptionId(sub)
        val callback = object : TelephonyManager.UssdResponseCallback() {
            override fun onReceiveUssdResponse(t: TelephonyManager, request: String, response: CharSequence) =
                done(UssdCode.answered(response))

            override fun onReceiveUssdResponseFailed(t: TelephonyManager, request: String, failureCode: Int) {
                Log.i(TAG, "USSD on slot ${req.slot} failed: $failureCode")
                done(UssdCode.failure(failureCode).let(UssdCode::failed))
            }
        }
        runCatching { tm.sendUssdRequest(req.code, callback, main) }.onFailure {
            Log.w(TAG, "USSD could not start: $it")
            done(UssdCode.failed(UssdResult.Failure.NETWORK))
        }
    }

    /** [answer] wrapped so it runs once, by whichever comes first: the network or the timeout. */
    private fun once(main: Handler, answer: (UssdResult) -> Unit): (UssdResult) -> Unit {
        val timeout = Runnable { finish(answer, UssdCode.failed(UssdResult.Failure.TIMEOUT)) }
        pending = answer
        main.postDelayed(timeout, TIMEOUT_MS)
        return { r ->
            main.removeCallbacks(timeout)
            finish(answer, r)
        }
    }

    private fun finish(answer: (UssdResult) -> Unit, r: UssdResult) {
        if (pending !== answer) return
        pending = null
        answer(r)
    }
}
