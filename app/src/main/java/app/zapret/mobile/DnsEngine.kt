package app.zapret.mobile

import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
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
 * Modes: SYSTEM = plain UDP DNS, DOH = one chosen DoH endpoint, AUTO = "smart" selection whose goal is an
 * address that actually works (the usual failure is a CDN edge address that is blackholed from Iran):
 *  - candidates: the best DoH resolvers (latency-ranked, raced), the phone's own ISP resolver, then the same
 *    name asked with EDNS client-subnet hints from other countries (different CDN edges), then random resolvers;
 *  - an answer is good only if its IPv4 addresses are real (no 0.0.0.0 / private block-page IPs / banned IPs)
 *    AND one of them accepts a TCP connection on 443, 80 or 8443 (checked in parallel);
 *  - the answer handed to byedpi contains only reachable addresses;
 *  - feedback: when byedpi reports "unreach ip" (every strategy group failed for that address) the address is
 *    banned for 10 minutes and removed from the cache, so the next attempt resolves to a different edge;
 *  - what worked is remembered per domain; failures are penalised; rankings refresh in the background.
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
        private const val TTL_WEAK = 2 * 60_000L
        private const val BUDGET_MS = 2500L
        private const val HARD_MS = 4200L
        private const val BAN_MS = 10 * 60_000L
        private val PORTS = intArrayOf(443, 80, 8443)
        // /24 client-subnet hints (US, FR, TR, AE, DE, DE): CDNs answer with edges near the hinted client.
        private val ECS = listOf(intArrayOf(8, 8, 8), intArrayOf(2, 2, 2), intArrayOf(85, 105, 0),
            intArrayOf(94, 200, 200), intArrayOf(5, 1, 66), intArrayOf(80, 187, 0))
        private const val GOOGLE = "https://dns.google/dns-query"
    }

    private val resolvers: List<DohResolver> =
        all + if (all.none { it.url == dohUrl }) listOf(DohResolver("custom", dohUrl, emptyList())) else emptyList()
    private val byUrl = resolvers.associateBy { it.url }
    private val ipsByHost: Map<String, List<String>> =
        resolvers.groupBy { it.url.substringAfter("//").substringBefore("/").substringBefore(":") }
            .mapValues { e -> e.value.flatMap { it.ips }.distinct() }
    private val isp = DohResolver("ISP-DNS", "isp://system", emptyList())

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
    private val ecsFor = ConcurrentHashMap<String, Int>()          // domain -> ECS hint index that gave a working edge
    private val banned = ConcurrentHashMap<String, Long>()         // ip -> ban expiry
    private val reach = ConcurrentHashMap<String, Pair<Boolean, Long>>()
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

    // ---- feedback from byedpi ------------------------------------------------------------------
    /** byedpi gave up on [ip] (all strategy groups failed): avoid it and forget cached answers that contain it. */
    fun ban(ip: String) {
        banned[ip] = System.currentTimeMillis() + BAN_MS
        reach.remove(ip)
        cache.entries.removeIf { e -> parseA(e.value.data).contains(ip) }
        VpnLog.w("DNS: banned $ip for 10 min (every strategy failed on it); next lookups pick another address")
    }

    private fun isBanned(ip: String): Boolean {
        val t = banned[ip] ?: return false
        if (t < System.currentTimeMillis()) { banned.remove(ip); return false }
        return true
    }

    // ---- self-test (DNS tab button; works with or without the VPN running) ----------------------
    private fun buildQuery(name: String): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(byteArrayOf(0x4A, 0x11, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        name.split(".").forEach { o.write(it.length); o.write(it.toByteArray()) }
        o.write(byteArrayOf(0, 0, 1, 0, 1)); return o.toByteArray()
    }

    fun selfTest(names: List<String>): String {
        val sb = StringBuilder()
        probeAll()
        sb.append("Ranking: ").append(ranked.take(6).joinToString { "${it.name} ${latency[it.url]?.let { l -> if (l == Long.MAX_VALUE) "fail" else "${l}ms" }}" }).append("\n")
        for (n in names) {
            sb.append("\n== ").append(n).append("\n")
            val q = buildQuery(n)
            val jobs = (ranked.take(5).map { r -> r.name to Callable<Cand?> { fetchValidated(r, q) } } +
                listOf("ISP-DNS" to Callable<Cand?> { fetchSystem(q, n) }) +
                ECS.indices.take(3).map { i -> "Google ecs#$i" to Callable<Cand?> { byUrl[GOOGLE]?.let { fetchValidated(it, q, i) } } })
                .map { (l, c) -> l to pool.submit(c) }
            for ((l, f) in jobs) {
                val c = runCatching { f.get(6, TimeUnit.SECONDS) }.getOrNull()
                val st = when (c?.quality) { 2 -> "OK"; 1 -> "unreachable"; 0 -> "bogus/banned"; 3 -> "empty"; else -> "failed" }
                sb.append(String.format("  %-18s %-12s %s\n", l, st, c?.ips?.take(3)?.joinToString(",") ?: ""))
            }
            val t0 = System.currentTimeMillis()
            val r = autoResolve(q, n)
            sb.append("  => chosen: ").append(r?.let { parseA(it.data).joinToString(",") } ?: "none")
                .append(" (").append(System.currentTimeMillis() - t0).append(" ms)\n")
        }
        return sb.toString()
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

    private class Cand(val r: DohResolver, val answer: ByteArray, val quality: Int, val ms: Long, val ips: List<String>, val ecs: Int = -1)

    /**
     * quality 3 = NXDOMAIN / empty answer (trusted as is), 2 = has a reachable address (answer rewritten to
     * reachable ones only), 1 = real addresses but none reachable, 0 = bogus (0.0.0.0, private, banned...).
     */
    private fun assess(r: DohResolver, q: ByteArray, a: ByteArray, ips: List<String>, ms: Long, ecs: Int): Cand {
        if (a.size < 12) return Cand(r, a, 0, ms, emptyList(), ecs)
        val rcode = a[3].toInt() and 0x0F
        if (ips.isEmpty()) return if (rcode == 0 || rcode == 3) Cand(r, a, 3, ms, ips, ecs) else Cand(r, a, 0, ms, ips, ecs)
        val good = ips.filterNot { isBogon(it) || isBanned(it) }
        if (good.isEmpty()) return Cand(r, a, 0, ms, ips, ecs)
        val ok = reachableSubset(good.take(4)).take(3)
        return if (ok.isNotEmpty()) Cand(r, buildAnswer(q, ok), 2, ms, ok, ecs) else Cand(r, a, 1, ms, good, ecs)
    }

    private fun fetchValidated(r: DohResolver, q: ByteArray, ecs: Int = -1): Cand? {
        val t0 = System.nanoTime()
        val query = if (ecs >= 0) withEcs(q, ecs) else q
        val a = doh(r, query) ?: return null
        return assess(r, q, a, parseA(a), (System.nanoTime() - t0) / 1_000_000, ecs)
    }

    /** The phone's own (ISP) resolver, used from this VPN-excluded process; poisoned answers are filtered as bogons. */
    private fun fetchSystem(q: ByteArray, name: String): Cand? {
        val t0 = System.nanoTime()
        val ips = try {
            InetAddress.getAllByName(name).filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
        } catch (_: Exception) { return null }
        if (ips.isEmpty()) return null
        return assess(isp, q, buildAnswer(q, ips), ips, (System.nanoTime() - t0) / 1_000_000, -1)
    }

    private fun autoResolve(q: ByteArray, name: String): Res? {
        val t0 = System.currentTimeMillis()
        val order = ArrayList<DohResolver>()
        preferred[name]?.let { u -> byUrl[u]?.let { order += it } }
        ranked.forEach { if (it !in order) order += it }
        if (order.isEmpty()) order += Resolvers.builtin.mapNotNull { byUrl[it.url] }
        val google = byUrl[GOOGLE]

        val waves = ArrayList<List<Callable<Cand?>>>()
        val w1 = ArrayList<Callable<Cand?>>()
        ecsFor[name]?.let { idx -> google?.let { g -> w1 += Callable { fetchValidated(g, q, idx) } } }
        order.take(2).forEach { r -> w1 += Callable { fetchValidated(r, q) } }
        w1 += Callable { fetchSystem(q, name) }
        waves += w1
        waves += order.drop(2).take(3).map { r -> Callable<Cand?> { fetchValidated(r, q) } }
        if (google != null) waves += ECS.indices.shuffled().take(4).map { i -> Callable<Cand?> { fetchValidated(google, q, i) } }
        waves += candidates().filter { it !in order.take(5) }.take(4).map { r -> Callable<Cand?> { fetchValidated(r, q) } }

        var weak: Cand? = null
        for ((i, wave) in waves.withIndex()) {
            if (wave.isEmpty()) continue
            val spent = System.currentTimeMillis() - t0
            if (i > 0 && (spent > HARD_MS || (weak != null && spent > BUDGET_MS))) break
            if (i == waves.size - 1) pool.execute { probeAll() }
            val ecs = ExecutorCompletionService<Cand?>(pool)
            wave.forEach { ecs.submit(it) }
            repeat(wave.size) {
                val left = HARD_MS + 500 - (System.currentTimeMillis() - t0)
                val c = try { ecs.poll(maxOf(left, 1L), TimeUnit.MILLISECONDS)?.get() } catch (_: Exception) { null }
                if (c == null) return@repeat
                if (c.quality >= 2) {
                    if (c.r !== isp) { preferred[name] = c.r.url; penalty[c.r.url] = 0; latency[c.r.url] = c.ms; rerank() }
                    if (c.ecs >= 0) ecsFor[name] = c.ecs
                    VpnLog.w("DNS $name -> ${c.ips.firstOrNull() ?: "(none)"} via ${c.r.name}${if (c.ecs >= 0) " (ecs#${c.ecs})" else ""} ${c.ms}ms ${if (c.quality == 2) "reachable" else "empty"}")
                    return Res(c.answer, TTL_OK)
                }
                if (c.quality == 0 && c.r !== isp) penalty.merge(c.r.url, 1) { x, y -> minOf(x + y, 6) }
                if (c.quality == 1 && weak == null) weak = c
            }
            rerank()
        }
        weak?.let { return finishWeak(name, it) }
        plain(q)?.let { VpnLog.w("DNS $name -> plain DNS fallback"); return Res(it, TTL_WEAK) }
        return null
    }

    private fun finishWeak(name: String, w: Cand): Res {
        VpnLog.w("DNS $name -> ${w.ips.firstOrNull()} via ${w.r.name} (NOT reachable on 443/80/8443 from any resolver or hint tried)")
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

    // ---- reachability --------------------------------------------------------------------------
    private fun connectOk(ip: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), 1200); true }
    } catch (_: Exception) { false }

    /** True if any of the ports accepts a TCP connection (all ports tried in parallel); results are cached. */
    private fun reachable(ip: String): Boolean {
        val now = System.currentTimeMillis()
        reach[ip]?.let { (ok, exp) -> if (exp > now) return ok }
        val ecs = ExecutorCompletionService<Boolean>(pool)
        PORTS.forEach { p -> ecs.submit(Callable { connectOk(ip, p) }) }
        var ok = false
        repeat(PORTS.size) { if (!ok && runCatching { ecs.take().get() }.getOrDefault(false)) ok = true }
        reach[ip] = ok to (now + if (ok) TTL_OK else 60_000L)
        return ok
    }

    /** The reachable subset of [ips], checked in parallel, original order kept. */
    private fun reachableSubset(ips: List<String>): List<String> {
        val futures = ips.map { ip -> ip to pool.submit(Callable { reachable(ip) }) }
        return futures.filter { (_, f) -> runCatching { f.get() }.getOrDefault(false) }.map { it.first }
    }

    private fun isBogon(ip: String): Boolean {
        val p = ip.split(".").mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return true
        return p[0] == 0 || p[0] == 10 || p[0] == 127 || p[0] >= 224 ||
            (p[0] == 169 && p[1] == 254) || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168) ||
            (p[0] == 100 && p[1] in 64..127)
    }

    // ---- DNS wire helpers ----------------------------------------------------------------------
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
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(q[0], q[1], 0x81.toByte(), 0x80.toByte(), 0, 1, 0, ips.size.toByte(), 0, 0, 0, 0))
        out.write(q, 12, qEnd - 12)
        for (ip in ips) {
            out.write(byteArrayOf(0xC0.toByte(), 0x0C, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4))
            ip.split(".").forEach { out.write(it.toInt()) }
        }
        return out.toByteArray()
    }

    /** [q] plus an EDNS0 client-subnet option (/24) so CDN-aware resolvers answer with edges near that subnet. */
    private fun withEcs(q: ByteArray, idx: Int): ByteArray {
        val s = ECS[idx]
        val b = q.copyOf(); b[10] = 0; b[11] = 1
        val out = ByteArrayOutputStream()
        out.write(b)
        out.write(byteArrayOf(0, 0, 41, 0x10, 0, 0, 0, 0, 0, 0, 11, 0, 8, 0, 7, 0, 1, 24, 0, s[0].toByte(), s[1].toByte(), s[2].toByte()))
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
