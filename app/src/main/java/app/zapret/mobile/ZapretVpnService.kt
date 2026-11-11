package app.zapret.mobile

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import hev.htproxy.TProxyService
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Non-root pipeline:   apps -> TUN -> hev-socks5-tunnel -> 127.0.0.1:1080 (byedpi, applies the strategy) -> internet
 * DNS queries go to hev's mapped-DNS (198.18.0.2), which hands byedpi *domain names*; byedpi resolves them
 * through DnsEngine (plain/DoH/auto). This service lives in the ":vpn" process and kills it on stop.
 */
class ZapretVpnService : VpnService() {
    companion object {
        const val ACTION_START = "app.zapret.mobile.START"
        const val ACTION_STOP = "app.zapret.mobile.STOP"
        const val EXTRA_CONFIG = "config"
        const val ACTION_STATE = "app.zapret.mobile.STATE"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_ERROR = "error"
        private const val SOCKS_PORT = 1080
        private const val TAG = "zapret-vpn"
    }

    private var tun: ParcelFileDescriptor? = null
    private var dns: DnsEngine? = null
    private val tproxy by lazy { TProxyService() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { shutdown(); return START_NOT_STICKY }
        val cfg = intent?.getStringExtra(EXTRA_CONFIG)
        if (cfg == null) { shutdown(); return START_NOT_STICKY }
        Thread { start(JSONObject(cfg)) }.start()
        return START_NOT_STICKY
    }

    private fun start(cfg: JSONObject) {
        try {
            val dnsMode = DnsMode.valueOf(cfg.getString("dnsMode"))
            dns = DnsEngine(dnsMode, cfg.getString("dohUrl"), cfg.getString("plainDns")).also { it.start() }

            val strategy = ArgParser.tokenize(cfg.getString("strategy"))
            val args = listOf("ciadpi", "--ip", "127.0.0.1", "--port", SOCKS_PORT.toString()) + strategy
            Thread({ Native.runProxy(args.toTypedArray()); Log.w(TAG, "proxy thread ended") }, "byedpi").start()
            if (!waitForPort(SOCKS_PORT)) error("proxy did not start - check the strategy syntax")

            val b = Builder().setSession("Zapret Mobile").setMtu(1500)
                .addAddress("198.18.0.1", 32).addRoute("0.0.0.0", 0).addDnsServer("198.18.0.2")
            val apps = cfg.getJSONArray("apps").let { a -> (0 until a.length()).map { a.getString(it) } }
            if (cfg.getString("appMode") == AppMode.ONLY.name) {
                // Only the chosen apps go through the tunnel. Our own process stays out automatically.
                apps.filter { it != packageName }.forEach { runCatching { b.addAllowedApplication(it) } }
            } else {
                // Everything except the chosen apps (and always except ourselves, so DoH/proxy sockets go out directly).
                (apps + packageName).distinct().forEach { runCatching { b.addDisallowedApplication(it) } }
            }
            tun = b.establish() ?: error("VPN permission was not granted")

            val yaml = File(filesDir, "tproxy.yml")
            yaml.writeText(
                """
                tunnel:
                  mtu: 1500
                  ipv4: 198.18.0.1
                socks5:
                  port: $SOCKS_PORT
                  address: 127.0.0.1
                  udp: 'udp'
                mapdns:
                  address: 198.18.0.2
                  port: 53
                  network: 100.64.0.0
                  netmask: 255.192.0.0
                  cache-size: 10000
                misc:
                  log-level: warn
                """.trimIndent()
            )
            if (!tproxy.TProxyStartService(yaml.absolutePath, tun!!.fd)) error("tunnel failed to start")
            broadcast(true, null)
        } catch (e: Throwable) {
            Log.e(TAG, "start failed", e)
            broadcast(false, e.message)
            shutdown()
        }
    }

    private fun waitForPort(port: Int): Boolean {
        repeat(40) {
            try { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200); return true } }
            catch (_: Exception) { Thread.sleep(100) }
        }
        return false
    }

    private fun shutdown() {
        runCatching { tproxy.TProxyStopService() }
        runCatching { dns?.stop() }
        runCatching { tun?.close() }
        broadcast(false, null)
        stopSelf()
        // byedpi cannot be restarted in-process (global state) - end this process; the next start gets a fresh one.
        Thread { Thread.sleep(300); Process.killProcess(Process.myPid()) }.start()
    }

    override fun onRevoke() { shutdown() }

    private fun broadcast(running: Boolean, err: String?) {
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).putExtra(EXTRA_RUNNING, running).putExtra(EXTRA_ERROR, err))
    }
}
