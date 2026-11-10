// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

/** A long write arrives in parts; only the parts of one value, in order and within the limit, make it whole. */
class PreparedWritesTest {
    private val reply = UUID.fromString("0e9c0017-0971-48af-ba0b-2c5c254faec0")
    private val other = UUID.fromString("0e9c0013-0971-48af-ba0b-2c5c254faec0")

    @Test fun partsInOrderMakeTheValueAtTheExecute() {
        val w = PreparedWrites(max = 8)
        val added = listOf(w.add("mac", reply, 0, byteArrayOf(1, 2, 3)), w.add("mac", reply, 3, byteArrayOf(4, 5)))
        assertEquals(listOf(true, true), added)
        val (uuid, value) = w.execute("mac", run = true)!!
        assertEquals(reply, uuid)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), value)
        assertEquals(null, w.execute("mac", run = true))
    }

    @Test fun aGapAnotherCharacteristicATooLongValueOrACancelLeaveNothing() {
        val w = PreparedWrites(max = 8)
        val refused = listOf(
            w.add("a", reply, 0, byteArrayOf(1)) && !w.add("a", reply, 2, byteArrayOf(2)),
            w.add("b", reply, 0, byteArrayOf(1)) && !w.add("b", other, 1, byteArrayOf(2)),
            w.add("c", reply, 0, ByteArray(5)) && !w.add("c", reply, 5, ByteArray(4)),
        )
        assertEquals(listOf(true, true, true), refused)
        assertEquals(listOf(null, null, null), listOf("a", "b", "c").map { w.execute(it, run = true) })
        w.add("d", reply, 0, byteArrayOf(1))
        assertEquals(null, w.execute("d", run = false))
    }
}
