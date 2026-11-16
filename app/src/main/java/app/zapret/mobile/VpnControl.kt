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

    fun start(ctx: Context) {
        val store = Store(ctx)
        val s = store.settings()
        val st = store.find(s.strategyId)
        ContextCompat.startForegroundService(
            ctx,
            Intent(ctx, ZapretVpnService::class.java).setAction(ZapretVpnService.ACTION_START)
                .putExtra(ZapretVpnService.EXTRA_CONFIG, s.toJson(st.args)),
        )
    }

    fun stop(ctx: Context) {
        ctx.startService(Intent(ctx, ZapretVpnService::class.java).setAction(ZapretVpnService.ACTION_STOP))
    }
}
