package com.keremgok.hermessim.host.wire

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** The shared test vectors of protocol/v2/vectors; the tests run in host/android/app. */
object Vectors {
    fun cases(name: String): List<JSONObject> = objects(JSONArray(File("../../../protocol/v2/vectors/$name.json").readText()))

    fun JSONObject.list(key: String): List<JSONObject> = objects(getJSONArray(key))

    private fun objects(array: JSONArray): List<JSONObject> = List(array.length(), array::getJSONObject)

    fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}
