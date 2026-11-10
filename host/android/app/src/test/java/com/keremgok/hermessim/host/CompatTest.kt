// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which support table row is this phone (Compat.modelRow), and how the chip is named (Compat.socName). */
class CompatTest {
    private fun row(name: String, models: List<String>, devices: List<String>) =
        JSONObject().put("name", name).put("models", JSONArray(models)).put("devices", JSONArray(devices))

    private val table = listOf(
        row("Redmi Note 9 Pro", listOf("Redmi Note 9 Pro"), listOf("joyeuse")),
        row("Redmi Note 9S / Note 9 Pro (India)", listOf("Redmi Note 9S", "Redmi Note 9 Pro"), listOf("curtana")),
        row("Redmi Note 9 Pro 5G", listOf("Redmi Note 9 Pro"), listOf("gauguin")),
    )

    @Test fun aSharedModelNameIsToldApartByTheCodename() {
        assertEquals("Redmi Note 9S / Note 9 Pro (India)", Compat.modelRow(table, "Redmi Note 9 Pro", "curtana")?.optString("name"))
        assertEquals("Redmi Note 9 Pro 5G", Compat.modelRow(table, "redmi note 9 pro", "gauguin")?.optString("name"))
        assertEquals("Redmi Note 9 Pro", Compat.modelRow(table, "Redmi Note 9 Pro", "joyeuse")?.optString("name"))
    }

    @Test fun anUnknownCodenameTakesTheFirstRowNamingTheModel() {
        assertEquals("Redmi Note 9 Pro", Compat.modelRow(table, "Redmi Note 9 Pro", "other")?.optString("name"))
        assertEquals("Redmi Note 9S / Note 9 Pro (India)", Compat.modelRow(table, "Redmi Note 9S", "joyeuse")?.optString("name"))
        assertNull(Compat.modelRow(table, "Pixel 8", "shiba"))
    }

    @Test fun aChipNamedUnknownFallsBackToTheBoardPlatformThenTheHardware() {
        assertEquals("Qualcomm SM7125", Compat.socName("Qualcomm SM7125", { "atoll" }, { "qcom" }))
        assertEquals("atoll", Compat.socName("unknown unknown", { "atoll" }, { "qcom" }))
        assertEquals("Mediatek", Compat.socName("Mediatek unknown", { "mt6768" }, { "mt6768" }))
        assertEquals("exynos7870", Compat.socName("", { "" }, { "exynos7870" }))
    }
}
