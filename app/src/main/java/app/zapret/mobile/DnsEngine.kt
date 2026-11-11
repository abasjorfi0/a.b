package app.zapret.mobile

import android.util.Log
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Local DNS engine on 127.0.0.1:PORT. byedpi's resolver (zp_resolver.c) sends it wire-format queries.
 * Modes: SYSTEM = plain UDP DNS to a chosen server, DOH = one DoH endpoint, AUTO = fastest working DoH
 * (probed at start, re-probed when everything fails), with plain DNS as the last resort.
 * Runs in the :vpn process, which is excluded from the VPN, so these sockets go out directly.
 */
class DnsEngine(private val mode: DnsMode, private val dohUrl: String, private val plainDns: String) {
    companion object {
        const val PORT = 5300
        private const val TAG = "zapret-dns"
        private val DNS_MSG = "application/dns-message".toMediaType()
    }

    private val resolvers: List<DohResolver> =
        Resolvers.builtin + if (Resolvers.builtin.none { it.url == dohUrl }) listOf(DohResolver("custom", dohUrl, emptyList())) else emptyList()

    private val bootstrap = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val ips = resolvers.firstOrNull { it.url.contains("//$hostname/") }?.ips.orEmpty()
            return if (ips.isNotEmpty()) ips.map { InetAddress.getByName(it) } else Dns.SYSTEM.lookup(hostname)
        }
    }

    private val http = OkHttpClient.Builder()
        .dns(bootstrap)
        .connectTimeout(4, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS)
        .build()

    private val pool = Executors.newCachedThreadPool()
    @Volatile private var sock: DatagramSocket? = null
    @Volatile private var ranked: List<DohResolver> = resolvers
    @Volatile var currentName: String = "-"; private set

    fun start() {
        val s = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT)) }
        sock = s
        if (mode == DnsMode.AUTO) pool.execute { probeAll() }
        pool.execute {
            val buf = ByteArray(1500)
            while (!s.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (_: Exception) { break }
                val q = p.data.copyOf(p.length); val from = p.socketAddress
                pool.execute {
                    val a = resolve(q) ?: servfail(q)
                    runCatching { s.send(DatagramPacket(a, a.size, from)) }
                }
            }
        }
    }

    fun stop() { sock?.close(); pool.shutdownNow() }

    private fun resolve(q: ByteArray): ByteArray? = when (mode) {
        DnsMode.SYSTEM -> plain(q)
        DnsMode.DOH -> doh(resolvers.first { it.url == dohUrl }, q) ?: plain(q)
        DnsMode.AUTO -> {
            var ans: ByteArray? = null
            for (r in ranked) { ans = doh(r, q); if (ans != null) { currentName = r.name; break } }
            if (ans == null) { probeAll(); ans = plain(q) }
            ans
        }
    }

    private fun doh(r: DohResolver, q: ByteArray): ByteArray? = try {
        val req = Request.Builder().url(r.url).header("Accept", "application/dns-message")
            .post(q.toRequestBody(DNS_MSG)).build()
        http.newCall(req).execute().use { if (it.isSuccessful) it.body?.bytes() else null }
    } catch (e: Exception) { Log.w(TAG, "doh ${r.name}: ${e.message}"); null }

    private fun plain(q: ByteArray): ByteArray? = try {
        DatagramSocket().use { s ->
            s.soTimeout = 3000
            s.send(DatagramPacket(q, q.size, InetAddress.getByName(plainDns), 53))
            val b = ByteArray(1500); val p = DatagramPacket(b, b.size)
            s.receive(p); p.data.copyOf(p.length)
        }
    } catch (e: Exception) { null }

    /** Probe every resolver with a tiny A query and rank by latency (failures last). */
    private fun probeAll() {
        val q = byteArrayOf(0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(),
            'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(), 3, 'c'.code.toByte(), 'o'.code.toByte(),
            'm'.code.toByte(), 0, 0, 1, 0, 1)
        val timings = resolvers.map { r ->
            val t0 = System.nanoTime()
            val ok = doh(r, q) != null
            r to (if (ok) System.nanoTime() - t0 else Long.MAX_VALUE)
        }
        ranked = timings.sortedBy { it.second }.map { it.first }
        currentName = timings.minByOrNull { it.second }?.takeIf { it.second != Long.MAX_VALUE }?.first?.name ?: "none"
        Log.i(TAG, "ranked: " + timings.joinToString { "${it.first.name}=${if (it.second == Long.MAX_VALUE) "fail" else (it.second / 1_000_000).toString() + "ms"}" })
    }

    private fun servfail(q: ByteArray): ByteArray {
        val a = q.copyOf(); if (a.size >= 4) { a[2] = (a[2].toInt() or 0x80).toByte(); a[3] = ((a[3].toInt() and 0xF0) or 2).toByte() }
        return a
    }
}
