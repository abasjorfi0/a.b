package app.zapret.mobile

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Downloads the DNSCrypt public-resolvers list and turns the DoH stamps (sdns://) into our resolver JSON. */
object ResolverUpdater {
    private const val URL = "https://raw.githubusercontent.com/DNSCrypt/dnscrypt-resolvers/master/v3/public-resolvers.md"

    private fun client(proxy: Boolean): OkHttpClient {
        val b = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS)
        if (proxy) b.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", ZapretVpnService.SOCKS_PORT)))
        return b.build()
    }

    private fun fetch(): String? {
        for (viaProxy in listOf(false, true)) {
            val t = runCatching {
                client(viaProxy).newCall(Request.Builder().url(URL).build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
            }.getOrNull()
            if (!t.isNullOrBlank()) return t
        }
        return null
    }

    private fun lp(b: ByteArray, pos: Int): Pair<String, Int>? {
        if (pos >= b.size) return null
        val n = b[pos].toInt() and 0xFF
        if (pos + 1 + n > b.size) return null
        return String(b, pos + 1, n, Charsets.UTF_8) to pos + 1 + n
    }

    /** DoH stamp: 0x02, 8 props bytes, lp(addr), vlp(hashes), lp(host), lp(path). */
    private fun parseStamp(stamp: String): Pair<String, List<String>>? = runCatching {
        val b = Base64.getUrlDecoder().decode(stamp.removePrefix("sdns://").trim())
        if (b.isEmpty() || b[0].toInt() != 0x02) return null
        var pos = 9
        val (addr, p1) = lp(b, pos) ?: return null
        pos = p1
        while (pos < b.size) { val n = b[pos].toInt() and 0xFF; pos += 1 + (n and 0x7F); if (n and 0x80 == 0) break }
        val (host, p2) = lp(b, pos) ?: return null
        val (path, _) = lp(b, p2) ?: return null
        val ip = addr.substringBefore(':').takeIf { it.isNotBlank() && !addr.startsWith("[") && it.count { c -> c == '.' } == 3 }
        "https://$host$path" to listOfNotNull(ip)
    }.getOrNull()

    /** Returns a short status message; on success the new list is used from the next start. */
    fun update(ctx: Context): String {
        val md = fetch() ?: return "Could not download the list (no internet to GitHub). Try again with Konata running."
        val arr = JSONArray(); var name = ""; val seen = HashSet<String>()
        for (line in md.lines()) {
            if (line.startsWith("## ")) name = line.removePrefix("## ").trim()
            else if (line.startsWith("sdns://") && name.isNotEmpty()) {
                val r = parseStamp(line) ?: continue
                if (r.first in seen) continue
                seen += r.first
                arr.put(JSONObject().put("n", name).put("u", r.first).put("i", JSONArray(r.second)).put("f", 0))
            }
        }
        if (arr.length() < 20) return "The downloaded list looks wrong (${arr.length()} entries) - kept the old one."
        File(ctx.filesDir, "doh-resolvers.json").writeText(arr.toString())
        Resolvers.allCache = null
        return "Updated: ${arr.length()} DoH resolvers. Restart Konata to use them."
    }
}
