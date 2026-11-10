// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.content.Context
import android.os.Handler
import android.util.Log
import com.keremgok.hermessim.protocol.v1.HostActionResult
import com.keremgok.hermessim.protocol.v1.MacControl
import com.keremgok.hermessim.protocol.v1.SetSim

/**
 * A Mac's control write (protocol/README.md "Mac", Control): the mobile data line, a host action or a hotspot device
 * block. The answer is the write's ATT status: success, [IN_CALL] for a host action during a call, [FAILED] for
 * anything else refused. Pure.
 */
object MacControlCore {
    const val OK = 0
    const val FAILED = 0x101                 // BluetoothGatt.GATT_FAILURE
    const val IN_CALL = 0x80                 // application error: the host is in a call

    fun parse(value: ByteArray): MacControl? = runCatching { MacControl.parseFrom(value) }.getOrNull()

    /** The status for a control that may not run, or null when it may. [slots]: the lines that are on. */
    fun refusal(control: MacControl?, inCall: Boolean, slots: Set<Int>): Int? = when (control?.whatCase) {
        MacControl.WhatCase.DATA_SLOT -> if (control.dataSlot in slots) null else FAILED
        MacControl.WhatCase.HOST_ACTION -> when (HostActionCore.refusal(control.hostAction, inCall)) {
            null -> null
            HostActionResult.Failure.IN_CALL -> IN_CALL
            else -> FAILED
        }
        MacControl.WhatCase.BLOCK -> if (HotspotGuardCore.validMac(control.block.mac.lowercase())) null else FAILED
        else -> FAILED
    }
}

/** Runs a Mac's control write on the main thread and gives the write's status. */
object MacControlRunner {
    private const val TAG = "HermesSimHost"

    fun run(ctx: Context, main: Handler, address: String, value: ByteArray): Int {
        val control = MacControlCore.parse(value)
        val slots = Sims.active(ctx).map { it.simSlotIndex }.toSet()
        MacControlCore.refusal(control, CallRegistry.anyCall(), slots)?.let { code ->
            Log.w(TAG, "Mac $address: control refused (${control?.whatCase}, status $code)")
            return code
        }
        val c = control ?: return MacControlCore.FAILED
        Log.i(TAG, "Mac $address: control ${c.whatCase}")
        return when (c.whatCase) {
            MacControl.WhatCase.DATA_SLOT ->
                if (Sims.apply(ctx, SetSim.newBuilder().setSlot(c.dataSlot).setMakeDefaultData(true).build()) == null) MacControlCore.OK
                else MacControlCore.FAILED
            MacControl.WhatCase.HOST_ACTION -> {
                HostActions.run(ctx, c.hostAction, main) { r -> Log.i(TAG, "Mac $address: ${c.hostAction} ok=${r.ok} ${r.failure}") }
                MacControlCore.OK
            }
            else -> if (HotspotGuard.set(c.block.mac, c.block.blocked, c.block.name)) MacControlCore.OK else MacControlCore.FAILED
        }
    }
}
