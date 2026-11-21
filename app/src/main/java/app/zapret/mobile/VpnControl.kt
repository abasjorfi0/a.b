package app.zapret.mobile

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import java.io.File

/** Start/stop/state helpers shared by the app screen and the quick-settings tile. */
object VpnControl {
    fun isRunning(ctx: Context): Boolean {
        val alive = (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).runningAppProcesses
            ?.any { it.processName == "${ctx.packageName}:vpn" } == true
        return alive && File(ctx.filesDir, "running").exists()
    }

    /** Returns false (and tells the user) when the settings would make the VPN capture everything by mistake. */
    fun start(ctx: Context): Boolean {
        val store = Store(ctx)
        val s = store.settings()
        if (s.appMode == AppMode.ONLY && s.apps.isEmpty()) {
            android.widget.Toast.makeText(ctx, "Apps tab: 'Only checked' needs at least one app", android.widget.Toast.LENGTH_LONG).show()
            return false
        }
        val st = store.find(s.strategyId)
        ContextCompat.startForegroundService(
            ctx,
            Intent(ctx, ZapretVpnService::class.java).setAction(ZapretVpnService.ACTION_START)
                .putExtra(ZapretVpnService.EXTRA_CONFIG, s.toJson(st.args)),
        )
        return true
    }

    fun stop(ctx: Context) {
        ctx.startService(Intent(ctx, ZapretVpnService::class.java).setAction(ZapretVpnService.ACTION_STOP))
    }
}
