// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import com.keremgok.hermessim.host.wire.StateBook
import com.keremgok.hermessim.wire.Code
import com.keremgok.hermessim.wire.HotspotSecret
import com.keremgok.hermessim.wire.LineLockNow
import com.keremgok.hermessim.wire.LogPart
import com.keremgok.hermessim.wire.Request
import com.keremgok.hermessim.wire.Role
import com.keremgok.hermessim.wire.SendText
import com.keremgok.hermessim.wire.SetHotspotSetup
import com.keremgok.hermessim.wire.SetLine
import com.keremgok.hermessim.wire.SetPeer
import com.keremgok.hermessim.wire.TextOrigin
import com.keremgok.hermessim.wire.TextQueued
import com.keremgok.hermessim.wire.UssdAnswer

/**
 * The requests of a peer that is served (protocol/v2/SPEC.md "Requests"; a Mac's as "Macs" allows). Each request has
 * one handler, and each handler answers exactly once (GetLog: once per part), at once or when the phone system has
 * answered. Main thread.
 */
object Requests {
    private const val TAG = "HermesSimHost"
    private lateinit var app: Context
    private lateinit var main: Handler

    private val lineGroups = intArrayOf(StateBook.LINES, StateBook.SIGNAL, StateBook.NETWORK)

    private val handlers: Map<Request.OpCase, (Ask) -> Unit> = mapOf(
        Request.OpCase.GET_STATE to ::getState,
        Request.OpCase.DIAL to ::dial,
        Request.OpCase.CALL_CONTROL to ::callControl,
        Request.OpCase.SEND_TONES to ::sendTones,
        Request.OpCase.SEND_TEXT to ::sendText,
        Request.OpCase.RUN_USSD to ::runUssd,
        Request.OpCase.SET_LINE to ::setLine,
        Request.OpCase.UNLOCK_LINE to ::unlockLine,
        Request.OpCase.SET_DATA_LINE to ::setDataLine,
        Request.OpCase.SET_HOTSPOT to ::setHotspot,
        Request.OpCase.SET_HOTSPOT_SETUP to ::setHotspotSetup,
        Request.OpCase.SET_HOTSPOT_CLIENT to ::setHotspotClient,
        Request.OpCase.GET_HOTSPOT_SECRET to ::getHotspotSecret,
        Request.OpCase.SET_MOBILE_DATA to ::setMobileData,
        Request.OpCase.SET_HOST_WIFI to ::setHostWifi,
        Request.OpCase.SAVE_WIFI to ::saveWifi,
        Request.OpCase.FORGET_WIFI to ::forgetWifi,
        Request.OpCase.SCAN_WIFI to ::scanWifi,
        Request.OpCase.SET_BOX to ::setBox,
        Request.OpCase.SET_CALL_RULES to ::setCallRules,
        Request.OpCase.HOST_ACTION to ::hostAction,
        Request.OpCase.UPDATE_CONTROL to ::updateControl,
        Request.OpCase.GET_LOG to ::getLog,
        Request.OpCase.SET_REMOTE to ::setRemote,
        Request.OpCase.SET_PUSH_KEY to ::setPushKey,
        Request.OpCase.OPEN_AUDIO_PATH to ::openAudioPath,
        Request.OpCase.CLOSE_AUDIO_PATH to ::closeAudioPath,
        Request.OpCase.AUDIO_REPORT to ::audioReport,
        Request.OpCase.CALL_TEST_CHECK to ::callTestCheck,
        Request.OpCase.CALL_TEST_VERDICT to ::callTestVerdict,
        Request.OpCase.OPEN_PEER_WINDOW to ::openPeerWindow,
        Request.OpCase.SET_PEER to ::setPeer,
        Request.OpCase.FORGET_HOST to ::forgetHost,
    )

    fun handle(ctx: Context, handler: Handler, link: Link, req: Request) {
        app = ctx
        main = handler
        // Asked again after a resume: the same reply, and nothing runs twice.
        link.channel.replies.find(req.id)?.let { return Relay.resend(link, it, req.opCase) }
        if (!link.running.add(req.id)) return                     // still running: its reply follows
        val ask = Ask(link, req, link.channel.sessionId)
        val refusal = refusal(link, req)
        if (refusal != null) return ask.answer(refusal)
        (handlers[req.opCase] ?: ::unknown)(ask)
    }

    private fun refusal(link: Link, req: Request): Code? =
        (if (link.role == Role.ROLE_MAC) MacRules.refusal(req, Relay.macs.grants(link.macId)) else null)
            ?: RequestRules.refusal(req, link.bluetooth)
            ?: RequestRules.voiceRefusal(req, VoiceGate.fullVoice, Daemon.hasProfile())
            // Test rig running (adb override): the real phone's switches would fight the scenario.
            ?: Code.CODE_NOT_ALLOWED.takeIf { HostWifi.clientOverride != null && req.opCase in RequestRules.heldByRig }

    private fun unknown(a: Ask) = a.answer(Code.CODE_UNKNOWN_REQUEST)

    private fun getState(a: Ask) {
        Relay.sendState(a.link, a.req.getState.groupsList)
        a.ok()
    }

    // ------------------------------------------------------------- calls and texts

    private fun dial(a: Ask) = with(a.req.dial) {
        a.answer(CallRegistry.dial(number, if (hasLine()) line else -1, CallCore.refText(clientRef), test))
    }

    private fun callControl(a: Ask) = with(a.req.callControl) { a.answer(CallRegistry.control(call, verb)) }

    private fun sendTones(a: Ask) = with(a.req.sendTones) { a.answer(CallRegistry.tones(call, digits)) }

    private fun sendText(a: Ask) = with(a.req.sendText) {
        if (a.link.role == Role.ROLE_MAC) return@with macText(a)
        val (code, seq) = SmsSender.sendFromPeer(app, CallCore.refText(clientRef), to, body, if (hasLine()) line else -1)
        a.answer(code) { reply -> if (code == Code.CODE_OK) reply.setText(TextQueued.newBuilder().setText(seq)) }
    }

    /** A Mac answers a text it was given: to its sender, on its line; the phone sees it as TextByHost. */
    private fun macText(a: Ask) = with(a.req.sendText) {
        val line = if (hasLine()) line else null
        when (val code = MacRules.textRefusal(this, Relay.macs.answers(to, line))) {
            null -> { SmsSender.sendFromHost(app, to, body, line ?: -1, TextOrigin.TEXT_ORIGIN_MAC); a.ok() }
            Code.CODE_BAD_VALUE -> a.answer(code, SendText.BODY_FIELD_NUMBER.toLong())
            else -> a.answer(code)
        }
    }

    private fun runUssd(a: Ask) = with(a.req.runUssd) {
        Ussd.run(app, if (hasLine()) line else -1, code, main) { result, text ->
            a.answer(result) { reply -> reply.setUssd(UssdAnswer.newBuilder().setText(text)) }
        }
    }

    // ------------------------------------------------------------- lines

    private fun setLine(a: Ask) {
        val s = a.req.setLine
        val code = when (s.settingCase) {
            SetLine.SettingCase.ENABLED -> Sims.setEnabled(app, s.line, s.enabled)
            SetLine.SettingCase.ROAMING -> Sims.setRoaming(app, s.line, s.roaming)
            SetLine.SettingCase.CEILING -> Sims.setCeiling(app, s.line, s.ceiling, CallRegistry.anyCall())
            else -> Code.CODE_BAD_VALUE
        }
        a.answer(code)
        settle(1500, 6000)                    // a SIM switched on or off takes a few seconds to settle
    }

    private fun unlockLine(a: Ask) = with(a.req.unlockLine) {
        Sims.unlock(app, line, pin, puk) { r ->
            val lock = LineLockNow.newBuilder().setLock(r.lock)
            if (r.attemptsLeft >= 0) lock.triesLeft = r.attemptsLeft
            if (r.ok) a.answer(Code.CODE_OK) { it.setLineLock(lock) }
            else a.answer(Code.CODE_WRONG_PIN, r.attemptsLeft.toLong()) { it.setLineLock(lock) }
            settle(2000, 8000)                // the SIM loads, then registers
        }
    }

    private fun setDataLine(a: Ask) {
        a.answer(Sims.setDataLine(app, a.req.setDataLine.line))
        settle(1500, 6000)
    }

    private fun settle(vararg afterMs: Long) = afterMs.forEach { Relay.touchLater(it, *lineGroups) }

    // ------------------------------------------------------------- hotspot

    private fun setHotspot(a: Ask) = with(a.req.setHotspot) { Relay.switchHotspot(on, offWhenUnusedS, a::answer) }

    /** The month limit and the call pause are the host's own settings; the rest is saved in the phone's hotspot. */
    private fun setHotspotSetup(a: Ask) {
        val s = a.req.setHotspotSetup
        // Pausing during calls is only for hosts whose hotspot can only use 2.4 GHz
        if (s.hasPauseInCalls() && s.pauseInCalls && !Hotspot.callPauseOffered(app)) return a.answer(Code.CODE_NOT_OFFERED)
        if (s.hasWatch() && !WatchSettings.valid(s.watch)) return a.answer(Code.CODE_BAD_VALUE, SetHotspotSetup.WATCH_FIELD_NUMBER.toLong())
        val now = Hotspot.info(app)
        val wanted = HotspotCore.wanted(now, s)
        val radio = HotspotCore.touchesRadio(s) && wanted != HotspotCore.wanted(now, SetHotspotSetup.getDefaultInstance())
        if (radio) HotspotCore.refusal(wanted)?.let { (code, field) -> return a.answer(code, field.toLong()) }
        if (s.hasMonthLimitBytes() && DataLimit.set(s.monthLimitBytes)) Log.i(TAG, "hotspot month limit ${s.monthLimitBytes / 1_000_000} MB")
        if (s.hasPauseInCalls()) HotspotPause.setSetting(s.pauseInCalls)
        if (s.hasWatch()) HotspotWatch.setSetting(s.watch)
        Relay.touch(StateBook.HOTSPOT)
        if (!radio) return a.ok()             // a running hotspot is not started again for these
        Hotspot.configure(app, wanted) { code ->
            a.answer(code)
            Relay.touchLater(1500, StateBook.HOTSPOT)
        }
    }

    private fun setHotspotClient(a: Ask) = with(a.req.setHotspotClient) {
        val address = ApClient.text(mac.toByteArray())
        when {
            address.isEmpty() -> a.answer(Code.CODE_BAD_VALUE, 1)
            HotspotGuard.set(address, blocked, name) -> a.ok()
            else -> a.answer(Code.CODE_LIMIT_REACHED, HotspotGuardCore.MAX_BLOCKED.toLong())
        }
    }

    private fun getHotspotSecret(a: Ask) = a.answer(Code.CODE_OK) { reply ->
        reply.setHotspotSecret(HotspotSecret.newBuilder().setPassphrase(Hotspot.info(app).passphrase))
    }

    // ------------------------------------------------------------- host settings

    private fun setMobileData(a: Ask) {
        a.answer(if (MobileData.set(app, a.req.setMobileData.on)) Code.CODE_OK else Code.CODE_FAILED)
        Relay.touchLater(1500, StateBook.NETWORK)
    }

    private fun setHostWifi(a: Ask) {
        HostWifi.setUseWhenAway(a.req.setHostWifi.useWhenAway, remote = !a.link.bluetooth)
        a.ok()
    }

    private fun scanWifi(a: Ask) = HostWifi.scan { list, code -> a.answer(code) { reply -> list?.let(reply::setWifiList) } }

    private fun saveWifi(a: Ask) = HostWifi.add(a.req.saveWifi.name, a.req.saveWifi.passphrase) { code ->
        a.answer(code)
        Relay.touch(StateBook.WIFI, StateBook.NETWORK)
    }

    private fun forgetWifi(a: Ask) = HostWifi.forget(a.req.forgetWifi.id) {
        a.ok()
        Relay.touch(StateBook.WIFI)
    }

    private fun setBox(a: Ask) {
        val s = a.req.setBox
        if (s.hasChargeLimit()) Box.setChargeLimit(s.chargeLimit)
        if (s.hasBoxMode()) Box.set(s.boxMode)
        a.ok()
        Relay.touchLater(1500, StateBook.POWER, *lineGroups)
    }

    private fun setCallRules(a: Ask) = a.req.setCallRules.rules.let(CallRuleStore::set).also { a.ok(); Relay.touch(StateBook.RULES) }

    private fun hostAction(a: Ask) = HostActions.run(app, a.req.hostAction.verb, main) { code -> a.answer(code) }

    private fun updateControl(a: Ask) {
        Updater.action(a.req.updateControl.verb) { code, fault ->
            if (code == Code.CODE_UPDATE_FAILED) a.answer(code, fault.number.toLong()) else a.answer(code)
            Relay.touch(StateBook.UPDATE)
        }
    }

    /**
     * The host's scrubbed log and facts, in parts. The phone waits a limited time for it, so the log is read with
     * the short root timeouts ([Diag.recentLog] fast).
     */
    private fun getLog(a: Ask) = Thread {
        val t0 = SystemClock.elapsedRealtime()
        val max = a.req.getLog.maxBytes
        val log = Diag.recentLog(if (max in 1..32_000) max else 16_000, fast = true)
        val facts = Diag.facts("event").apply {
            remove("install_id"); remove("kind")
            Compat.bleCaps(app)?.let { put("ble", it) }
            CallAudioCheck.lastForReport()?.let { put("last_call", it) }     // the last call's audio numbers, poor or not
        }.toString()
        val parts = utf8Chunks(log, 2_800).ifEmpty { listOf("") }
        parts.forEachIndexed { i, chunk ->
            val part = LogPart.newBuilder().setText(chunk).setLast(i == parts.lastIndex)
            if (i == 0) part.factsJson = facts
            a.answer(Code.CODE_OK) { it.setLog(part) }
        }
        Log.i(TAG, "log sent to the phone: ${log.length} chars in ${parts.size} parts, ready after ${SystemClock.elapsedRealtime() - t0} ms")
    }.start()

    // ------------------------------------------------------------- internet mode

    private fun setRemote(a: Ask) = with(a.req.setRemote) {
        if (on && roomKey.size() != 32) return@with a.answer(Code.CODE_BAD_VALUE, 2)
        RemoteLink.configure(on, roomKey.toByteArray())
        a.ok()
        Relay.touchLater(500, StateBook.REMOTE)
    }

    private fun setPushKey(a: Ask) {
        val key = a.req.setPushKey.key.toByteArray()
        if (key.isNotEmpty() && key.size != 32) return a.answer(Code.CODE_BAD_VALUE, 1)
        Push.setKey(key)
        a.ok()
        Relay.touchLater(300, StateBook.REMOTE)
    }

    private fun openAudioPath(a: Ask) {
        val o = a.req.openAudioPath
        AudioPathCore.refusal(o)?.let { (code, field) -> return a.answer(code, field.toLong()) }
        InternetPath.open(app, o,
            opened = { code, path -> a.answer(code) { reply -> path?.let(reply::setAudioPath) } },
            closed = { Relay.audioPathClosed() })
    }

    private fun closeAudioPath(a: Ask) {
        InternetPath.close("the phone closed it")
        a.ok()
    }

    /** The phone's numbers set the daemon's expected loss; the answer is the host's numbers since the last report. */
    private fun audioReport(a: Ask) {
        AudioPathCore.loss(a.req.audioReport)?.let(Daemon::loss)
        Daemon.query("AUDIO?") { reply -> a.answer(Code.CODE_OK) { it.setAudioReport(AudioRouter.report(reply)) } }
    }

    // ------------------------------------------------------------- the call test

    private fun callTestCheck(a: Ask) {
        VoiceGate.check { reading -> a.answer(Code.CODE_OK) { it.setCallTest(reading) } }
    }

    private fun callTestVerdict(a: Ask) {
        val verdict = a.req.callTestVerdict
        val fault = VoiceGate.verdict(verdict)
        VoiceGate.report(verdict, fault)
        if (fault == null) a.ok() else a.answer(Code.CODE_CALL_TEST_FAILED, fault.number.toLong())
        Relay.touch(StateBook.VOICE, StateBook.HOTSPOT_LIMITS)
    }

    // ------------------------------------------------------------- Mac peers and the end of the pairing

    private fun openPeerWindow(a: Ask) {
        Relay.macs.setWindow(a.req.openPeerWindow.open)
        a.ok()
    }

    private fun setPeer(a: Ask) {
        val p = a.req.setPeer
        val id = hexOf(p.id.toByteArray())
        if (!Relay.macs.known(id)) return a.answer(Code.CODE_BAD_VALUE, SetPeer.ID_FIELD_NUMBER.toLong())
        when (p.changeCase) {
            SetPeer.ChangeCase.REMOVE -> Relay.removeMac(id)
            SetPeer.ChangeCase.GRANTS -> Relay.macs.setGrants(id, p.grants.texts, p.grants.control)
            else -> return a.answer(Code.CODE_BAD_VALUE)
        }
        a.ok()
    }

    private fun forgetHost(a: Ask) {
        a.ok()                                // the phone waits for this
        Relay.forgetPhone(a.link)
    }
}
