// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.content.Intent
import com.keremgok.hermessim.host.SetupActivity.Btn
import com.keremgok.hermessim.host.SetupActivity.Card
import com.keremgok.hermessim.host.SetupActivity.Check
import com.keremgok.hermessim.host.SetupActivity.Mark
import com.keremgok.hermessim.host.SetupActivity.Model
import com.keremgok.hermessim.wire.LineLock

/**
 * Debug builds only: every card of the setup screen with its real texts and the longest content (two SIM lines, every
 * missing permission), for screenshots on an emulator, which has no root and so reaches few states by itself.
 * `adb shell am start -n com.keremgok.hermessim.host/.SetupActivity --es demo <state>`; [STATES] lists the states.
 */
internal object SetupDemo {
    val STATES = listOf(
        "intro", "cant", "unlock", "unlock_cant", "root_checking", "root_denied", "root_unknown", "install", "install_busy",
        "install_failed", "restart", "inactive", "given_back", "given_back_after", "retire_failed", "error", "retire",
        "fix_daemon", "fix_privileged", "fix_permissions", "lock", "pair", "pair_closed", "sim_none", "sim_puk", "sim_off",
        "next_test", "next_basic", "next_box", "done",
    )
    private val SETUP = setOf("cant", "unlock", "unlock_cant", "root_checking", "root_denied", "root_unknown", "install",
        "install_busy", "install_failed", "restart", "inactive", "retire_failed", "error")
    private const val PHONE = "Galaxy S23 Ultra"
    private const val CODE = "523054"

    fun key(intent: Intent): String? = intent.getStringExtra("demo")?.takeIf { it in STATES }

    fun isHost(key: String?): Boolean = key != null && key !in SETUP && key != "given_back" && key != "given_back_after"

    /** The code and seconds left for the pairing block; null leaves the live values. */
    fun pairing(key: String?): Pair<String?, Int>? = when (key) {
        "pair", "next_test", "next_basic", "next_box", "done" -> CODE to 272
        "pair_closed" -> null to 0
        else -> null
    }

    fun retireStep(key: String?): Int? = if (key == "retire") R.string.retire_step_settings else null

    fun model(a: SetupActivity, key: String): Model? = when (key) {
        "intro" -> a.introModel()
        in SETUP -> setup(a, key)
        else -> host(a, key)
    }

    private fun b(a: SetupActivity, id: Int, enabled: Boolean = true) = Btn(a.getString(id), SetupActivity.Act.RESTART, enabled)

    private fun setupChecks(a: SetupActivity) = listOf(
        Check(a.getString(R.string.c_phone), Mark.OK, a.getString(R.string.c_phone_detail, "Xiaomi Redmi Note 11 Pro+ 5G", "13", "MediaTek Dimensity 920")),
        Check(a.getString(R.string.c_support), Mark.WARN, a.getString(R.string.tier_experimental)),
        Check(a.getString(R.string.c_boot), Mark.OK, a.getString(R.string.boot_unlocked)),
        Check(a.rs(R.string.c_root), Mark.FAIL, a.rs(R.string.root_denied)),
        Check(a.getString(R.string.c_bluetooth), Mark.OK, a.getString(R.string.ble_present)),
    )

    private fun setup(a: SetupActivity, key: String): Model {
        val card = when (key) {
            "cant" -> a.cant(a.getString(R.string.cant_cpu))
            "unlock" -> Card(a.getString(R.string.unlock_title), listOf(a.getString(R.string.unlock_body), a.getString(R.string.unlock_xiaomi),
                a.getString(R.string.unlock_erase)).joinToString("\n\n"), Mark.WARN, b(a, R.string.btn_dev_options), b(a, R.string.btn_check_root))
            "unlock_cant" -> a.cant(a.getString(R.string.unlock_cant)).copy(secondary = b(a, R.string.btn_check_root))
            "root_checking" -> Card(a.getString(R.string.root_title), a.rs(R.string.root_checking), Mark.WAIT, b(a, R.string.btn_check_root, false))
            "root_denied" -> Card(a.getString(R.string.root_title), listOf(a.getString(R.string.root_body), a.rs(R.string.root_denied)).joinToString("\n\n"),
                Mark.FAIL, b(a, R.string.btn_check_root), b(a, R.string.btn_magisk_guide))
            "root_unknown" -> Card(a.getString(R.string.root_title), a.getString(R.string.root_body), Mark.WARN,
                b(a, R.string.btn_check_root), b(a, R.string.btn_magisk_guide))
            "install" -> Card(a.getString(R.string.install_title), a.getString(R.string.install_body), Mark.OK,
                Btn(a.rs(R.string.btn_open_magisk), SetupActivity.Act.RESTART), b(a, R.string.btn_install_here))
            "install_busy" -> Card(a.getString(R.string.install_title), a.getString(R.string.install_busy_body), Mark.WAIT, b(a, R.string.btn_installing, false))
            "install_failed" -> Card(a.getString(R.string.install_failed_title), a.getString(R.string.install_failed_body, "mount: /data/adb/modules_update: Permission denied"),
                Mark.FAIL, b(a, R.string.btn_retry), b(a, R.string.btn_check_root))
            "restart" -> Card(a.getString(R.string.restart_title), a.getString(R.string.restart_body), Mark.OK, b(a, R.string.btn_restart), b(a, R.string.btn_reinstall))
            "inactive" -> Card(a.getString(R.string.inactive_title), a.rs(R.string.inactive_disabled), Mark.FAIL,
                Btn(a.rs(R.string.btn_open_magisk), SetupActivity.Act.RESTART), b(a, R.string.btn_restart))
            "retire_failed" -> Card(a.getString(R.string.retire_failed_title), listOf(a.rs(R.string.retire_failed), a.getString(R.string.retire_unpaired))
                .joinToString("\n\n"), Mark.FAIL, b(a, R.string.btn_retry), b(a, R.string.btn_close))
            else -> Card(a.getString(R.string.error_title), a.getString(R.string.error_body), Mark.FAIL)
        }
        val step = mapOf("unlock" to 1, "unlock_cant" to 1, "install" to 3, "install_busy" to 3, "install_failed" to 3, "restart" to 4, "inactive" to 4)
            .getOrDefault(key, 2).takeIf { key !in setOf("cant", "retire_failed", "error") } ?: 0
        val checks = if (key == "error") emptyList() else setupChecks(a)
        return Model(a.getString(R.string.subtitle_setup), step, if (step == 0 && key != "cant") 0 else 6, card, checks)
    }

    private fun sims(a: SetupActivity) = listOf(
        SimLine(0, carrier = "Turk Telekom", enabled = true, signal = 3, volte = true),
        SimLine(1, carrier = "Vodafone TR", lock = LineLock.LINE_LOCK_PUK),
    ).joinToString("\n") { a.simLine(it) }

    private fun hostChecks(a: SetupActivity, key: String) = listOf(
        Check(a.getString(R.string.c_phone), Mark.OK, a.getString(R.string.c_phone_detail, "Xiaomi Redmi Note 11 Pro+ 5G", "13", "MediaTek Dimensity 920")),
        Check(a.getString(R.string.c_service), if (key == "fix_daemon") Mark.FAIL else Mark.OK,
            a.getString(if (key == "fix_daemon") R.string.service_down else R.string.service_ok)),
        Check(a.getString(R.string.c_module), Mark.OK, a.getString(R.string.module_active)),
        Check(a.getString(R.string.c_permissions), Mark.WARN, a.problems(PERMS, sms = false, dialer = false, battery = false, silent = false, bt = true).joinToString(" ")),
        Check(a.getString(R.string.c_bluetooth), Mark.OK, a.getString(R.string.ble_ok)),
        Check(a.getString(R.string.c_lock), Mark.OK, a.getString(R.string.lock_off)),
        Check(a.getString(R.string.c_client), Mark.WARN, a.getString(R.string.client_away, PHONE)),
        Check(a.getString(R.string.c_sim), Mark.WARN, sims(a)),
        Check(a.getString(R.string.c_audio), Mark.WARN, a.getString(R.string.audio_untested)),
        Check(a.getString(R.string.c_box), Mark.WAIT, a.getString(R.string.box_paused, "23:45")),
    )

    private val PERMS = listOf("android.permission.READ_PHONE_STATE", "android.permission.SEND_SMS", "android.permission.BLUETOOTH_CONNECT",
        "android.permission.POST_NOTIFICATIONS")

    private fun host(a: SetupActivity, key: String): Model {
        val other = b(a, R.string.btn_pair_other)
        val card = when (key) {
            "given_back" -> Card(a.getString(R.string.given_back_title), a.rs(R.string.given_back_body), Mark.WARN, b(a, R.string.btn_restart),
                Btn(a.rs(R.string.btn_open_magisk), SetupActivity.Act.RESTART))
            "given_back_after" -> Card(a.rs(R.string.given_back_after_title), a.rs(R.string.given_back_after_body), Mark.WARN,
                Btn(a.rs(R.string.btn_open_magisk), SetupActivity.Act.RESTART), b(a, R.string.btn_restart))
            "fix_daemon" -> Card(a.getString(R.string.fix_title), a.getString(R.string.fix_daemon), Mark.FAIL, b(a, R.string.btn_restart), b(a, R.string.btn_reinstall))
            "fix_privileged" -> Card(a.getString(R.string.fix_title), a.getString(R.string.fix_privileged, a.names(Onboarding.PRIVILEGED)), Mark.FAIL,
                b(a, R.string.btn_reinstall), b(a, R.string.btn_restart))
            "fix_permissions" -> Card(a.getString(R.string.fix_title), a.fixBody(a.problems(PERMS, sms = false, dialer = false, battery = false,
                silent = false, bt = false), a.rs(R.string.fix_hint)), Mark.WARN, b(a, R.string.btn_fix))
            "lock" -> Card(a.getString(R.string.lock_title), a.getString(R.string.lock_body), Mark.WARN, b(a, R.string.btn_lock_settings))
            "pair", "pair_closed" -> Card(a.getString(R.string.pair_title), a.getString(R.string.pair_body, "Redmi Note 11 Pro+ 5G"), Mark.WARN, countdown = true)
            "sim_none" -> Card(a.getString(R.string.sim_title), a.getString(R.string.sim_body), Mark.FAIL)
            "sim_puk" -> Card(a.getString(R.string.sim_locked_title), a.getString(R.string.sim_locked_puk, 2), Mark.WARN)
            "sim_off" -> Card(a.getString(R.string.sim_off_title), a.getString(R.string.sim_switched_off), Mark.WARN)
            "next_test" -> Card(a.getString(R.string.next_title), a.getString(R.string.next_test, PHONE), Mark.WARN, secondary = other, countdown = true)
            "next_basic" -> Card(a.getString(R.string.next_title), a.getString(R.string.next_basic, PHONE), Mark.WARN, secondary = other, countdown = true)
            "next_box" -> Card(a.getString(R.string.next_title), a.getString(R.string.next_box, PHONE), Mark.WARN, secondary = other, countdown = true)
            else -> Card(a.getString(R.string.done_title), a.getString(R.string.done_body), Mark.OK, secondary = other, countdown = true)
        }
        if (key == "given_back" || key == "given_back_after") return Model(a.getString(R.string.subtitle_given_back), 0, 0, card, emptyList())
        val step = when { key == "done" -> 0; key.startsWith("sim") || key.startsWith("next") -> 6; else -> 5 }
        return Model(a.getString(R.string.subtitle_host), step, if (step == 0) 0 else 6, card, hostChecks(a, key), btName = "Redmi Note 11 Pro+ 5G")
    }
}
