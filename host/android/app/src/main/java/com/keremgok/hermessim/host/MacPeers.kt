// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import com.google.protobuf.ByteString
import com.keremgok.hermessim.host.wire.PairingDesk
import com.keremgok.hermessim.wire.Peer
import com.keremgok.hermessim.wire.PeerGrants
import com.keremgok.hermessim.wire.Peers
import com.keremgok.hermessim.wire.TextIn

/**
 * The Macs (protocol/v2/SPEC.md "Macs"): the list and grants, the window and code that take a new Mac, the texts a
 * Mac gets, and the second advertising set ([MacAdvertiser]) that lets a Mac find the host while the phone is
 * connected. A Mac's session runs in Relay like the phone's. Main thread, except [isMac].
 */
class MacPeers(
    ctx: Context,
    private val main: Handler,
    bt: BluetoothManager,
    /** The Peers group changed. */
    private val changed: () -> Unit,
    /** The install ids of the Macs served now. */
    private val connected: () -> Set<String>,
) {
    private companion object { const val TAG = "HermesSimHost" }

    private val prefs = ctx.getSharedPreferences("mac", Context.MODE_PRIVATE)
    private val core = MacCore(SystemClock::elapsedRealtime)
    private val shelf = MacTextShelf(System::currentTimeMillis)
    val desk = PairingDesk { n -> ByteArray(n).also(java.security.SecureRandom()::nextBytes) }
    @Volatile private var radio = false
    private val advertiser = MacAdvertiser(main, bt,
        wanted = { radio && (core.macs.isNotEmpty() || core.windowOpen()) }, pairing = core::windowOpen, refused = { changed() })

    /** A Mac was just put on the list (BleLink: it may have come in through the phone's set, which it switched off). */
    var admitted: (() -> Unit)? = null

    init {
        // The list of the old Mac service was kept by Bluetooth address: those Macs pair again.
        prefs.edit().remove("macs").remove("texts").remove("control").apply()
        core.load(prefs.getStringSet("peers", emptySet()).orEmpty())
        core.loadTexts(prefs.getStringSet("peer_texts", emptySet()).orEmpty())
        core.loadControl(prefs.getStringSet("peer_control", emptySet()).orEmpty())
    }

    /** A link from [address] is a listed Mac's. Any thread. */
    fun isMac(address: String): Boolean = core.byAddress(address) != null

    fun known(id: String): Boolean = core.isMac(id)
    fun grants(id: String?): PeerGrants = if (id == null) PeerGrants.getDefaultInstance() else core.grants(id)
    fun windowOpen(): Boolean = core.windowOpen() && desk.code != null

    /** The phone's `Peers` group: every Mac, and the code while the window is open. */
    fun peers(): Peers {
        val now = connected()
        val b = Peers.newBuilder().setAvailable(advertiser.supported()).setWindowOpen(windowOpen())
            .addAllPeers(core.macs.map { peer(it, it.id in now) })
        if (windowOpen()) b.code = desk.code
        return b.build()
    }

    /** The `Peers` group as the Mac [id] sees it: its own entry only. */
    fun own(id: String?): ByteArray {
        val mac = core.macs.firstOrNull { it.id == id }
        val b = Peers.newBuilder().setAvailable(advertiser.supported())
        if (mac != null) b.addPeers(peer(mac, connected = true))
        return b.build().toByteArray()
    }

    private fun peer(m: MacCore.Mac, connected: Boolean): Peer = Peer.newBuilder()
        .setId(ByteString.copyFrom(MacCore.idBytes(m.id))).setName(m.name).setGrants(core.grants(m.id)).setConnected(connected).build()

    /** A `State` as the Mac [id] sees it (MacView). */
    fun view(state: ByteArray, id: String?): ByteArray = MacView.state(state, MacRules.groups(grants(id)), own(id))

    // ---------------------------------------------------------------- the window and pairing

    /** OpenPeerWindow: a fresh code for [MacCore.WINDOW_MS], or the window closed. */
    fun setWindow(open: Boolean) {
        if (open) { core.openWindow(); desk.open() } else { core.closeWindow(); desk.close() }
        Log.i(TAG, "Mac pairing window ${if (open) "open for ${MacCore.WINDOW_MS / 1000} s" else "closed"}")
        advertiser.refresh()
        changed()
        if (open) main.postDelayed(::closeWhenDue, MacCore.WINDOW_MS + 500)
    }

    private fun closeWhenDue() {
        if (core.windowOpen()) return
        desk.close()
        advertiser.refresh()
        changed()
    }

    /** A wrong proof: the third one made a new code, which the phone shows. */
    fun wrongProof() = changed()

    /** The proof was right: the Mac goes on the list (when there is room) and the window closes. */
    fun paired(id: String, address: String, name: String) {
        desk.close()
        val added = core.add(MacCore.Mac(id, address, name))
        if (added) save()
        Log.i(TAG, if (added) "Mac paired: $name $address" else "Mac pairing: the list is full")
        advertiser.refresh()
        admitted?.invoke()
        changed()
    }

    /** A listed Mac said Hello: its address and name are kept as they are now. */
    fun seen(id: String, address: String, name: String) {
        if (core.seen(id, address, name)) save()
        changed()
    }

    /** SetPeer with grants: stored, and the Mac sees them in its Peers group. */
    fun setGrants(id: String, texts: Boolean, control: Boolean) {
        val textsChanged = core.setTexts(id, texts)
        if (!core.setControl(id, control) && !textsChanged) return
        save()
        Log.i(TAG, "Mac ${id.take(8)}: texts $texts, control $control")
        changed()
    }

    /** SetPeer with remove: the Mac that went, or null when it was not listed. */
    fun remove(id: String): MacCore.Mac? {
        val gone = core.remove(id) ?: return null
        save()
        Log.i(TAG, "Mac removed: ${gone.name} ${gone.address}")
        advertiser.refresh()
        changed()
        return gone
    }

    /** The host stops being a host: every Mac is forgotten. Their addresses, for the bonds. */
    fun clear(): List<String> = core.macs.mapNotNull { remove(it.id)?.address }

    private fun save() = with(prefs.edit()) {
        putStringSet("peers", core.stored())
        putStringSet("peer_texts", core.storedTexts())
        putStringSet("peer_control", core.storedControl())
        apply()
    }

    // ---------------------------------------------------------------- texts

    fun textArrived(seq: Long, text: TextIn) = shelf.add(seq, text)

    /** The recent texts above [cursor] for the Mac [id], oldest first; none without the texts grant. */
    fun texts(id: String?, cursor: Long = 0): List<MacTextShelf.Shelved> =
        if (id != null && core.texts(id)) shelf.recent().filter { it.seq > cursor } else emptyList()

    fun mayGetTexts(id: String?): Boolean = id != null && core.texts(id)

    /** A Mac may text [to] on [line]: a recent text came from there. */
    fun answers(to: String, line: Int?): Boolean = shelf.allows(to, line)

    // ---------------------------------------------------------------- the radio (BleLink)

    /** The GATT service is up: the Macs' set may advertise. */
    fun serviceReady() { radio = true; advertiser.kick() }

    /** Bluetooth is going off: the advertising set goes with it. */
    fun radioOff() = main.post { radio = false; advertiser.lost() }

    /** After any connection change: the advertising set follows. */
    fun kick(): Boolean = advertiser.kick()

    fun stop() = main.post { advertiser.stop() }
}
