package com.keremgok.hermessim.host.wire

import com.keremgok.hermessim.host.wire.Vectors.hex
import com.keremgok.hermessim.host.wire.Vectors.list
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The call audio packet, stream, keys and datagrams against protocol/v2/vectors/audio.json. */
class AudioPacketTest {
    private val audio = Vectors.cases("audio").single()

    @Test fun eachStreamGivesItsPacketsItsSkippedBytesAndItsRest() = audio.list("stream").forEach { case ->
        val bytes = hex(case.getString("bytes"))
        val name = case.getString("name")
        // Whole, then one byte at a time: a write may cut the stream anywhere.
        for (cut in listOf(bytes.size, 1)) {
            val packets = mutableListOf<String>()
            val stream = AudioStream { b, off, len -> packets += b.copyOfRange(off, off + len).hex() }
            bytes.indices.step(cut).forEach { stream.feed(bytes, it, minOf(cut, bytes.size - it)) }
            assertEquals(name, strings(case, "packets"), packets)
            assertEquals(name, case.getLong("skipped"), stream.skipped)
            assertEquals(name, case.getString("rest"), stream.rest().hex())
        }
    }

    @Test fun aPacketIsFramedWithItsMarkAndLength() {
        val packets = audio.list("stream").first().let { strings(it, "packets") }.map(::hex)
        val out = ByteArray(1024)
        var n = 0
        packets.forEach { n += AudioPacket.frame(it, it.size, out, n) }
        assertEquals(audio.list("stream").first().getString("bytes"), out.copyOf(n).hex())
    }

    @Test fun thePathKeysAndEveryDatagram() {
        val keys = audio.list("keys").single()
        val phoneSecret = hex(keys.getString("phone_secret"))
        val hostSecret = hex(keys.getString("host_secret"))
        assertEquals(keys.getString("phone_key"), X25519.publicKey(phoneSecret).hex())
        assertEquals(keys.getString("host_key"), X25519.publicKey(hostSecret).hex())
        val shared = X25519.shared(hostSecret, hex(keys.getString("phone_key")))
        assertEquals(keys.getString("shared"), shared.hex())
        val derived = AudioKeys.derive(hex(keys.getString("audio_id")), shared)
        assertEquals(keys.getString("p2h"), derived.p2h.hex())
        assertEquals(keys.getString("h2p"), derived.h2p.hex())
        keys.list("datagrams").forEach { d ->
            val toHost = d.getString("key") == "p2h"
            val key = if (toHost) derived.p2h else derived.h2p
            val bytes = AudioSeal.seal(key, d.getInt("type"), d.getLong("counter"), hex(d.getString("plain")))
            assertEquals(d.getString("name"), d.getString("bytes"), bytes.hex())
            // The receiving side opens it once; a repeat is refused.
            val receiver = if (toHost) AudioSeal(derived.h2p, derived.p2h) else AudioSeal(derived.p2h, derived.h2p)
            val opened = receiver.open(bytes, 0, bytes.size)!!
            assertEquals(d.getInt("type"), opened.type)
            assertArrayEquals(hex(d.getString("plain")), opened.plain)
            assertNull(receiver.open(bytes, 0, bytes.size))
        }
    }

    @Test fun aDatagramUnderTheOtherDirectionsKeyDoesNotOpen() {
        val keys = audio.list("keys").single()
        val d = keys.list("datagrams").first()
        val bytes = hex(d.getString("bytes"))
        val p2h = hex(keys.getString("p2h"))
        val h2p = hex(keys.getString("h2p"))
        assertNull(AudioSeal(p2h, h2p).open(bytes, 0, bytes.size))            // the phone's own datagram reflected back
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        assertNull(AudioSeal(h2p, p2h).open(bytes, 0, bytes.size))
    }

    @Test fun theSenderCountsFromZeroAcrossAudioAndProbes() {
        val keys = audio.list("keys").single()
        val sender = AudioSeal(hex(keys.getString("h2p")), hex(keys.getString("p2h")))
        val receiver = AudioSeal(hex(keys.getString("p2h")), hex(keys.getString("h2p")))
        val counters = listOf(AudioPacket.PROBE, AudioPacket.AUDIO, AudioPacket.PROBE).map { type ->
            val d = sender.seal(type, AudioPacket.probe(AudioPacket.PROBE_ASK, 9))!!
            receiver.open(d, 0, d.size)!!.counter
        }
        assertEquals(listOf(0L, 1L, 2L), counters)
    }

    @Test fun theCounterWindowFollowsThePushWindow() = Vectors.cases("push").single().list("window").forEach { case ->
        val window = CounterWindow()
        val counters = case.getJSONArray("counters").let { a -> List(a.length()) { a.getLong(it) } }
        val accepted = case.getJSONArray("accepted").let { a -> List(a.length()) { a.getBoolean(it) } }
        assertEquals(case.getString("name"), accepted, counters.map(window::take))
    }

    private fun strings(o: JSONObject, key: String): List<String> = o.getJSONArray(key).let { a -> List(a.length(), a::getString) }
}
