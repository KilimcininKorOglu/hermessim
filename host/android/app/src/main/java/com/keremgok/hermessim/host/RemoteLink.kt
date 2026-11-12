// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import com.keremgok.hermessim.host.wire.RelayFrame
import com.keremgok.hermessim.host.wire.RelayFrames
import com.keremgok.hermessim.host.wire.Tunnel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Internet mode (protocol/README.md "Relay", "Tunnel"): the host keeps one binary WebSocket to hermessim.keremgok.tr,
 * waits in its room, and runs the tunnel the phone opens there. The phone counts as present only once the tunnel is
 * open. The same socket carries the host's pushes. Runs only while a room key is set; the key arrives over Bluetooth
 * (SetRemote) and is kept in app-private prefs.
 */
object RemoteLink {
    private const val TAG = "HermesSimHost"
    private const val SLOT = 0
    private const val PUSH_PENDING = 64

    private lateinit var app: Context
    private lateinit var onFrames: (ByteArray) -> Unit
    private lateinit var onPeer: (present: Boolean) -> Unit
    private val thread = HandlerThread("remote").apply { start() }
    private val h = Bg.handler("remote", thread.looper)
    private val http = OkHttpClient.Builder().pingInterval(25, TimeUnit.SECONDS).build()
    private val rng = SecureRandom()
    private var ws: WebSocket? = null
    private var gen = 0                       // bumps on every (re)connect: callbacks from an old socket are ignored
    private var backoffMs = 2000L
    private var tunnel: Tunnel? = null
    private var pushRequest = 0
    private val pushes = LinkedHashMap<Int, String>()     // request number -> what, until its result comes
    @Volatile var crypto: RemoteCrypto? = null; private set
    @Volatile var online = false; private set      // in our room on the relay
    @Volatile var peerPresent = false; private set  // the phone opened the tunnel

    val enabled get() = crypto != null

    fun start(ctx: Context, onFrames: (ByteArray) -> Unit, onPeer: (present: Boolean) -> Unit) {
        app = ctx.applicationContext
        this.onFrames = onFrames
        this.onPeer = onPeer
        prefs().getString("key", null)?.let { crypto = RemoteCrypto(Base64.decode(it, Base64.NO_WRAP)) }
        app.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) { h.post { if (enabled && !online) { backoffMs = 2000; connect() } } }
        })
        h.post { if (enabled) connect() }
    }

    /** SetRemote from the paired phone over Bluetooth: a new room key moves the host to its room. */
    fun configure(on: Boolean, key: ByteArray?) = h.post {
        if (on && key != null && key.size == 32) {
            prefs().edit().putString("key", Base64.encodeToString(key, Base64.NO_WRAP)).apply()
            crypto = RemoteCrypto(key)
            Log.i(TAG, "remote mode on (room ${crypto!!.roomId.take(8)})")
            connect()
        } else {
            prefs().edit().remove("key").apply()
            crypto = null
            Log.i(TAG, "remote mode off")
            disconnect()
        }
    }

    /** Frames of the protocol 2 stream to the phone, sealed in the tunnel. */
    fun send(frames: ByteArray) = h.post {
        val w = ws ?: return@post
        // null: no open tunnel, or its counter ran out (then the phone must open a new one)
        val body = tunnel?.seal(frames) ?: return@post setPresent(false)
        w.send(RelayFrames.data(SLOT, body).bytes().toByteString())
    }

    /**
     * A push on the relay socket. False when the socket is not in its room; the caller then posts it over HTTPS.
     * The server answers with PUSH_RESULT, which is only logged.
     */
    fun push(roomToken: ByteArray, kind: String, collapseId: String, sealed: ByteArray, what: String): Boolean {
        val code = RelayFrames.PUSH_KINDS[kind] ?: return false
        if (!online) return false
        h.post {
            val w = ws ?: return@post
            pushRequest = (pushRequest + 1) and 0xFFFF
            pushes[pushRequest] = what
            while (pushes.size > PUSH_PENDING) pushes.remove(pushes.keys.first())
            w.send(RelayFrames.push(pushRequest, roomToken, code, collapseId, sealed).bytes().toByteString())
        }
        return true
    }

    private fun prefs() = app.getSharedPreferences("remote", Context.MODE_PRIVATE)

    private fun disconnect() {
        gen++
        ws?.close(1000, "bye"); ws = null
        tunnel = null
        setOnline(false); setPresent(false)
    }

    private fun connect() {
        disconnect()
        val c = crypto ?: return
        val my = gen
        tunnel = Tunnel(c.auth) { n -> ByteArray(n).also(rng::nextBytes) }
        Log.i(TAG, "remote: connecting")
        ws = http.newWebSocket(Request.Builder().url(Api.RELAY_URL).build(), Listener(my, c.roomToken))
    }

    private fun retry() {
        val my = gen
        h.postDelayed({ if (my == gen && enabled) connect() }, backoffMs)
        backoffMs = minOf(backoffMs * 2, 60_000)
    }

    private fun setOnline(v: Boolean) { if (online != v) { online = v; Relay.remoteChanged() } }

    private fun setPresent(v: Boolean) {
        if (peerPresent == v) return
        peerPresent = v
        Log.i(TAG, "remote: phone ${if (v) "opened the tunnel" else "gone"}")
        onPeer(v)
    }

    private fun closeTunnel(why: String) {
        tunnel?.reset()
        if (peerPresent) Log.i(TAG, "remote: tunnel closed ($why)")
        setPresent(false)
    }

    private class Listener(val my: Int, val roomToken: ByteArray) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(RelayFrames.join(SLOT, RelayFrames.ROLE_HOST, roomToken).bytes().toByteString())
        }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val message = bytes.toByteArray()
            h.post { if (my == gen) handle(webSocket, message) }
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            h.post { if (my == gen) { Log.w(TAG, "remote: socket failed: $t"); lost(); retry() } }
        }
        // The server closing (restarting): answer it, so onClosed follows now. Unanswered, the socket counted as
        // online until the next ping failed, up to ~50 s of messages into nothing (audit, 1 Nov 2026).
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            h.post { if (my == gen) { Log.i(TAG, "remote: socket closed $code"); lost(); retry() } }
        }
    }

    private fun lost() {
        ws = null
        tunnel?.reset()
        setOnline(false); setPresent(false)
    }

    private fun handle(w: WebSocket, message: ByteArray) {
        val frame = RelayFrame.parse(message) ?: return
        when (frame.type) {
            RelayFrames.JOINED -> { backoffMs = 2000; setOnline(true); Log.i(TAG, "remote: in the room") }
            RelayFrames.PEER -> closeTunnel(if (frame.flag() == 1) "the phone came again" else "the phone left the room")
            RelayFrames.DATA -> data(w, frame.body)
            RelayFrames.PUSH_RESULT -> Log.i(TAG, "push ${pushes.remove(frame.code()) ?: "?"}: result ${frame.pushResult()}")
            RelayFrames.KICKED -> { Log.w(TAG, "remote: another host socket took the room"); w.close(1000, "kicked") }
            RelayFrames.ERROR -> Log.w(TAG, "remote: server error ${frame.code()}")
        }
    }

    private fun data(w: WebSocket, body: ByteArray) {
        when (val step = tunnel?.receive(body) ?: return) {
            is Tunnel.Step.Send -> {
                setPresent(false)                    // HS1 replaced any tunnel that was open
                w.send(RelayFrames.data(SLOT, step.body).bytes().toByteString())
            }
            Tunnel.Step.Opened -> setPresent(true)
            is Tunnel.Step.Frames -> onFrames(step.bytes)
            is Tunnel.Step.Broken -> { Log.w(TAG, "remote: ${step.why}"); setPresent(false) }
        }
    }
}
