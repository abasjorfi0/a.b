package app.zapret.mobile

import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Local DNS engine on 127.0.0.1:PORT. byedpi's resolver (zp_resolver.c) sends it wire-format queries.
 *
 * Modes: SYSTEM = plain UDP DNS, DOH = one chosen DoH endpoint, AUTO = "smart" selection:
 *  - resolvers are ranked by measured latency and the best two are raced for every query;
 *  - an answer only counts as good if its addresses are real (no 0.0.0.0 / private block-page IPs) AND at least
 *    one of them accepts a TCP connection on :443 - a resolver that answers fast but returns an unreachable or
 *    geo-blocked CDN address is skipped, the next resolver is asked, like dnscrypt-proxy switching servers
 *    in SimpleDnsCrypt's query log until the site works;
 *  - the answer handed to byedpi contains only the reachable addresses;
 *  - the resolver that worked is remembered per domain, failures are penalised, rankings refresh in the background.
 *
 * DoH requests go out directly (this :vpn process is excluded from the VPN). They must NOT go through the
 * local byedpi proxy: byedpi resolves names synchronously through this engine, so a request that waits for
 * byedpi while byedpi waits for us would stall until timeout.
 */
class DnsEngine(
    private val mode: DnsMode, private val dohUrl: String, private val plainDns: String,
    private val all: List<DohResolver>,
) {
    companion object {
        const val PORT = 5300
        private val DNS_MSG = "application/dns-message".toMediaType()
        private const val PENALTY_MS = 1500L
        private const val TTL_OK = 10 * 60_000L
        private const val TTL_WEAK = 30_000L
    }

    private val resolvers: List<DohResolver> =
        all + if (all.none { it.url == dohUrl }) listOf(DohResolver("custom", dohUrl, emptyList())) else emptyList()
    private val byUrl = resolvers.associateBy { it.url }
    private val ipsByHost: Map<String, List<String>> =
        resolvers.groupBy { it.url.substringAfter("//").substringBefore("/").substringBefore(":") }
            .mapValues { e -> e.value.flatMap { it.ips }.distinct() }

    private val bootstrap = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val ips = ipsByHost[hostname].orEmpty()
            return if (ips.isNotEmpty()) ips.map { InetAddress.getByName(it) } else Dns.SYSTEM.lookup(hostname)
        }
    }

    private val direct = OkHttpClient.Builder().dns(bootstrap)
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).callTimeout(4, TimeUnit.SECONDS)
        .build()

    private val pool = Executors.newCachedThreadPool()
    @Volatile private var sock: DatagramSocket? = null

    // ---- AUTO state ----------------------------------------------------------------------------
    private val latency = ConcurrentHashMap<String, Long>()
    private val penalty = ConcurrentHashMap<String, Int>()
    private val preferred = ConcurrentHashMap<String, String>()
    @Volatile private var ranked: List<DohResolver> = Resolvers.builtin.mapNotNull { byUrl[it.url] }

    private fun score(r: DohResolver): Long {
        val l = latency[r.url] ?: 800L
        return (if (l == Long.MAX_VALUE) 60_000L else l) + (penalty[r.url] ?: 0) * PENALTY_MS
    }

    private fun rerank() { ranked = latency.keys.mapNotNull { byUrl[it] }.sortedBy { score(it) } }

    private fun candidates(): List<DohResolver> {
        val fav = Resolvers.builtin.mapNotNull { byUrl[it.url] }
        val rest = resolvers.filter { r -> fav.none { it.url == r.url } && r.ips.isNotEmpty() }.shuffled().take(10)
        return (fav + rest).distinctBy { it.url }
    }

    // ---- lifecycle -----------------------------------------------------------------------------
    fun start() {
        val s = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT)) }
        sock = s
        VpnLog.w("DNS engine listening on 127.0.0.1:$PORT (mode=$mode)")
        if (mode == DnsMode.AUTO) pool.execute {
            probeAll()
            try { while (true) { Thread.sleep(5 * 60_000L); probeAll() } } catch (_: InterruptedException) {}
        }
        pool.execute {
            val buf = ByteArray(1500)
            while (!s.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (_: Exception) { break }
                val q = p.data.copyOf(p.length); val from = p.socketAddress
                pool.execute {
                    val a = resolve(q, qname(q))
                    val out = a ?: servfail(q)
                    runCatching { s.send(DatagramPacket(out, out.size, from)) }
                }
            }
        }
    }

    fun stop() { sock?.close(); pool.shutdownNow() }

    // ---- resolving -----------------------------------------------------------------------------
    private class Res(val data: ByteArray, val ttlMs: Long)
    private class Cached(val data: ByteArray, val expires: Long)
    private val cache = ConcurrentHashMap<String, Cached>()

    private fun resolve(q: ByteArray, name: String): ByteArray? {
        if (q.size < 13) return resolveUncached(q, name)?.data
        val key = String(q, 12, q.size - 12, Charsets.ISO_8859_1)
        cache[key]?.let { c ->
            if (c.expires > System.currentTimeMillis()) {
                val a = c.data.copyOf(); a[0] = q[0]; a[1] = q[1]; return a
            }
            cache.remove(key)
        }
        val r = resolveUncached(q, name)
        if (r == null) { VpnLog.w("DNS $name -> FAILED"); return null }
        val ok = r.data.size > 12 && (r.data[3].toInt() and 0x0F) == 0 && ((r.data[6].toInt() shl 8) or (r.data[7].toInt() and 0xFF)) != 0
        if (ok) cache[key] = Cached(r.data, System.currentTimeMillis() + r.ttlMs)
        return r.data
    }

    private fun resolveUncached(q: ByteArray, name: String): Res? = when (mode) {
        DnsMode.SYSTEM -> plain(q)?.let { Res(it, TTL_OK) }
        DnsMode.DOH -> (byUrl[dohUrl]?.let { doh(it, q) } ?: plain(q))?.let { Res(it, TTL_OK) }
        DnsMode.AUTO -> autoResolve(q, name.lowercase())
    }

    private class Cand(val r: DohResolver, val answer: ByteArray, val quality: Int, val ms: Long, val ips: List<String>)

    /**
     * quality 3 = NXDOMAIN / empty answer (trusted as is), 2 = has a reachable address (answer rewritten to
     * reachable ones only), 1 = real addresses but none reachable, 0 = bogus (0.0.0.0, private...) or unparsable.
     */
    private fun fetchValidated(r: DohResolver, q: ByteArray): Cand? {
        val t0 = System.nanoTime()
        val a = doh(r, q) ?: return null
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (a.size < 12) return Cand(r, a, 0, ms, emptyList())
        val rcode = a[3].toInt() and 0x0F
        val ips = parseA(a)
        if (ips.isEmpty()) return if (rcode == 0 || rcode == 3) Cand(r, a, 3, ms, ips) else Cand(r, a, 0, ms, ips)
        val good = ips.filterNot { isBogon(it) }
        if (good.isEmpty()) return Cand(r, a, 0, ms, ips)
        val ok = good.filter { reachable(it) }.take(3)
        return if (ok.isNotEmpty()) Cand(r, buildAnswer(q, ok), 2, ms, ok) else Cand(r, a, 1, ms, good)
    }

    private fun autoResolve(q: ByteArray, name: String): Res? {
        val order = ArrayList<DohResolver>()
        preferred[name]?.let { u -> byUrl[u]?.let { order += it } }
        ranked.forEach { if (it !in order) order += it }
        if (order.isEmpty()) order += Resolvers.builtin.mapNotNull { byUrl[it.url] }
        val waves = listOf(order.take(2), order.drop(2).take(3), candidates().filter { it !in order.take(5) }.take(4))
        var weak: Cand? = null
        for ((i, wave) in waves.withIndex()) {
            if (wave.isEmpty()) continue
            if (i == 2) pool.execute { probeAll() }
            val ecs = ExecutorCompletionService<Cand?>(pool)
            wave.forEach { r -> ecs.submit(Callable { fetchValidated(r, q) }) }
            repeat(wave.size) {
                val c = try { ecs.take().get() } catch (_: Exception) { null }
                if (c == null) return@repeat
                if (c.quality >= 2) {
                    preferred[name] = c.r.url; penalty[c.r.url] = 0; latency[c.r.url] = c.ms; rerank()
                    VpnLog.w("DNS $name -> ${c.ips.firstOrNull() ?: "(none)"} via ${c.r.name} ${c.ms}ms ${if (c.quality == 2) "reachable" else "empty"}")
                    return Res(c.answer, TTL_OK)
                }
                // slow/unreachable/bogus answer: penalise only the resolver that returned nothing usable at all
                if (c.quality == 0) penalty.merge(c.r.url, 1) { x, y -> minOf(x + y, 6) }
                if (c.quality == 1 && weak == null) weak = c
            }
            weak?.let { w -> if (i >= 1) return finishWeak(name, w) }
            rerank()
        }
        weak?.let { return finishWeak(name, it) }
        plain(q)?.let { VpnLog.w("DNS $name -> plain DNS fallback"); return Res(it, TTL_WEAK) }
        return null
    }

    private fun finishWeak(name: String, w: Cand): Res {
        VpnLog.w("DNS $name -> ${w.ips.firstOrNull()} via ${w.r.name} (NOT reachable on :443 from any resolver tried)")
        return Res(w.answer, TTL_WEAK)
    }

    private fun doh(r: DohResolver, q: ByteArray): ByteArray? = try {
        val req = Request.Builder().url(r.url).header("Accept", "application/dns-message")
            .post(q.toRequestBody(DNS_MSG)).build()
        direct.newCall(req).execute().use { if (it.isSuccessful) it.body?.bytes() else null }
    } catch (e: Exception) {
        VpnLog.w("DoH ${r.name} failed: ${e.javaClass.simpleName} ${e.message?.take(60)}")
        null
    }

    private fun plain(q: ByteArray): ByteArray? = try {
        DatagramSocket().use { s ->
            s.soTimeout = 3000
            s.send(DatagramPacket(q, q.size, InetAddress.getByName(plainDns), 53))
            val b = ByteArray(1500); val p = DatagramPacket(b, b.size)
            s.receive(p); p.data.copyOf(p.length)
        }
    } catch (e: Exception) { null }

    /** Probe a sample of resolvers in parallel with a tiny A query and update the latency table. */
    private fun probeAll() {
        val q = byteArrayOf(0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(),
            'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(), 3, 'c'.code.toByte(), 'o'.code.toByte(),
            'm'.code.toByte(), 0, 0, 1, 0, 1)
        val futures = candidates().map { r ->
            r to pool.submit(Callable {
                val t0 = System.nanoTime()
                if (doh(r, q) != null) (System.nanoTime() - t0) / 1_000_000 else Long.MAX_VALUE
            })
        }
        futures.forEach { (r, f) ->
            val t = runCatching { f.get() }.getOrDefault(Long.MAX_VALUE)
            latency[r.url] = t
            if (t != Long.MAX_VALUE) penalty[r.url] = 0
        }
        rerank()
        VpnLog.w("DoH ranking: " + ranked.take(5).joinToString { "${it.name}=${latency[it.url]?.let { l -> if (l == Long.MAX_VALUE) "fail" else "${l}ms" }}" })
    }

    // ---- DNS wire helpers ----------------------------------------------------------------------
    private fun reachable(ip: String, port: Int = 443, timeoutMs: Int = 1500): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), timeoutMs); true }
    } catch (_: Exception) { false }

    private fun isBogon(ip: String): Boolean {
        val p = ip.split(".").mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return true
        return p[0] == 0 || p[0] == 10 || p[0] == 127 || p[0] >= 224 ||
            (p[0] == 169 && p[1] == 254) || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168) ||
            (p[0] == 100 && p[1] in 64..127)
    }

    private fun skipName(b: ByteArray, start: Int): Int {
        var o = start
        while (o < b.size) {
            val c = b[o].toInt() and 0xFF
            if (c == 0) return o + 1
            if ((c and 0xC0) == 0xC0) return o + 2
            o += 1 + c
        }
        return -1
    }

    private fun parseA(b: ByteArray): List<String> {
        val out = ArrayList<String>()
        if (b.size < 12) return out
        val qd = ((b[4].toInt() and 0xFF) shl 8) or (b[5].toInt() and 0xFF)
        val an = ((b[6].toInt() and 0xFF) shl 8) or (b[7].toInt() and 0xFF)
        var o = 12
        repeat(qd) { o = skipName(b, o); if (o < 0) return out; o += 4 }
        repeat(an) {
            o = skipName(b, o); if (o < 0 || o + 10 > b.size) return out
            val type = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
            val rdlen = ((b[o + 8].toInt() and 0xFF) shl 8) or (b[o + 9].toInt() and 0xFF)
            o += 10
            if (o + rdlen > b.size) return out
            if (type == 1 && rdlen == 4) out += (0 until 4).joinToString(".") { (b[o + it].toInt() and 0xFF).toString() }
            o += rdlen
        }
        return out
    }

    /** A fresh response for query [q] that contains only the given IPv4 addresses. */
    private fun buildAnswer(q: ByteArray, ips: List<String>): ByteArray {
        var qEnd = skipName(q, 12)
        qEnd = if (qEnd < 0) q.size else minOf(qEnd + 4, q.size)
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(q[0], q[1], 0x81.toByte(), 0x80.toByte(), 0, 1, 0, ips.size.toByte(), 0, 0, 0, 0))
        out.write(q, 12, qEnd - 12)
        for (ip in ips) {
            out.write(byteArrayOf(0xC0.toByte(), 0x0C, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4))
            ip.split(".").forEach { out.write(it.toInt()) }
        }
        return out.toByteArray()
    }

    private fun qname(q: ByteArray): String {
        val sb = StringBuilder(); var o = 12
        while (o < q.size && q[o].toInt() != 0) {
            val n = q[o].toInt() and 0x3F
            if (o + 1 + n > q.size) break
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(q, o + 1, n)); o += 1 + n
        }
        return sb.toString()
    }

    private fun servfail(q: ByteArray): ByteArray {
        val a = q.copyOf(); if (a.size >= 4) { a[2] = (a[2].toInt() or 0x80).toByte(); a[3] = ((a[3].toInt() and 0xF0) or 2).toByte() }
        return a
    }
}
