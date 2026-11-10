// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import com.keremgok.hermessim.protocol.v1.MacStatus
import com.keremgok.hermessim.protocol.v1.MacText
import com.keremgok.hermessim.protocol.v1.MacTexts

/** Whole seconds from [now] until [until] (elapsed-realtime ms), never negative: a pairing window's countdown. */
fun secondsLeft(until: Long, now: Long): Int = ((until - now) / 1000).coerceAtLeast(0).toInt()

/**
 * The rules of Mac access (protocol/README.md "Mac"), apart from Android so they are tested on their own: which Macs
 * may ask for the hotspot, the pairing window that admits a new one, and the state a Mac is shown.
 */
class MacCore(private val now: () -> Long) {
    data class Mac(val address: String, val name: String)

    enum class Access { ALLOW, ADMIT, DENY }

    companion object {
        const val MAX_MACS = 4
        const val WINDOW_MS = 120_000L

        /** One stored entry: "address|name" (an address never holds a '|'). */
        fun encode(m: Mac): String = "${m.address}|${m.name}"
        fun decode(s: String): Mac? = s.split('|', limit = 2).takeIf { it.size == 2 && it[0].isNotEmpty() }?.let { Mac(it[0], it[1]) }

        /** The state a Mac is shown: a start that failed stays FAILED until the hotspot comes on or is switched again. */
        fun state(on: Boolean, starting: Boolean, failed: Boolean): MacStatus.State = when {
            on -> MacStatus.State.ON
            starting -> MacStatus.State.STARTING
            failed -> MacStatus.State.FAILED
            else -> MacStatus.State.OFF
        }
    }

    var macs: List<Mac> = emptyList()
        private set
    private var windowUntil = 0L
    private val readers = MacPermission(::isMac)
    private val controllers = MacPermission(::isMac)

    fun load(stored: Collection<String>) { macs = stored.mapNotNull(::decode).distinctBy { it.address }.take(MAX_MACS) }
    fun stored(): Set<String> = macs.map(::encode).toSet()

    /** The Macs that may read the texts (PairedMac.texts). */
    fun loadTexts(stored: Collection<String>) = readers.load(stored)
    fun storedTexts(): Set<String> = readers.granted
    fun texts(address: String): Boolean = address in readers.granted
    fun setTexts(address: String, on: Boolean): Boolean = readers.set(address, on)

    /** The Macs that may change host settings (PairedMac.control). */
    fun loadControl(stored: Collection<String>) = controllers.load(stored)
    fun storedControl(): Set<String> = controllers.granted
    fun control(address: String): Boolean = address in controllers.granted
    fun setControl(address: String, on: Boolean): Boolean = controllers.set(address, on)

    fun windowOpen(): Boolean = now() < windowUntil
    fun secondsLeft(): Int = secondsLeft(windowUntil, now())
    fun openWindow() { windowUntil = now() + WINDOW_MS }
    fun closeWindow() { windowUntil = 0 }

    fun isMac(address: String): Boolean = macs.any { it.address == address }

    /**
     * A central reading or writing the Mac service (only over an encrypted link, so it is bonded): a listed Mac is
     * served; inside the window a new one is admitted while there is room; anyone else is refused.
     */
    fun access(address: String): Access = when {
        isMac(address) -> Access.ALLOW
        windowOpen() && macs.size < MAX_MACS -> Access.ADMIT
        else -> Access.DENY
    }

    /** Puts [mac] on the list and closes the window: one pairing per window. */
    fun admit(mac: Mac) {
        if (!isMac(mac.address) && macs.size < MAX_MACS) macs = macs + mac
        closeWindow()
    }

    /** True if [address] was on the list. */
    fun remove(address: String): Boolean {
        val before = macs.size
        macs = macs.filterNot { it.address == address }
        readers.drop(address)
        controllers.drop(address)
        return macs.size != before
    }
}

/** A per-Mac permission of the [listed] Macs, stored apart from the list so older lists stay readable. */
class MacPermission(private val listed: (String) -> Boolean) {
    var granted: Set<String> = emptySet()
        private set

    fun load(stored: Collection<String>) { granted = stored.filter(listed).toSet() }

    /** True if the setting changed; only a listed Mac can be allowed. */
    fun set(address: String, on: Boolean): Boolean {
        val next = if (on && listed(address)) granted + address else granted - address
        return (next != granted).also { granted = next }
    }

    fun drop(address: String) { granted = granted - address }
}

/**
 * The texts a Mac may read (protocol/README.md "Mac", MacTexts): those of the last 10 minutes, newest first, at most
 * 5, and the serialised value at most 512 bytes (a GATT attribute's limit). The oldest drop first; a lone text too long
 * keeps the start of its body.
 */
class MacTextShelf(private val now: () -> Long) {
    companion object {
        const val WINDOW_MS = 10 * 60_000L
        const val MAX_TEXTS = 5
        const val MAX_BYTES = 512
    }

    private var texts: List<MacText> = emptyList()
    /** Where each text on the shelf came from, for a Mac's reply: the sender as the network gave it, and the line. */
    private val origins = HashMap<String, Pair<String, Int>>()

    fun add(text: MacText, from: String = text.sender, slot: Int = -1) {
        texts = (listOf(text) + texts.filterNot { it.id == text.id }).sortedByDescending { it.atMs }.take(MAX_TEXTS)
        origins[text.id] = from to slot
        origins.keys.retainAll(texts.map { it.id }.toSet())
    }

    /** The sender and line of text [id] while it is still shown (on the shelf, younger than [WINDOW_MS]), else null. */
    fun origin(id: String): Pair<String, Int>? =
        origins[id]?.takeIf { texts.any { it.id == id && now() - it.atMs < WINDOW_MS } }

    fun value(): MacTexts {
        val recent = texts.filter { now() - it.atMs < WINDOW_MS }
        for (n in recent.size downTo 1) {
            val value = MacTexts.newBuilder().addAllTexts(recent.take(n)).build()
            if (value.serializedSize <= MAX_BYTES) return value
        }
        return recent.firstOrNull()?.let { MacTexts.newBuilder().addTexts(cut(it)).build() } ?: MacTexts.getDefaultInstance()
    }

    /** [text] with its body shortened until it fits alone. */
    private fun cut(text: MacText): MacText {
        var body = text.body
        while (body.isNotEmpty()) {
            body = body.dropLast(maxOf(1, (body.length / 8)))
            val fitted = text.toBuilder().setBody(body).build()
            if (MacTexts.newBuilder().addTexts(fitted).build().serializedSize <= MAX_BYTES) return fitted
        }
        return text.toBuilder().setBody("").build()
    }
}
