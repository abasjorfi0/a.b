package app.zapret.mobile

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Cloudflare clean-IP scanner: tries random addresses from Cloudflare's public IPv4 ranges with a real HTTPS request
 * (/cdn-cgi/trace, proper SNI + certificate check) and keeps the ones that answer, fastest first.
 */
object CfScanner {
    private val ranges = listOf(
        "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22", "141.101.64.0/18", "108.162.192.0/18",
        "190.93.240.0/20", "188.114.96.0/20", "197.234.240.0/22", "198.41.128.0/17", "162.158.0.0/15", "104.16.0.0/13",
        "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
    )

    private fun randomIp(): String {
        // pick a range weighted by its size so big blocks are sampled more
        val parsed = ranges.map { r -> val (a, p) = r.split('/'); val b = a.split('.').map { it.toLong() }
            Triple((b[0] shl 24) or (b[1] shl 16) or (b[2] shl 8) or b[3], 1L shl (32 - p.toInt()), r) }
        var pick = Random.nextLong(parsed.sumOf { it.second })
        for ((base, size, _) in parsed) {
            if (pick < size) { val v = base + 1 + Random.nextLong(size - 2); return "${v shr 24 and 255}.${v shr 16 and 255}.${v shr 8 and 255}.${v and 255}" }
            pick -= size
        }
        return "104.16.0.1"
    }

    /** Returns (ip, milliseconds) of the working addresses, fastest first. */
    fun scan(sni: String, count: Int, progress: (done: Int, found: Int) -> Unit): List<Pair<String, Long>> {
        val base = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
        val ips = generateSequence { randomIp() }.distinct().take(count).toList()
        val pool = Executors.newFixedThreadPool(24)
        val done = AtomicInteger(); val found = AtomicInteger()
        val futures = ips.map { ip ->
            pool.submit(Callable<Pair<String, Long>?> {
                val t0 = System.currentTimeMillis()
                val ok = try {
                    val c = base.newBuilder().dns(object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getByName(ip)) }).build()
                    c.newCall(Request.Builder().url("https://$sni/cdn-cgi/trace").build()).execute().use { r ->
                        r.isSuccessful && (r.body?.string() ?: "").contains("colo=")
                    }
                } catch (_: Exception) { false }
                val ms = System.currentTimeMillis() - t0
                if (ok) found.incrementAndGet()
                progress(done.incrementAndGet(), found.get())
                if (ok) ip to ms else null
            })
        }
        val out = futures.mapNotNull { runCatching { it.get() }.getOrNull() }.sortedBy { it.second }
        pool.shutdownNow()
        return out
    }
}
