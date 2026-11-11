// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.util.Log

/**
 * One look at the Bluetooth stack: who scans ([LinkRadio.scans]) and what the chip did with a link's last seconds
 * ([HciRing]). Taken at a call's end and a few seconds after a link comes up; a call's numbers carry it.
 */
object RadioLook {
    private const val TAG = "HermesSimHost"
    // Where Android writes the record, and where Samsung's does (a Galaxy A13 on Android 14, 11 Nov 2026)
    private const val RINGS = "/data/misc/bluetooth/logs/btsnooz_hci.log /data/log/bt/btsnooz_hci.log"
    private const val MARK = "@ring"

    /** [scans]; how the chip sent the busiest link's last seconds, as numbers and in words; what that link agreed. */
    class Look(val scans: String, val sending: HciRing.Sending?, val hci: String, val link: String)

    @Volatile private var links: Map<Int, HciRing.Link> = emptyMap()
    @Volatile private var buffers = ""
    @Volatile private var lastLinkLook = 0L

    /** A new link: what an earlier one agreed no longer holds. */
    fun forget() { links = emptyMap() }

    /**
     * One root command, two seconds or three: the Bluetooth dump's scanner list, then the record of the chip's traffic
     * that the same dump has just written ([HciRing]). Only numbers leave here. Asked at a call's end and a few seconds
     * after a link comes up, never during a call: the dump runs inside the Bluetooth stack. Never throws.
     */
    fun look(): Look = runCatching {
        val r = Root.sh("dumpsys bluetooth_manager | sed -n '/GATT Scanner Map/,/GATT Advertiser Map/p;/Le scan:/p' | head -c 16000; " +
            "echo; echo $MARK; f=\$(ls -t $RINGS 2>/dev/null | head -1); if [ -n \"\$f\" ]; then echo age \$(( \$(date +%s) - \$(stat -c %Y \$f) )); base64 \$f | head -c 8000000; " +
            "else echo none; ls /data/misc/bluetooth/logs /data/log/bt 2>&1 | head -12; fi", 3_000)
        val scans = LinkRadio.scans(r.out.substringBefore("\n$MARK\n"))
        val rest = r.out.substringAfter("\n$MARK\n", "")
        when {
            rest.isEmpty() -> Look(scans, null, "no answer", "")
            // No such file on this phone: what its Bluetooth keeps instead, by name (a folder listing, no content)
            !rest.startsWith("age ") -> Look(scans, null, "no record file (${otherFiles(rest)})", "")
            else -> fromRecord(scans, rest)
        }
    }.getOrElse {
        Log.w(TAG, "BLE look failed: $it")
        Look("", null, "failed: ${it.javaClass.simpleName}", "")
    }

    private fun otherFiles(rest: String): String =
        rest.lines().drop(1).map { it.trim() }.filter { it.isNotEmpty() && it != "timeout" }.joinToString(" ").take(200)

    /** [rest]: "age N", then the record file in base64. */
    private fun fromRecord(scans: String, rest: String): Look {
        val age = rest.substringBefore('\n').removePrefix("age ").trim().toLongOrNull() ?: -1
        val b64 = rest.substringAfter('\n').filterNot { it.isWhitespace() }.removeSuffix("timeout")
        val file = java.util.Base64.getDecoder().decode(b64.take(b64.length - b64.length % 4))
        val recs = HciRing.records(file)
        if (recs.isEmpty()) return Look(scans, null,
            "record not readable (${file.size} B, starts ${file.take(16).joinToString("") { "%02x".format(it) }})", "")
        links = HciRing.links(recs, links)
        HciRing.buffers(recs).let { if (it.isNotEmpty()) buffers = it }
        val s = HciRing.sending(recs)
        val stale = if (age in 0..5) "" else ", written $age s ago"
        val link = (s?.let { links[it.handle] } ?: links.values.singleOrNull())?.text().orEmpty() +
            if (buffers.isEmpty()) "" else ", chip room $buffers"
        return Look(scans, s, (s?.text() ?: "no data sent") + " | ${HciRing.size(recs)}$stale", link.removePrefix(", "))
    }

    /**
     * A link came up a few seconds ago: what it agreed on (packet length, interval), while the record still holds it.
     * A call's audio pushes that out of the record within seconds. Not in a call, at most once a minute.
     * [onlyIfUnknown]: a client said hello, which it does every few minutes; one look is enough. Any thread.
     */
    fun linkLook(onlyIfUnknown: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        val looked = lastLinkLook != 0L
        if (!VoiceGate.fullVoice || CallRegistry.anyCall() || (looked && (onlyIfUnknown || now - lastLinkLook < 60_000))) return
        lastLinkLook = now
        Thread {
            val l = look()
            Log.i(TAG, "BLE link: ${l.link.ifEmpty { l.hci }}")
        }.apply { isDaemon = true }.start()
    }
}
