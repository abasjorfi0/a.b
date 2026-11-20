package app.zapret.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService

/** Starts Konata after the phone boots, if "Start on boot" is on and the VPN permission was already granted. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Store(c).settings().autoStart && VpnService.prepare(c) == null) runCatching { VpnControl.start(c) }
    }
}
