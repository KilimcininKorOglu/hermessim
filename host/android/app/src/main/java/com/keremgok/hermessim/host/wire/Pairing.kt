// HermesSIM host. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
package com.keremgok.hermessim.host.wire

import java.math.BigInteger
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * X25519 of RFC 7748. Written out because the phone system offers it only from Android 13. It runs once per
 * pairing attempt with a key made for that attempt, so its run time tells an observer nothing that lasts.
 */
object X25519 {
    private val P = BigInteger.TWO.pow(255) - BigInteger.valueOf(19)
    private val A24 = BigInteger.valueOf(121_665)
    private val BASE = ByteArray(32).also { it[0] = 9 }

    fun publicKey(secret: ByteArray): ByteArray = shared(secret, BASE)

    fun shared(secret: ByteArray, peerPublic: ByteArray): ByteArray {
        require(secret.size == 32 && peerPublic.size == 32) { "X25519 takes 32-byte values" }
        val k = scalar(secret)
        val u = little(peerPublic.copyOf().also { it[31] = (it[31].toInt() and 0x7F).toByte() })
        var a = Pair(BigInteger.ONE, BigInteger.ZERO)      // x2, z2
        var b = Pair(u, BigInteger.ONE)                    // x3, z3
        var swap = false
        for (t in 254 downTo 0) {
            val bit = k.testBit(t)
            if (swap != bit) a = b.also { b = a }
            swap = bit
            val next = step(u, a, b)
            a = next.first; b = next.second
        }
        if (swap) a = b
        return bytes(a.first * a.second.modPow(P - BigInteger.TWO, P) % P)
    }

    /** One step of the Montgomery ladder: doubles [a] and adds [b] to it. */
    private fun step(u: BigInteger, a: Pair<BigInteger, BigInteger>, b: Pair<BigInteger, BigInteger>): Pair<Pair<BigInteger, BigInteger>, Pair<BigInteger, BigInteger>> {
        val sumA = (a.first + a.second) % P
        val difA = (a.first - a.second).mod(P)
        val da = (b.first - b.second).mod(P) * sumA % P
        val cb = (b.first + b.second) % P * difA % P
        val aa = sumA * sumA % P
        val bb = difA * difA % P
        val e = (aa - bb).mod(P)
        val added = Pair((da + cb).modPow(BigInteger.TWO, P), u * (da - cb).mod(P).modPow(BigInteger.TWO, P) % P)
        val doubled = Pair(aa * bb % P, e * ((aa + A24 * e) % P) % P)
        return doubled to added
    }

    private fun scalar(secret: ByteArray): BigInteger {
        val k = secret.copyOf()
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = ((k[31].toInt() and 127) or 64).toByte()
        return little(k)
    }

    private fun little(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())

    private fun bytes(value: BigInteger): ByteArray {
        val big = value.toByteArray().reversedArray()
        return ByteArray(32) { if (it < big.size) big[it] else 0 }
    }
}

/** The key and the proof of SPEC.md "Pairing". */
object PairProof {
    private const val INFO = "hermessim pair v2"

    private fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        parts.forEach(mac::update)
        return mac.doFinal()
    }

    /** HKDF-SHA-256 with one block of output. */
    fun key(shared: ByteArray, salt: ByteArray): ByteArray = hmac(hmac(salt, shared), INFO.toByteArray(), byteArrayOf(1))

    fun proof(key: ByteArray, phoneKey: ByteArray, hostKey: ByteArray, code: String, installId: ByteArray): ByteArray =
        hmac(key, phoneKey, hostKey, code.toByteArray(Charsets.US_ASCII), installId)

    fun same(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}

/** What the host answers to a proof. */
enum class PairResult { PAIRED, WRONG, CLOSED }

/**
 * The host's side of pairing: the code it shows, one offer per attempt and three tries per code.
 * [random] gives fresh random bytes.
 */
class PairingDesk(private val random: (Int) -> ByteArray) {
    private class Attempt(val secret: ByteArray, val hostKey: ByteArray, val phoneKey: ByteArray, val salt: ByteArray)

    private var attempt: Attempt? = null

    /** The code on the host's screen, or null while the host takes no new phone. */
    var code: String? = null
        private set
    var triesLeft = 0
        private set

    fun open() { newCode() }

    fun close() { code = null; attempt = null; triesLeft = 0 }

    /** The host key and salt for a phone that sent [phoneKey], or null while pairing is closed. */
    fun offer(phoneKey: ByteArray): Pair<ByteArray, ByteArray>? {
        if (code == null || phoneKey.size != 32) return null
        val secret = random(32)
        val made = Attempt(secret, X25519.publicKey(secret), phoneKey, random(16))
        attempt = made
        return made.hostKey to made.salt
    }

    /** Checks a proof. A right one closes pairing; the third wrong one makes a new code. */
    fun check(proof: ByteArray, installId: ByteArray): PairResult {
        val shown = code
        val made = attempt
        if (shown == null || made == null) return PairResult.CLOSED
        attempt = null                                       // one proof per offer
        val key = PairProof.key(X25519.shared(made.secret, made.phoneKey), made.salt)
        if (PairProof.same(proof, PairProof.proof(key, made.phoneKey, made.hostKey, shown, installId))) {
            close()
            return PairResult.PAIRED
        }
        if (--triesLeft <= 0) newCode()
        return PairResult.WRONG
    }

    private fun newCode() {
        val n = random(4).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) } % 1_000_000
        code = "%06d".format(n)
        triesLeft = TRIES
        attempt = null
    }

    companion object {
        const val TRIES = 3
    }
}
