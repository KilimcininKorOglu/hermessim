// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import com.keremgok.hermessim.wire.PeerGrants
import com.keremgok.hermessim.wire.TextIn

/** [bytes] as lowercase hex: install ids and client refs as the host stores them. */
fun hexOf(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

/** Whole seconds from [now] until [until] (elapsed-realtime ms), never negative: a pairing window's countdown. */
fun secondsLeft(until: Long, now: Long): Int = ((until - now) / 1000).coerceAtLeast(0).toInt()

/**
 * The Mac list (protocol/v2/SPEC.md "Macs"), apart from Android so it is tested on its own: the paired Macs by
 * install id, their grants, and the window in which the host takes a new one.
 */
class MacCore(private val now: () -> Long) {
    /** [id]: the Mac's install id in hex; [address]: its Bluetooth address when it last said Hello. */
    data class Mac(val id: String, val address: String, val name: String)

    companion object {
        const val MAX_MACS = 4
        const val WINDOW_MS = 120_000L
        private const val ID_HEX = 32

        /** One stored entry: "id|address|name" (an id and an address never hold a '|'). */
        fun encode(m: Mac): String = "${m.id}|${m.address}|${m.name}"
        fun decode(s: String): Mac? = s.split('|', limit = 3).takeIf { it.size == 3 && validId(it[0]) }?.let { Mac(it[0], it[1], it[2]) }

        fun validId(id: String): Boolean = id.length == ID_HEX && id.all { it in '0'..'9' || it in 'a'..'f' }

        /** A stored id as the 16 bytes of `Peer.id`. */
        fun idBytes(id: String): ByteArray = ByteArray(id.length / 2) { id.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** Read from binder threads too (BleLink asks whether a link is a Mac's): replaced whole, never changed in place. */
    @Volatile var macs: List<Mac> = emptyList()
        private set
    private var windowUntil = 0L
    private val readers = MacPermission(::isMac)
    private val controllers = MacPermission(::isMac)

    fun load(stored: Collection<String>) { macs = stored.mapNotNull(::decode).distinctBy { it.id }.take(MAX_MACS) }
    fun stored(): Set<String> = macs.map(::encode).toSet()

    /** The Macs that may get the texts and answer them (PeerGrants.texts). */
    fun loadTexts(stored: Collection<String>) = readers.load(stored)
    fun storedTexts(): Set<String> = readers.granted
    fun texts(id: String): Boolean = id in readers.granted
    fun setTexts(id: String, on: Boolean): Boolean = readers.set(id, on)

    /** The Macs that may change host settings (PeerGrants.control). */
    fun loadControl(stored: Collection<String>) = controllers.load(stored)
    fun storedControl(): Set<String> = controllers.granted
    fun control(id: String): Boolean = id in controllers.granted
    fun setControl(id: String, on: Boolean): Boolean = controllers.set(id, on)

    fun grants(id: String): PeerGrants = PeerGrants.newBuilder().setTexts(texts(id)).setControl(control(id)).build()

    fun windowOpen(): Boolean = now() < windowUntil
    fun secondsLeft(): Int = secondsLeft(windowUntil, now())
    fun openWindow() { windowUntil = now() + WINDOW_MS }
    fun closeWindow() { windowUntil = 0 }

    fun isMac(id: String): Boolean = macs.any { it.id == id }
    fun byAddress(address: String): Mac? = macs.firstOrNull { it.address == address }

    /** A new Mac paired: it goes on the list while there is room, and the window closes (one pairing per window). */
    fun add(mac: Mac): Boolean {
        closeWindow()
        if (isMac(mac.id) || macs.size >= MAX_MACS) return false
        macs = macs + mac
        return true
    }

    /** A listed Mac said Hello from [address] as [name]: true when either changed (it is stored again). */
    fun seen(id: String, address: String, name: String): Boolean {
        val known = macs.firstOrNull { it.id == id } ?: return false
        if (known.address == address && known.name == name) return false
        macs = macs.map { if (it.id == id) Mac(id, address, name) else it }
        return true
    }

    /** The removed Mac, or null when [id] was not listed. */
    fun remove(id: String): Mac? {
        val gone = macs.firstOrNull { it.id == id } ?: return null
        macs = macs - gone
        readers.drop(id)
        controllers.drop(id)
        return gone
    }
}

/** A per-Mac permission of the [listed] Macs, stored apart from the list so older lists stay readable. */
class MacPermission(private val listed: (String) -> Boolean) {
    var granted: Set<String> = emptySet()
        private set

    fun load(stored: Collection<String>) { granted = stored.filter(listed).toSet() }

    /** True if the setting changed; only a listed Mac can be allowed. */
    fun set(id: String, on: Boolean): Boolean {
        val next = if (on && listed(id)) granted + id else granted - id
        return (next != granted).also { granted = next }
    }

    fun drop(id: String) { granted = granted - id }
}

/**
 * The texts a Mac with the texts grant gets (SPEC.md "Macs"): those of the last 10 minutes, at most the 5 newest,
 * with their seq. A Mac may answer only one of them, to its sender and on its line.
 */
class MacTextShelf(private val now: () -> Long) {
    data class Shelved(val seq: Long, val text: TextIn)

    companion object {
        const val WINDOW_MS = 10 * 60_000L
        const val MAX_TEXTS = 5
    }

    private var texts: List<Shelved> = emptyList()

    fun add(seq: Long, text: TextIn) {
        texts = (texts.filterNot { it.seq == seq } + Shelved(seq, text)).sortedBy { it.text.atMs }.takeLast(MAX_TEXTS)
    }

    /** The texts younger than [WINDOW_MS], oldest first. */
    fun recent(): List<Shelved> = texts.filter { now() - it.text.atMs < WINDOW_MS }

    /** True when a recent text came from [to] on [line] (null: a text without a line). */
    fun allows(to: String, line: Int?): Boolean = recent().any { it.text.from == to && lineOf(it.text) == line }

    private fun lineOf(t: TextIn): Int? = if (t.hasLine()) t.line else null
}
