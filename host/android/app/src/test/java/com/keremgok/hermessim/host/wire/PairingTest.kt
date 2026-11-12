package com.keremgok.hermessim.host.wire

import com.keremgok.hermessim.host.wire.Vectors.hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingTest {
    private val case = Vectors.cases("pairing").single()
    private fun bytes(key: String) = hex(case.getString(key))

    @Test
    fun theVectorGivesItsKeysItsSharedSecretAndItsProof() {
        assertEquals(case.getString("phone_key"), X25519.publicKey(bytes("phone_secret")).hex())
        assertEquals(case.getString("host_key"), X25519.publicKey(bytes("host_secret")).hex())
        assertEquals(case.getString("shared"), X25519.shared(bytes("host_secret"), bytes("phone_key")).hex())
        val key = PairProof.key(bytes("shared"), bytes("salt"))
        assertEquals(case.getString("key"), key.hex())
        assertEquals(case.getString("proof"),
            PairProof.proof(key, bytes("phone_key"), bytes("host_key"), case.getString("code"), bytes("install_id")).hex())
    }

    /** RFC 7748 section 5.2, first vector. */
    @Test
    fun x25519MatchesTheStandard() {
        val got = X25519.shared(hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"),
            hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"))
        assertEquals("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552", got.hex())
    }

    /** A phone that knows the code: it does what SPEC.md "Pairing" tells the phone to do. */
    private fun phoneProof(desk: PairingDesk, code: String, installId: ByteArray): ByteArray {
        val secret = ByteArray(32) { (it + 1).toByte() }
        val phoneKey = X25519.publicKey(secret)
        val (hostKey, salt) = desk.offer(phoneKey)!!
        val key = PairProof.key(X25519.shared(secret, hostKey), salt)
        return PairProof.proof(key, phoneKey, hostKey, code, installId)
    }

    private fun desk(): PairingDesk {
        var n = 0
        return PairingDesk { size -> ByteArray(size) { (++n * 37).toByte() } }.apply { open() }
    }

    @Test
    fun thePhoneThatKnowsTheCodePairsAndPairingThenCloses() {
        val desk = desk()
        val id = ByteArray(16) { 5 }
        assertEquals(PairResult.PAIRED, desk.check(phoneProof(desk, desk.code!!, id), id))
        assertNull(desk.code)
        assertNull(desk.offer(ByteArray(32)))
    }

    @Test
    fun aWrongCodeCostsATryAndTheThirdOneChangesTheCode() {
        val desk = desk()
        val id = ByteArray(16) { 5 }
        val first = desk.code!!
        val wrong = if (first == "000000") "000001" else "000000"
        assertEquals(listOf(PairResult.WRONG, PairResult.WRONG), List(2) { desk.check(phoneProof(desk, wrong, id), id) })
        assertEquals(first to 1, desk.code to desk.triesLeft)
        assertEquals(PairResult.WRONG, desk.check(phoneProof(desk, wrong, id), id))
        assertNotEquals(first, desk.code)
        assertEquals(PairingDesk.TRIES, desk.triesLeft)
    }

    @Test
    fun aProofForAnotherInstallOrASecondProofForOneOfferIsRefused() {
        val desk = desk()
        val proof = phoneProof(desk, desk.code!!, ByteArray(16) { 5 })
        assertEquals(PairResult.WRONG, desk.check(proof, ByteArray(16) { 6 }))
        assertEquals(PairResult.CLOSED, desk.check(proof, ByteArray(16) { 5 }))
    }
}
