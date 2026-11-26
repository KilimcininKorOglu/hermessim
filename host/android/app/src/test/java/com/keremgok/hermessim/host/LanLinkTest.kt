// HermesSIM host. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import com.keremgok.hermessim.host.wire.LanFrames
import com.keremgok.hermessim.host.wire.Tunnel
import com.keremgok.hermessim.host.wire.X25519
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The host's local network server on loopback, against a peer written from protocol/README.md "Local network" and
 * "Tunnel": KEY frame, HS1-HS3 under the key's `lan-auth`, then SEAL frames both ways.
 */
class LanLinkTest {
    private val phoneKey = ByteArray(32) { (it + 1).toByte() }
    private val events = LinkedBlockingQueue<String>()
    private val frames = LinkedBlockingQueue<ByteArray>()
    private val conns = LinkedBlockingQueue<LanLink.Conn>()
    private var port = 0

    @Before fun up() {
        LanLink.log = {}
        LanLink.find = { id -> if (id.contentEquals(LanFrames.keyId(phoneKey))) "phone" to phoneKey else null }
        port = LanLink.listen(object : LanLink.Listener {
            override fun opened(conn: LanLink.Conn) { events.add("opened ${conn.peer}"); conns.add(conn) }
            override fun frames(conn: LanLink.Conn, bytes: ByteArray) { frames.add(bytes) }
            override fun closed(conn: LanLink.Conn) { events.add("closed") }
        })!!
    }

    @After fun down() = LanLink.stop()

    @Test fun `the server listens on the kept port, and on another one when that is taken`() {
        LanLink.stop()
        val free = java.net.ServerSocket(0).use { it.localPort }
        assertEquals(free, LanLink.listen(object : LanLink.Listener {
            override fun opened(conn: LanLink.Conn) = Unit
            override fun frames(conn: LanLink.Conn, bytes: ByteArray) = Unit
            override fun closed(conn: LanLink.Conn) = Unit
        }, free))
        assertEquals(free, LanLink.port)
        LanLink.stop()
        java.net.ServerSocket(free).use { taken ->
            val other = LanLink.listen(object : LanLink.Listener {
                override fun opened(conn: LanLink.Conn) = Unit
                override fun frames(conn: LanLink.Conn, bytes: ByteArray) = Unit
                override fun closed(conn: LanLink.Conn) = Unit
            }, taken.localPort)!!
            assertNotEquals(taken.localPort, other)
            assertEquals(other, LanLink.port)
        }
    }

    /** The peer's side of one connection. */
    private class Peer(port: Int) {
        val socket = Socket("127.0.0.1", port).apply { soTimeout = 5000 }
        private val input: InputStream = socket.getInputStream()

        fun send(body: ByteArray) = socket.getOutputStream().run { write(LanFrames.frame(body)); flush() }

        fun read(): ByteArray {
            val length = ByteBuffer.wrap(input.readNBytes(4)).int
            return input.readNBytes(length)
        }

        /** True when the host closed the connection; a host that keeps it open past the timeout did not. */
        fun closedByHost(): Boolean = try {
            input.read() == -1
        } catch (e: java.net.SocketTimeoutException) {
            false
        } catch (e: java.io.IOException) {
            true                                            // reset by the host
        }
    }

    private fun hmac(key: ByteArray, vararg parts: ByteArray) = Tunnel.hmac(key, *parts)

    private fun chacha(mode: Int, key: ByteArray, counter: Long) = Cipher.getInstance("ChaCha20-Poly1305").apply {
        init(mode, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(ByteBuffer.allocate(12).putInt(0).putLong(counter).array()))
    }

    /** Runs KEY and HS1-HS3 as the phone; returns the (phone-to-host, host-to-phone) keys. */
    private fun handshake(peer: Peer, key: ByteArray): Pair<ByteArray, ByteArray> {
        val auth = LanFrames.auth(key)
        peer.send(byteArrayOf(LanFrames.KEY.toByte()) + LanFrames.keyId(key))
        val secret = ByteArray(32) { (it * 7 + 3).toByte() }
        val eP = X25519.publicKey(secret)
        peer.send(byteArrayOf(Tunnel.HS1.toByte()) + eP)
        val hs2 = peer.read()
        assertEquals(Tunnel.HS2, hs2[0].toInt())
        val eH = hs2.copyOfRange(1, 33)
        assertTrue("HS2 proves the host holds the key", MessageDigest.isEqual(hmac(auth, "H".toByteArray(), eP, eH), hs2.copyOfRange(33, 65)))
        peer.send(byteArrayOf(Tunnel.HS3.toByte()) + hmac(auth, "P".toByteArray(), eP, eH))
        val prk = hmac(auth, X25519.shared(secret, eH))
        return hmac(prk, "p2h".toByteArray() + 1) to hmac(prk, "h2p".toByteArray() + 1)
    }

    @Test fun `a peer with its key opens the tunnel and frames travel sealed both ways`() {
        val peer = Peer(port)
        val (p2h, h2p) = handshake(peer, phoneKey)
        assertEquals("opened phone", events.poll(5, TimeUnit.SECONDS))

        val hello = "frames to the host".toByteArray()
        val sealed = chacha(Cipher.ENCRYPT_MODE, p2h, 0).doFinal(hello)
        peer.send(byteArrayOf(Tunnel.SEAL.toByte()) + ByteBuffer.allocate(8).putLong(0).array() + sealed)
        assertArrayEquals(hello, frames.poll(5, TimeUnit.SECONDS))

        val conn = conns.poll(5, TimeUnit.SECONDS)!!
        assertTrue(conn.send("frames to the phone".toByteArray()))
        val body = peer.read()
        assertEquals(Tunnel.SEAL, body[0].toInt())
        assertEquals(0L, ByteBuffer.wrap(body, 1, 8).long)
        assertArrayEquals("frames to the phone".toByteArray(), chacha(Cipher.DECRYPT_MODE, h2p, 0).doFinal(body, 9, body.size - 9))
        peer.socket.close()
        assertEquals("closed", events.poll(5, TimeUnit.SECONDS))
    }

    /** A CREDIT timer can fire after the phone closed the link; sending then must say no, not crash the app. */
    @Test fun `a send on a closed connection is refused without throwing`() {
        val peer = Peer(port)
        handshake(peer, phoneKey)
        assertEquals("opened phone", events.poll(5, TimeUnit.SECONDS))
        val conn = conns.poll(5, TimeUnit.SECONDS)!!
        conn.close()
        assertEquals(false, conn.send("credit after close".toByteArray()))
        peer.socket.close()
    }

    @Test fun `a key the host never made is closed before any handshake`() {
        val peer = Peer(port)
        peer.send(byteArrayOf(LanFrames.KEY.toByte()) + LanFrames.keyId(ByteArray(32) { 9 }))
        assertTrue(peer.closedByHost())
        assertNull(events.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test fun `a peer that knows the key id but not the key cannot prove it`() {
        val peer = Peer(port)
        // Right id, wrong key: the HS3 MAC is made under another auth
        peer.send(byteArrayOf(LanFrames.KEY.toByte()) + LanFrames.keyId(phoneKey))
        val eP = X25519.publicKey(ByteArray(32) { 5 })
        peer.send(byteArrayOf(Tunnel.HS1.toByte()) + eP)
        val eH = peer.read().copyOfRange(1, 33)
        peer.send(byteArrayOf(Tunnel.HS3.toByte()) + hmac(LanFrames.auth(ByteArray(32) { 7 }), "P".toByteArray(), eP, eH))
        assertTrue(peer.closedByHost())
        assertNull(events.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test fun `a replayed sealed frame ends the connection`() {
        val peer = Peer(port)
        val (p2h, _) = handshake(peer, phoneKey)
        assertNotNull(events.poll(5, TimeUnit.SECONDS))
        val seal = byteArrayOf(Tunnel.SEAL.toByte()) + ByteBuffer.allocate(8).putLong(0).array() +
            chacha(Cipher.ENCRYPT_MODE, p2h, 0).doFinal(byteArrayOf(1))
        peer.send(seal)
        assertNotNull(frames.poll(5, TimeUnit.SECONDS))
        peer.send(seal)
        assertTrue(peer.closedByHost())
        assertEquals("closed", events.poll(5, TimeUnit.SECONDS))
    }

    @Test fun `a frame over the limit ends the connection`() {
        val peer = Peer(port)
        peer.socket.getOutputStream().run { write(ByteBuffer.allocate(4).putInt(LanFrames.MAX_BODY + 1).array()); flush() }
        assertTrue(peer.closedByHost())
    }
}
