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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local DNS engine on 127.0.0.1:PORT. byedpi's resolver (zp_resolver.c) sends it wire-format queries.
 * Modes: SYSTEM = plain UDP DNS to a chosen server, DOH = one DoH endpoint, AUTO = fastest working DoH
 * (probed at start, re-probed when everything fails), with plain DNS as the last resort.
 *
 * DoH requests go out directly (this :vpn process is excluded from the VPN). They must NOT go through the
 * local byedpi proxy: byedpi resolves names synchronously through this engine, so a request that waits for
 * byedpi while byedpi waits for us would stall until timeout. Answers are cached to keep lookups fast.
 */
class DnsEngine(private val mode: DnsMode, private val dohUrl: String, private val plainDns: String) {
    companion object {
        const val PORT = 5300
        private val DNS_MSG = "application/dns-message".toMediaType()
    }

    private val resolvers: List<DohResolver> =
        Resolvers.builtin + if (Resolvers.builtin.none { it.url == dohUrl }) listOf(DohResolver("custom", dohUrl, emptyList())) else emptyList()

    // Resolver hostnames map to their bootstrap IPs, so no DNS lookup is needed (and the SOCKS proxy only ever
    // receives IP addresses, never a name that would loop back into this engine).
    private val bootstrap = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val ips = resolvers.firstOrNull { it.url.contains("//$hostname/") }?.ips.orEmpty()
            return if (ips.isNotEmpty()) ips.map { InetAddress.getByName(it) } else Dns.SYSTEM.lookup(hostname)
        }
    }

    private val direct = OkHttpClient.Builder().dns(bootstrap)
        .connectTimeout(4, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).build()

    private val pool = Executors.newCachedThreadPool()
    @Volatile private var sock: DatagramSocket? = null
    @Volatile private var ranked: List<DohResolver> = resolvers
    private val logged = AtomicInteger(0)

    fun start() {
        val s = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT)) }
        sock = s
        VpnLog.w("DNS engine listening on 127.0.0.1:$PORT (mode=$mode)")
        if (mode == DnsMode.AUTO) pool.execute { probeAll() }
        pool.execute {
            val buf = ByteArray(1500)
            while (!s.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (_: Exception) { break }
                val q = p.data.copyOf(p.length); val from = p.socketAddress
                pool.execute {
                    val name = qname(q)
                    val a = resolve(q)
                    if (logged.incrementAndGet() <= 25 || a == null) VpnLog.w("DNS $name -> ${if (a == null) "FAILED" else "ok"}")
                    val out = a ?: servfail(q)
                    runCatching { s.send(DatagramPacket(out, out.size, from)) }
                }
            }
        }
    }

    fun stop() { sock?.close(); pool.shutdownNow() }

    private class Cached(val data: ByteArray, val expires: Long)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val cacheMs = 5 * 60 * 1000L

    private fun resolve(q: ByteArray): ByteArray? {
        if (q.size < 13) return resolveUncached(q)
        val key = String(q, 12, q.size - 12, Charsets.ISO_8859_1)
        cache[key]?.let { c ->
            if (c.expires > System.currentTimeMillis()) {
                val a = c.data.copyOf(); a[0] = q[0]; a[1] = q[1]; return a
            }
            cache.remove(key)
        }
        val a = resolveUncached(q) ?: return null
        val ok = a.size > 12 && (a[3].toInt() and 0x0F) == 0 && ((a[6].toInt() shl 8) or (a[7].toInt() and 0xFF)) != 0
        if (ok) cache[key] = Cached(a, System.currentTimeMillis() + cacheMs)
        return a
    }

    private fun resolveUncached(q: ByteArray): ByteArray? = when (mode) {
        DnsMode.SYSTEM -> plain(q)
        DnsMode.DOH -> doh(resolvers.first { it.url == dohUrl }, q) ?: plain(q)
        DnsMode.AUTO -> {
            var ans: ByteArray? = null
            for (r in ranked) { ans = doh(r, q); if (ans != null) break }
            if (ans == null) { probeAll(); ans = plain(q) }
            ans
        }
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

    /** Probe every resolver in parallel with a tiny A query and rank by latency (failures last). */
    private fun probeAll() {
        val q = byteArrayOf(0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(),
            'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(), 3, 'c'.code.toByte(), 'o'.code.toByte(),
            'm'.code.toByte(), 0, 0, 1, 0, 1)
        val futures = resolvers.map { r ->
            r to pool.submit(Callable {
                val t0 = System.nanoTime()
                if (doh(r, q) != null) System.nanoTime() - t0 else Long.MAX_VALUE
            })
        }
        val timings = futures.map { (r, f) -> r to runCatching { f.get() }.getOrDefault(Long.MAX_VALUE) }
        ranked = timings.sortedBy { it.second }.map { it.first }
        VpnLog.w("DoH probe: " + timings.joinToString { "${it.first.name}=${if (it.second == Long.MAX_VALUE) "fail" else (it.second / 1_000_000).toString() + "ms"}" })
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
