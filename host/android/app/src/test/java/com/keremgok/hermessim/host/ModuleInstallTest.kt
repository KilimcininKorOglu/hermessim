// HermesSIM host. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The update install command run in a real shell on a temporary module: the host is a persistent system app, so an
 * update replaces the module's APK and restarts the phone. The old APK must stay whole whenever a step fails, and the
 * phone must restart only after the new APK is in place.
 */
class ModuleInstallTest {
    @get:Rule val dir = TemporaryFolder()

    private val quote: (String) -> String = { "'" + it.replace("'", "'\\''") + "'" }

    private fun run(apk: File, moduleApk: File, restarted: File): Int {
        val command = UpdateCore.moduleInstall(apk.path, quote, moduleApk.path, "touch ${quote(restarted.path)}")
        return ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start().waitFor()
    }

    @Test fun `a verified update takes the module's place and then the phone restarts`() {
        val moduleApk = File(dir.newFolder("priv-app"), "HermesSimHost.apk").apply { writeText("old") }
        val update = dir.newFile("update.apk").apply { writeText("new") }
        val restarted = File(dir.root, "restarted")
        assertEquals(0, run(update, moduleApk, restarted))
        assertEquals("new", moduleApk.readText())
        assertTrue(restarted.exists())
        assertFalse(File("${moduleApk.path}.new").exists())
    }

    @Test fun `without the module nothing is written and the phone does not restart`() {
        val moduleApk = File(dir.newFolder("priv-app"), "HermesSimHost.apk")
        val update = dir.newFile("update.apk").apply { writeText("new") }
        val restarted = File(dir.root, "restarted")
        assertNotEquals(0, run(update, moduleApk, restarted))
        assertFalse(moduleApk.exists())
        assertFalse(restarted.exists())
    }

    @Test fun `a failed copy keeps the old app whole and the phone does not restart`() {
        val moduleApk = File(dir.newFolder("priv-app"), "HermesSimHost.apk").apply { writeText("old") }
        val missing = File(dir.root, "gone.apk")
        val restarted = File(dir.root, "restarted")
        assertNotEquals(0, run(missing, moduleApk, restarted))
        assertEquals("old", moduleApk.readText())
        assertFalse(restarted.exists())
    }

    @Test fun `the real command restarts the phone and targets the module's app`() {
        val command = UpdateCore.moduleInstall("/data/x.apk", quote)
        assertTrue(command.endsWith("&& reboot"))
        assertTrue(command.contains(quote(UpdateCore.MODULE_APK)))
    }
}
