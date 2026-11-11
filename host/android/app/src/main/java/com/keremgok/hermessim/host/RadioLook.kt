// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.util.Log

/**
 * One look at the Bluetooth stack: who scans ([LinkRadio.scans]) and what the chip did with a link's last seconds
 * ([HciRing]). Taken at a call's end and, on MediaTek hosts, once a link serves ([linkLook]); a call's numbers carry it.
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
    @Volatile private var linkLooked = false
    private val mediatek: Boolean by lazy {
        LinkRadio.isMediatek(listOf(Compat.soc(), Compat.prop("ro.board.platform"), android.os.Build.HARDWARE))
    }

    /** A new link: what an earlier one agreed no longer holds, and it gets its own look. */
    fun forget() { links = emptyMap(); linkLooked = false }

    /**
     * One root command, two seconds or three: the Bluetooth dump's scanner list, then the record of the chip's traffic
     * that the same dump has just written ([HciRing]). Only numbers leave here. Asked at a call's end and by [linkLook],
     * never during a call: the dump runs inside the Bluetooth stack. Never throws.
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
        val (recs, how) = HciFile.read(file)
        val stale = if (age in 0..5) "" else ", written $age s ago"
        if (recs.isEmpty()) return Look(scans, null, "record not readable (${HciFile.probe(file)}$stale)", "")
        links = HciRing.links(recs, links)
        HciRing.buffers(recs).let { if (it.isNotEmpty()) buffers = it }
        val s = HciRing.sending(recs)
        // With how many links share the chip: its room is divided between them
        val link = (s?.let { links[it.handle] } ?: links.values.singleOrNull())?.text().orEmpty() +
            (if (buffers.isEmpty()) "" else ", chip room $buffers") + (if (links.size > 1) ", ${links.size} links up" else "")
        val layout = if (how.isEmpty()) "" else ", $how"
        return Look(scans, s, (s?.text() ?: "no data sent") + " | ${HciRing.size(recs)}$layout$stale", link.removePrefix(", "))
    }

    /**
     * What a link agreed on (packet length, interval), while the record still holds it: a call's audio pushes that out
     * within seconds. Once per link, 20 s after the phone's Hello ([Relay]), on MediaTek hosts only: theirs is the
     * sending that needs explaining, and a look is a dump inside the Bluetooth stack. It used to run on every full-voice
     * host 6 s after a link came up, which is when a restarted host may be dropping that link or switching Bluetooth off
     * and on to get an iPhone's Hello; an iPhone did not get back to its Redmi Note 9 Pro host over Bluetooth for three
     * minutes after the host updated itself (11 Nov 2026). Nothing shows the look caused it; it no longer runs there.
     * Never in a call. Any thread.
     */
    fun linkLook() {
        if (!mediatek || linkLooked || !VoiceGate.fullVoice || CallRegistry.anyCall()) return
        linkLooked = true
        Thread {
            val l = look()
            Log.i(TAG, "BLE link: ${l.link.ifEmpty { l.hci }}")
            // A look that got no answer says nothing: the next Hello looks again
            if (l.hci.startsWith("no answer") || l.hci.startsWith("failed")) linkLooked = false
        }.apply { isDaemon = true }.start()
    }
}
