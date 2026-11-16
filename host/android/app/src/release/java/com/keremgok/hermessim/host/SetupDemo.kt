// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.content.Intent

/** The release build has no demo states: every answer is "no demo". */
internal object SetupDemo {
    fun key(intent: Intent): String? = null
    fun model(a: SetupActivity, key: String): SetupActivity.Model? = null
    fun isHost(key: String?): Boolean = false
    fun pairing(key: String?): Pair<String?, Int>? = null
    fun retireStep(key: String?): Int? = null
}
