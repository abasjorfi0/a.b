package app.zapret.mobile

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick-settings tile (the panel with Wi-Fi / Bluetooth): tap to start or stop. */
class KonataTile : TileService() {
    override fun onStartListening() { refresh() }

    private fun refresh() {
        val t = qsTile ?: return
        val on = VpnControl.isRunning(this)
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Konata"
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = if (on) "On" else "Off"
        t.updateTile()
    }

    override fun onClick() {
        if (VpnControl.isRunning(this)) { VpnControl.stop(this); return }
        if (VpnService.prepare(this) != null) {
            // VPN permission not granted yet: open the app once so the user can accept it.
            val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34)
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
            else @Suppress("DEPRECATION") startActivityAndCollapse(i)
            return
        }
        VpnControl.start(this)
        qsTile?.let { it.state = Tile.STATE_ACTIVE; it.updateTile() }
    }
}
