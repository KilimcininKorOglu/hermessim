// HermesSIM host. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import com.keremgok.hermessim.host.wire.Vectors
import com.keremgok.hermessim.host.wire.Vectors.hex
import com.keremgok.hermessim.host.wire.Vectors.list
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The keys of a room key and a push key, the sealed push and its collapse id, against protocol/v2/vectors. */
class PushCryptoTest {
    @Test fun `a room key gives the vectors' keys`() {
        for (case in Vectors.cases("roomkey")) {
            val c = RemoteCrypto(Vectors.hex(case.getString("key")))
            assertEquals(case.getString("room_token"), c.roomToken.hex())
            assertEquals(case.getString("room_id"), c.roomId)
            assertEquals(case.getString("auth"), c.auth.hex())
        }
    }

    @Test fun `a push is sealed as the vector with its counter first`() {
        val push = Vectors.cases("push").single()
        for (case in push.list("seal")) {
            val c = RemoteCrypto(Vectors.hex(case.getString("push_key")))
            val sealed = c.sealPush(case.getLong("counter"), case.getLong("sent_ms"), Vectors.hex(case.getString("packet")),
                Vectors.hex(case.getString("nonce")))
            assertEquals(case.getString("sealed"), sealed.hex())
        }
    }

    @Test fun `the collapse id hides the kind and the line`() {
        val push = Vectors.cases("push").single()
        for (case in push.list("collapse")) {
            val id = RemoteCrypto(Vectors.hex(case.getString("push_key"))).collapseId(case.getString("id"))
            assertEquals(case.getString("collapse_id"), id)
            assertFalse(id.contains("text") || id.contains("notice") || id.contains("basic"))
        }
    }
}
