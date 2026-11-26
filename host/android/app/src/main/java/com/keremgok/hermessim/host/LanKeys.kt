// HermesSIM host. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.content.Context
import com.keremgok.hermessim.host.wire.LanFrames
import java.security.SecureRandom

/**
 * Each peer's key of the local network path, by its install id (protocol/README.md "Local network"). Pure: the
 * stored form is a map of install id to key, both in hex.
 */
class LanKeyBook(private val keys: MutableMap<String, ByteArray>, private val random: (Int) -> ByteArray) {
    /** The peer's key, made the first time it is asked for. */
    fun keyFor(peer: String): ByteArray = keys.getOrPut(peer) { random(KEY_BYTES) }

    /** The peer whose key a KEY frame names, or null for none of ours. */
    fun peerOf(keyId: ByteArray): String? = keys.entries.firstOrNull { LanFrames.keyId(it.value).contentEquals(keyId) }?.key

    fun forget(peer: String) { keys.remove(peer) }

    fun all(): Map<String, ByteArray> = keys.toMap()

    companion object {
        const val KEY_BYTES = 32
        const val SERVICE_HEX = 16
        const val PORT_LOW = 40_000
        const val PORT_COUNT = 20_000

        /** The kept port of the link: 40000 to 59999, from two random bytes. */
        fun pickPort(random: ByteArray): Int = PORT_LOW + (((random[0].toInt() and 0xFF) shl 8) or (random[1].toInt() and 0xFF)) % PORT_COUNT
    }
}

/**
 * [LanKeyBook] kept in the host's preferences, with the mDNS instance name it announces under. Main thread and the
 * connection threads: every use holds the object's lock.
 */
object LanKeys {
    private val rng = SecureRandom()
    private lateinit var app: Context
    private lateinit var book: LanKeyBook

    private fun prefs() = app.getSharedPreferences("lan", Context.MODE_PRIVATE)

    @Synchronized fun init(ctx: Context) {
        app = ctx.applicationContext
        val stored = prefs().all.filterKeys { it.startsWith(PEER) }.mapNotNull { (k, v) ->
            (v as? String)?.let { k.removePrefix(PEER) to RemoteCrypto.unhex(it) }
        }.toMap(HashMap())
        book = LanKeyBook(stored) { n -> ByteArray(n).also(rng::nextBytes) }
    }

    /** 16 random hex characters, made once: names nothing about the host or its owner. */
    /** The port the link listens on, made once and kept. */
    @Synchronized fun port(): Int = prefs().getInt(PORT, 0).takeIf { it > 0 }
        ?: LanKeyBook.pickPort(ByteArray(2).also(rng::nextBytes)).also { prefs().edit().putInt(PORT, it).apply() }

    @Synchronized fun service(): String = prefs().getString(SERVICE, null)
        ?: RemoteCrypto.hex(ByteArray(LanKeyBook.SERVICE_HEX / 2).also(rng::nextBytes)).also { prefs().edit().putString(SERVICE, it).apply() }

    @Synchronized fun keyFor(peer: String): ByteArray {
        val known = book.all().containsKey(peer)
        val key = book.keyFor(peer)
        if (!known) prefs().edit().putString(PEER + peer, RemoteCrypto.hex(key)).apply()
        return key
    }

    /** The peer and its key for a KEY frame's key id, or null. */
    @Synchronized fun find(keyId: ByteArray): Pair<String, ByteArray>? = book.peerOf(keyId)?.let { it to book.all().getValue(it) }

    @Synchronized fun forget(peer: String) {
        book.forget(peer)
        prefs().edit().remove(PEER + peer).apply()
    }

    private const val PEER = "key."
    private const val SERVICE = "service"
    private const val PORT = "port"
}
