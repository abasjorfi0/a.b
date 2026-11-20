package app.zapret.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A saved strategy = a name + a byedpi-style command line (see README for supported options). */
data class Strategy(val id: String, val name: String, val args: String, val builtin: Boolean = false)

enum class DnsMode { SYSTEM, DOH, AUTO }
enum class AppMode { EXCLUDE, ONLY }
/** FULL = DPI bypass + DNS, DNS_ONLY = only the smart encrypted DNS (traffic passes through untouched). */
enum class RunMode { FULL, DNS_ONLY }

data class DohResolver(val name: String, val url: String, val ips: List<String>)

object Resolvers {
    @Volatile internal var allCache: List<DohResolver>? = null
    // Bootstrap IPs are included so the DoH hostname never needs a (possibly poisoned) DNS lookup.
    // First four come from the DNSCrypt public-resolvers list and are the ones confirmed working on the user's PC.
    val builtin = listOf(
        DohResolver("digitalsize", "https://dns.digitalsize.net/dns-query", listOf("94.130.135.203")),
        DohResolver("dnsforge-nofilter", "https://blank.dnsforge.de/dns-query", listOf("138.199.149.249")),
        DohResolver("dnshome-de", "https://dns.dnshome.de/dns-query", emptyList()),
        DohResolver("ffmuc", "https://doh.ffmuc.net/dns-query", listOf("5.1.66.255")),
        DohResolver("Cloudflare", "https://cloudflare-dns.com/dns-query", listOf("1.1.1.1", "1.0.0.1")),
        DohResolver("Google", "https://dns.google/dns-query", listOf("8.8.8.8", "8.8.4.4")),
        DohResolver("Quad9", "https://dns.quad9.net/dns-query", listOf("9.9.9.9", "149.112.112.112")),
        DohResolver("AdGuard", "https://dns.adguard-dns.com/dns-query", listOf("94.140.14.14", "94.140.15.15")),
        DohResolver("Mullvad", "https://dns.mullvad.net/dns-query", listOf("194.242.2.2")),
    )
}

/** All DoH resolvers: favorites first, then the full DNSCrypt public-resolvers list (bundled asset). */
fun Resolvers.all(ctx: Context): List<DohResolver> = allCache ?: synchronized(this) {
    allCache ?: run {
        val extra = runCatching {
            val a = JSONArray(ctx.assets.open("doh-resolvers.json").bufferedReader().use { it.readText() })
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                val ips = o.getJSONArray("i").let { x -> (0 until x.length()).map { k -> x.getString(k) } }
                DohResolver(o.getString("n"), o.getString("u"), ips)
            }
        }.getOrDefault(emptyList())
        val merged = LinkedHashMap<String, DohResolver>()
        builtin.forEach { merged[it.url] = it }
        // keep one entry per URL, preferring the one that has a bootstrap IP
        extra.filter { !it.name.endsWith("ipv6") }.sortedBy { it.name.lowercase() }.forEach { r ->
            val cur = merged[r.url]
            if (cur == null || (cur.ips.isEmpty() && r.ips.isNotEmpty())) merged[r.url] = r
        }
        merged.values.toList().also { allCache = it }
    }
}

data class Settings(
    val strategyId: String = "builtin-v6",
    val dnsMode: DnsMode = DnsMode.AUTO,
    val dohUrl: String = "https://doh.ffmuc.net/dns-query",
    val plainDns: String = "8.8.8.8",
    val appMode: AppMode = AppMode.EXCLUDE,
    val apps: Set<String> = emptySet(),
    val runMode: RunMode = RunMode.FULL,
) {
    fun toJson(strategyArgs: String): String = JSONObject()
        .put("strategy", strategyArgs)
        .put("dnsMode", dnsMode.name)
        .put("dohUrl", dohUrl)
        .put("plainDns", plainDns)
        .put("appMode", appMode.name)
        .put("runMode", runMode.name)
        .put("apps", JSONArray(apps.toList()))
        .toString()

    fun toStored(): JSONObject = JSONObject()
        .put("strategyId", strategyId).put("dnsMode", dnsMode.name).put("dohUrl", dohUrl)
        .put("plainDns", plainDns).put("appMode", appMode.name).put("runMode", runMode.name).put("apps", JSONArray(apps.toList()))

    companion object {
        fun fromStored(o: JSONObject): Settings {
            val d = Settings()
            val apps = o.optJSONArray("apps")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
            return Settings(
                strategyId = o.optString("strategyId", d.strategyId),
                dnsMode = runCatching { DnsMode.valueOf(o.optString("dnsMode")) }.getOrDefault(d.dnsMode),
                dohUrl = o.optString("dohUrl", d.dohUrl),
                plainDns = o.optString("plainDns", d.plainDns),
                appMode = runCatching { AppMode.valueOf(o.optString("appMode")) }.getOrDefault(d.appMode),
                apps = apps,
                runMode = runCatching { RunMode.valueOf(o.optString("runMode")) }.getOrDefault(d.runMode),
            )
        }
    }
}

object Presets {
    // Mobile port of the PC "all in one V6" (winws2). Group order matters: byedpi uses group 0 for UDP, host/proto
    // groups are matched first, the default group handles the rest, and the failover groups (-At,r,s) are tried one
    // after another only when a connection times out / is reset / gets a TLS error (= the PC's "circular" rotation).
    private val V6 = """
        # ===== V6 for mobile (byedpi) =====
        # Wait 4s for the first server reply; lifted after 2000 bytes so big downloads/videos are never cut.
        -T 4:2000
        # G0 - exclusions (PC list-nodesync.txt) + Instagram/Meta: NO desync. Also the UDP/QUIC/STUN/game fake
        # (PC: fake x8 on QUIC, x6 on STUN/games), because UDP always uses the first group.
        --hosts ":ubi.com ubisoft.com ea.com easports.com origin.com origin-a.akamaihd.net instagram.com cdninstagram.com fbcdn.net facebook.com fb.com facebook.net fbsbx.com" -a8
        # G1 - YouTube / Google video: disoob + split + fake(md5sig). Confirmed for YouTube video.
        --auto=none --hosts ":googlevideo.com youtube.com youtu.be ytimg.com ggpht.com googleusercontent.com youtube-nocookie.com" -q1+s -s29+s -s30+s -s14+s -o5+s -f-1 -S -a1
        # G2 - X / Twitter (pages, images, video, GIF): long split chain + TLS record + md5sig fake
        --auto=none --hosts ":x.com twitter.com twimg.com t.co" -d1 -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -r1+s -S -a1
        # G3 - plain HTTP (port 80): fake(md5sig) + split after the method (PC: fake http md5 + multisplit method+2)
        --auto=none --proto=h -s2 -f-1 -S -a1
        # G4 - everything else (TLS, any port): the proven mobile strategy (disorder + TLS record split)
        --auto=none --disorder 1 --tlsrec 1+s -a1
        # ---- failover groups (PC circular strategies 1,2,3 first, then extra community ones) ----
        # PC 1: fake md5 (random TLS, SNI www.google.com) + multidisorder at 1 and SNI middle
        -At,r,s -n www.google.com -Qr -f-1 -S -d1 -d0+sm -a1
        # PC 2: fake SNI fonts.google.com + multisplit at 1 and SNI middle (seqovl is root-only, so no overlap)
        -At,r,s -n fonts.google.com -Qr -f-1 -S -s1 -s0+sm -a1
        # PC 3: fake (low TTL) + fakedsplit in the SNI middle
        -At,r,s -n www.google.com -Qr -f-1 -t8 -s0+sm -a1
        -At,r,s -d1 -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -a1
        -At,r,s -q1+s -s29+s -o5+s -f-1 -S -a1
        -At,r,s -n www.google.com -Qr -f1 -d1:11+sm -s1:11+sm -S -a1
        -At,r,s -o1 -d1 -r1+s -S -s1+s -d3+s -a1
        -At,r,s -f-1 -Qr -s1+sm -d3+s -s5+sm -o2 -a1
        -At,r,s -d1 -s1+s -r1+s -f-1 -t8 -a1
        -At,r,s -q2 -s2 -s3+s -r3 -s4 -r4 -s5+s -r5+s -s6 -s7+s -r8 -s9+s -Qr -Mh,d,r -a1
    """.trimIndent()

    val v6 = Strategy("builtin-v6", "V6 (mobile port of the PC strategy)", V6, true)
    val all = listOf(v6)
}

/** Persists user strategies + settings as JSON in the app's private files dir. */
class Store(ctx: Context) {
    private val dir = ctx.filesDir
    private val strategiesFile = File(dir, "strategies.json")
    private val settingsFile = File(dir, "settings.json")

    fun userStrategies(): List<Strategy> = runCatching {
        val a = JSONArray(strategiesFile.readText())
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Strategy(o.getString("id"), o.getString("name"), o.getString("args"))
        }
    }.getOrDefault(emptyList())

    fun saveUserStrategies(list: List<Strategy>) {
        val a = JSONArray()
        list.filter { !it.builtin }.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("args", it.args)) }
        strategiesFile.writeText(a.toString())
    }

    fun allStrategies(): List<Strategy> = Presets.all + userStrategies()
    fun find(id: String): Strategy = allStrategies().firstOrNull { it.id == id } ?: Presets.v6

    fun newId(): String = "user-" + UUID.randomUUID().toString().take(8)

    fun settings(): Settings = runCatching { Settings.fromStored(JSONObject(settingsFile.readText())) }.getOrDefault(Settings())
    fun saveSettings(s: Settings) { settingsFile.writeText(s.toStored().toString()) }

    /** Export/import: a JSON text the user can copy, share, or paste back. */
    fun exportStrategies(): String {
        val a = JSONArray()
        userStrategies().forEach { a.put(JSONObject().put("name", it.name).put("args", it.args)) }
        return JSONObject().put("zapret-mobile-strategies", a).toString(2)
    }

    fun importStrategies(text: String): Int {
        val a = JSONObject(text).getJSONArray("zapret-mobile-strategies")
        val cur = userStrategies().toMutableList()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            cur += Strategy(newId(), o.getString("name"), o.getString("args"))
        }
        saveUserStrategies(cur)
        return a.length()
    }
}

/** Splits a strategy line into argv tokens (quotes, newlines and #comments supported) and validates it. */
object ArgParser {
    private val forbidden = setOf("--daemon", "-D", "--pidfile", "-w", "--ip", "-i", "--port", "-p", "--protect-path", "-P")

    fun tokenize(text: String): List<String> {
        val out = mutableListOf<String>()
        text.lines().forEach { raw ->
            val line = raw.substringBefore(" #").let { if (it.trimStart().startsWith("#")) "" else it }
            var cur = StringBuilder(); var q: Char? = null; var has = false
            for (c in line) {
                when {
                    q != null -> if (c == q) q = null else cur.append(c)
                    c == '"' || c == '\'' -> { q = c; has = true }
                    c.isWhitespace() -> if (has || cur.isNotEmpty()) { out += cur.toString(); cur = StringBuilder(); has = false }
                    else -> cur.append(c)
                }
            }
            if (has || cur.isNotEmpty()) out += cur.toString()
        }
        return out
    }

    /** Returns a human-readable problem, or null if the line looks usable. */
    fun validate(text: String): String? {
        val t = tokenize(text)
        t.firstOrNull { it.substringBefore('=') in forbidden }?.let { return "Option $it is set by the app and cannot be used here" }
        if (t.any { it == "--drop-sack" || it == "-Y" }) return "--drop-sack needs root and is not supported"
        return null
    }
}
