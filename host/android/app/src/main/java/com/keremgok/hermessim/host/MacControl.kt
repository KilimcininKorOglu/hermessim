// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.content.Context
import android.os.Handler
import android.util.Log
import com.keremgok.hermessim.protocol.v1.HostActionResult
import com.keremgok.hermessim.protocol.v1.MacControl
import com.keremgok.hermessim.protocol.v1.MacLine
import com.keremgok.hermessim.protocol.v1.MacStatus
import com.keremgok.hermessim.protocol.v1.SetSim
import com.keremgok.hermessim.protocol.v1.Sim

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

    /** The host's lines as a Mac with control chooses among them (MacStatus.lines). */
    fun lines(sims: List<Sim>): List<MacLine> = sims.map { sim ->
        MacLine.newBuilder().apply { slot = sim.slot; name = sim.operatorName; enabled = sim.enabled; defaultData = sim.defaultData }.build()
    }

    /** [base] as one Mac sees it: its own permissions, and the lines and devices only with [control]. */
    fun shown(base: MacStatus, texts: Boolean, control: Boolean): MacStatus = base.toBuilder().also { b ->
        b.texts = texts
        b.control = control
        if (!control) b.clearLines().clearClients().clearBlocked()
    }.build()

    /**
     * A status notification's value: the status when it fits the link ([mtu] - 3 bytes), else nothing, and the Mac
     * reads the status (a long read).
     */
    fun notified(status: ByteArray, mtu: Int): ByteArray = if (status.size <= mtu - 3) status else ByteArray(0)
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
