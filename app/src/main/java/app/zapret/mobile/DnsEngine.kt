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
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local DNS engine on 127.0.0.1:PORT. byedpi's resolver (zp_resolver.c) sends it wire-format queries.
 *
 * Modes: SYSTEM = plain UDP DNS, DOH = one chosen DoH endpoint, AUTO = behaves like dnscrypt-proxy's
 * load balancing: resolvers are ranked by measured latency, every query is raced across the best two
 * (a third and fourth wave on failure), the winner is remembered per domain, failures are penalised,
 * and the ranking is refreshed in the background every few minutes.
 *
 * DoH requests go out directly (this :vpn process is excluded from the VPN). They must NOT go through the
 * local byedpi proxy: byedpi resolves names synchronously through this engine, so a request that waits for
 * byedpi while byedpi waits for us would stall until timeout. Answers are cached to keep lookups fast.
 */
class DnsEngine(
    private val mode: DnsMode, private val dohUrl: String, private val plainDns: String,
    private val all: List<DohResolver>,
) {
    companion object {
        const val PORT = 5300
        private val DNS_MSG = "application/dns-message".toMediaType()
        private const val PENALTY_MS = 1500L
    }

    private val resolvers: List<DohResolver> =
        all + if (all.none { it.url == dohUrl }) listOf(DohResolver("custom", dohUrl, emptyList())) else emptyList()
    private val byUrl = resolvers.associateBy { it.url }
    private val ipsByHost: Map<String, List<String>> =
        resolvers.groupBy { it.url.substringAfter("//").substringBefore("/").substringBefore(":") }
            .mapValues { e -> e.value.flatMap { it.ips }.distinct() }

    // Resolver hostnames map to their bootstrap IPs, so no DNS lookup is needed to reach a resolver.
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
    private val logged = AtomicInteger(0)

    // ---- AUTO state ----------------------------------------------------------------------------
    private val latency = ConcurrentHashMap<String, Long>()      // url -> last probe latency (ms), MAX = failed
    private val penalty = ConcurrentHashMap<String, Int>()       // url -> recent failures
    private val preferred = ConcurrentHashMap<String, String>()  // domain -> url that last answered it
    @Volatile private var ranked: List<DohResolver> = Resolvers.builtin.mapNotNull { byUrl[it.url] }

    private fun score(r: DohResolver): Long {
        val l = latency[r.url] ?: 800L            // unknown: assume mediocre, so tested ones win
        return (if (l == Long.MAX_VALUE) 60_000L else l) + (penalty[r.url] ?: 0) * PENALTY_MS
    }

    private fun rerank() { ranked = latency.keys.mapNotNull { byUrl[it] }.sortedBy { score(it) } }

    /** AUTO probes the favorites plus a random sample of the rest (probing hundreds at once would be wasteful). */
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
                    val name = qname(q)
                    val a = resolve(q, name)
                    if (logged.incrementAndGet() <= 25 || a == null) VpnLog.w("DNS $name -> ${if (a == null) "FAILED" else "ok"}")
                    val out = a ?: servfail(q)
                    runCatching { s.send(DatagramPacket(out, out.size, from)) }
                }
            }
        }
    }

    fun stop() { sock?.close(); pool.shutdownNow() }

    // ---- resolving -----------------------------------------------------------------------------
    private class Cached(val data: ByteArray, val expires: Long)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val cacheMs = 5 * 60 * 1000L

    private fun resolve(q: ByteArray, name: String): ByteArray? {
        if (q.size < 13) return resolveUncached(q, name)
        val key = String(q, 12, q.size - 12, Charsets.ISO_8859_1)
        cache[key]?.let { c ->
            if (c.expires > System.currentTimeMillis()) {
                val a = c.data.copyOf(); a[0] = q[0]; a[1] = q[1]; return a
            }
            cache.remove(key)
        }
        val a = resolveUncached(q, name) ?: return null
        val ok = a.size > 12 && (a[3].toInt() and 0x0F) == 0 && ((a[6].toInt() shl 8) or (a[7].toInt() and 0xFF)) != 0
        if (ok) cache[key] = Cached(a, System.currentTimeMillis() + cacheMs)
        return a
    }

    private fun resolveUncached(q: ByteArray, name: String): ByteArray? = when (mode) {
        DnsMode.SYSTEM -> plain(q)
        DnsMode.DOH -> byUrl[dohUrl]?.let { doh(it, q) } ?: plain(q)
        DnsMode.AUTO -> autoResolve(q, name.lowercase())
    }

    private fun autoResolve(q: ByteArray, name: String): ByteArray? {
        val order = ArrayList<DohResolver>()
        preferred[name]?.let { u -> byUrl[u]?.let { order += it } }
        ranked.forEach { if (it !in order) order += it }
        if (order.isEmpty()) order += Resolvers.builtin.mapNotNull { byUrl[it.url] }
        // wave 1: race the best two, wave 2: next three, wave 3: a random sample of the rest
        race(order.take(2), q, name)?.let { return it }
        race(order.drop(2).take(3), q, name)?.let { return it }
        pool.execute { probeAll() }
        race(candidates().filter { it !in order.take(5) }.take(4), q, name)?.let { return it }
        return plain(q)
    }

    /** Ask all of [rs] at once; the first valid answer wins, the others are penalised only if they failed. */
    private fun race(rs: List<DohResolver>, q: ByteArray, name: String): ByteArray? {
        if (rs.isEmpty()) return null
        val ecs = ExecutorCompletionService<Triple<DohResolver, ByteArray?, Long>>(pool)
        rs.forEach { r -> ecs.submit(Callable { val t0 = System.nanoTime(); Triple(r, doh(r, q), (System.nanoTime() - t0) / 1_000_000) }) }
        repeat(rs.size) {
            val (r, a, ms) = try { ecs.take().get() } catch (_: Exception) { return null }
            if (a != null) {
                preferred[name] = r.url
                penalty[r.url] = 0
                latency[r.url] = ms
                if (preferred.size > 3000) preferred.clear()
                rerank()
                return a
            }
            penalty.merge(r.url, 1) { x, y -> minOf(x + y, 6) }
            rerank()
        }
        return null
    }

    private fun doh(r: DohResolver, q: ByteArray): ByteArray? = try {
        val req = Request.Builder().url(r.url).header("Accept", "application/dns-message")
            .post(q.toRequestBody(DNS_MSG)).build()
        direct.newCall(req).execute().use { if (it.isSuccessful) it.body?.bytes() else null }
    } catch (e: Exception) {
        if (logged.get() < 25) VpnLog.w("DoH ${r.name} failed: ${e.javaClass.simpleName} ${e.message?.take(60)}")
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
