package app.zapret.mobile

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tiny shared log: the :vpn process appends, the UI process reads (shown on the Home tab). */
object VpnLog {
    @Volatile private var file: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun init(dir: File, reset: Boolean) {
        file = File(dir, "vpn.log").also { if (reset) runCatching { it.writeText("") } }
    }

    @Synchronized
    fun w(msg: String) {
        Log.i("zapret", msg)
        runCatching { file?.let { if (it.length() > 1_500_000) it.writeText("") } }
        runCatching { file?.appendText("${fmt.format(Date())} $msg\n") }
    }

    fun read(dir: File, lines: Int = 16): String =
        runCatching { File(dir, "vpn.log").readLines().takeLast(lines).joinToString("\n") }.getOrDefault("")
}
