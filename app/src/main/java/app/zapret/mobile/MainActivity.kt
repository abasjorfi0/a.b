package app.zapret.mobile

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

private val Blue = Color(0xFF3F6FD8)
private val Green = Color(0xFF2FA36B)
private val Cream = Color(0xFFFFF6EC)

class MainActivity : ComponentActivity() {
    private var running by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            running = i.getBooleanExtra(ZapretVpnService.EXTRA_RUNNING, false)
            error = i.getStringExtra(ZapretVpnService.EXTRA_ERROR)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val filter = IntentFilter(ZapretVpnService.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(stateReceiver, filter)
        val store = Store(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent {
            val dark = isSystemInDarkTheme()
            val scheme = if (dark) darkColorScheme(
                primary = Color(0xFF8FB0FF), onPrimary = Color(0xFF0B1636), secondary = Color(0xFF5FD8A0),
                background = Color(0xFF0E1430), surface = Color(0xFF1A2347), surfaceVariant = Color(0xFF243060),
                onBackground = Color(0xFFEAF0FF), onSurface = Color(0xFFEAF0FF), onSurfaceVariant = Color(0xFFB8C6F0),
                primaryContainer = Color(0xFF2B3C7A), onPrimaryContainer = Color(0xFFDCE6FF),
            ) else lightColorScheme(
                primary = Blue, onPrimary = Color.White, secondary = Green,
                background = Cream, surface = Color.White, surfaceVariant = Color(0xFFE6EEFF),
                onBackground = Color(0xFF14204A), onSurface = Color(0xFF14204A), onSurfaceVariant = Color(0xFF4A5A8A),
                primaryContainer = Color(0xFFDCE6FF), onPrimaryContainer = Color(0xFF0E1E55),
            )
            MaterialTheme(colorScheme = scheme) {
                val bg = if (dark) listOf(Color(0xFF0E1430), Color(0xFF16204A)) else listOf(Color(0xFFD9E7FF), Cream)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(bg))) { App(store) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The VPN lives in its own process and keeps running when this screen is closed: re-detect it.
        running = VpnControl.isRunning(this)
    }

    override fun onDestroy() { unregisterReceiver(stateReceiver); super.onDestroy() }

    @Composable
    private fun App(store: Store) {
        var tab by remember { mutableIntStateOf(0) }
        var settings by remember { mutableStateOf(store.settings()) }
        var strategies by remember { mutableStateOf(store.allStrategies()) }
        fun update(s: Settings) { settings = s; store.saveSettings(s) }

        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.konata), null, Modifier.size(44.dp).clip(CircleShape).border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
                    contentScale = ContentScale.Crop)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Konata", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("DPI bypass + smart DNS", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(Modifier.size(12.dp).clip(CircleShape).background(if (running) Green else Color(0xFF9AA3BD)))
            }
            Box(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                when (tab) {
                    0 -> HomeTab(settings, strategies)
                    1 -> StrategiesTab(store, settings, strategies, { strategies = store.allStrategies() }, ::update)
                    2 -> DnsTab(settings, ::update)
                    else -> AppsTab(settings, ::update)
                }
            }
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                listOf("🏠" to "Home", "🧩" to "Strategy", "🌐" to "DNS", "📱" to "Apps").forEachIndexed { i, (ic, t) ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(ic, fontSize = 20.sp) }, label = { Text(t) })
                }
            }
        }
    }

    @Composable
    private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                content()
            }
        }
    }

    // ---------------------------------------------------------------- Home
    @Composable
    private fun HomeTab(s: Settings, strategies: List<Strategy>) {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == RESULT_OK) VpnControl.start(this)
        }
        val chosen = strategies.firstOrNull { it.id == s.strategyId } ?: strategies.first()
        var best by remember { mutableStateOf("") }
        LaunchedEffect(running) {
            while (true) {
                best = runCatching { File(filesDir, "dns-status.txt").readLines().firstOrNull().orEmpty() }.getOrDefault("")
                kotlinx.coroutines.delay(2000)
            }
        }
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = if (running) Green.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Image(painterResource(R.drawable.konata), null,
                        Modifier.size(130.dp).clip(CircleShape).border(4.dp, if (running) Green else MaterialTheme.colorScheme.primary, CircleShape),
                        contentScale = ContentScale.Crop)
                    Text(if (running) "Protected" else "Stopped", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(
                        onClick = {
                            if (running) VpnControl.stop(this@MainActivity)
                            else {
                                ArgParser.validate(chosen.args)?.let { error = it; return@Button }
                                error = null
                                val p = VpnService.prepare(this@MainActivity)
                                if (p != null) launcher.launch(p) else VpnControl.start(this@MainActivity)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (running) Color(0xFFD9534F) else MaterialTheme.colorScheme.primary),
                    ) { Text(if (running) "Stop" else "Start", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                }
            }
            Section("Now") {
                Text("Strategy: ${chosen.name}")
                val dnsLine = when (s.dnsMode) {
                    DnsMode.AUTO -> "DNS: Auto" + best.takeIf { running && it.isNotBlank() && !it.startsWith("none") }?.let { "  •  best: " + it.replace("|", " (") + " ms)" }.orEmpty()
                    DnsMode.DOH -> "DNS: DoH"
                    DnsMode.SYSTEM -> "DNS: Plain (${s.plainDns})"
                }
                Text(dnsLine)
                Text("Apps: ${if (s.appMode == AppMode.ONLY) "only" else "all except"} ${s.apps.size} selected")
            }
            Section("Tips") {
                Text("Quick button: swipe down the notification panel, tap the pencil (edit) and drag the Konata tile next to Wi-Fi and Bluetooth.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    runCatching {
                        startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Allow running in the background (battery)") }
            }
            var log by remember { mutableStateOf("") }
            var open by remember { mutableStateOf(false) }
            val logScroll = rememberScrollState()
            LaunchedEffect(open) {
                while (open) { log = VpnLog.read(filesDir, 400); kotlinx.coroutines.delay(1500) }
            }
            LaunchedEffect(log) {
                if (logScroll.maxValue - logScroll.value < 150) logScroll.scrollTo(logScroll.maxValue)
            }
            Section("Diagnostics") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { open = !open }) { Text(if (open) "Hide log" else "Show log") }
                    OutlinedButton(onClick = {
                        val t = VpnLog.read(filesDir, 400)
                        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("konata log", t))
                        Toast.makeText(this@MainActivity, "Log copied", Toast.LENGTH_SHORT).show()
                    }) { Text("Copy all") }
                    OutlinedButton(onClick = { runCatching { File(filesDir, "vpn.log").writeText("") }; log = "" }) { Text("Clear") }
                }
                if (open) Card(Modifier.fillMaxWidth().height(320.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    SelectionContainer {
                        Text(log.ifBlank { "(no log yet)" }, Modifier.fillMaxWidth().verticalScroll(logScroll).padding(8.dp),
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    // ---------------------------------------------------------------- Strategies
    @Composable
    private fun StrategiesTab(store: Store, s: Settings, list: List<Strategy>, refresh: () -> Unit, update: (Settings) -> Unit) {
        var editing by remember { mutableStateOf<Strategy?>(null) }
        var importText by remember { mutableStateOf<String?>(null) }
        var help by remember { mutableStateOf(false) }
        var msg by remember { mutableStateOf<String?>(null) }

        if (help) AlertDialog(onDismissRequest = { help = false },
            title = { Text("How to add a strategy") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(HELP_TEXT, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = { TextButton(onClick = { help = false }) { Text("OK") } })

        editing?.let { e ->
            var name by remember(e.id) { mutableStateOf(e.name) }
            var args by remember(e.id) { mutableStateOf(e.args) }
            val problem = ArgParser.validate(args)
            AlertDialog(onDismissRequest = { editing = null },
                title = { Text(if (e.builtin) "Built-in (copy to edit)" else "Strategy") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                        OutlinedTextField(args, { args = it }, label = { Text("Options (byedpi syntax)") }, minLines = 4, maxLines = 12)
                        problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                },
                confirmButton = {
                    TextButton(enabled = name.isNotBlank() && problem == null, onClick = {
                        val user = store.userStrategies().toMutableList()
                        if (e.builtin || user.none { it.id == e.id }) user += Strategy(if (e.builtin) store.newId() else e.id, name, args)
                        else user[user.indexOfFirst { it.id == e.id }] = Strategy(e.id, name, args)
                        store.saveUserStrategies(user); refresh(); editing = null
                    }) { Text(if (e.builtin) "Save as copy" else "Save") }
                },
                dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } })
        }

        importText?.let { t ->
            var text by remember { mutableStateOf(t) }
            AlertDialog(onDismissRequest = { importText = null },
                title = { Text("Import strategies") },
                text = { OutlinedTextField(text, { text = it }, label = { Text("Paste exported JSON") }, minLines = 6) },
                confirmButton = {
                    TextButton(onClick = {
                        msg = runCatching { "Imported ${store.importStrategies(text)}" }.getOrElse { "Invalid text: ${it.message}" }
                        refresh(); importText = null
                    }) { Text("Import") }
                },
                dismissButton = { TextButton(onClick = { importText = null }) { Text("Cancel") } })
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { editing = Strategy("", "", "") }) { Text("New") }
                OutlinedButton(onClick = {
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("strategies", store.exportStrategies()))
                    msg = "Copied to clipboard"
                }) { Text("Export") }
                OutlinedButton(onClick = { importText = "" }) { Text("Import") }
                Spacer(Modifier.weight(1f))
                FilledTonalIconButton(onClick = { help = true }) { Text("?", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
            }
            msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { st ->
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = s.strategyId == st.id, onClick = { update(s.copy(strategyId = st.id)) })
                            Column(Modifier.weight(1f).clickable { editing = st }) {
                                Text(st.name, style = MaterialTheme.typography.titleSmall)
                                Text(st.args.lines().filterNot { it.trimStart().startsWith("#") }.joinToString(" ").take(120) + "…",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (!st.builtin) TextButton(onClick = {
                                store.saveUserStrategies(store.userStrategies().filter { it.id != st.id })
                                if (s.strategyId == st.id) update(s.copy(strategyId = Presets.v6.id))
                                refresh()
                            }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- DNS
    @Composable
    private fun DnsTab(s: Settings, update: (Settings) -> Unit) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(DnsMode.AUTO to "Auto - smart (like SimpleDnsCrypt)", DnsMode.DOH to "DoH (choose resolver)", DnsMode.SYSTEM to "Plain DNS").forEach { (m, label) ->
                Row(Modifier.fillMaxWidth().clickable { update(s.copy(dnsMode = m)) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = s.dnsMode == m, onClick = { update(s.copy(dnsMode = m)) }); Text(label)
                }
            }
            if (s.dnsMode == DnsMode.AUTO) {
                var rank by remember { mutableStateOf(listOf<String>()) }
                LaunchedEffect(running) {
                    while (true) {
                        rank = runCatching { File(filesDir, "dns-status.txt").readLines().drop(1) }.getOrDefault(emptyList())
                        kotlinx.coroutines.delay(2000)
                    }
                }
                Section("Live servers (re-measured every 20 s, switches automatically)") {
                    if (!running || rank.isEmpty()) Text("Start Konata to see the live ranking.", style = MaterialTheme.typography.bodySmall)
                    rank.forEachIndexed { i, l ->
                        val (n, ms) = l.split("|").let { it[0] to (it.getOrNull(1)?.toLongOrNull() ?: -1L) }
                        Text((if (i == 0 && ms >= 0) "★ " else "   ") + n + "  —  " + (if (ms < 0) "down" else "$ms ms"),
                            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
                var out by remember { mutableStateOf("") }
                var busy by remember { mutableStateOf(false) }
                Button(enabled = !busy, onClick = {
                    busy = true; out = "Testing (about 30-60 s)..."
                    Thread {
                        val eng = DnsEngine(DnsMode.AUTO, s.dohUrl, s.plainDns, Resolvers.all(this@MainActivity))
                        val r = runCatching { eng.selfTest(listOf("pbs.twimg.com", "video.twimg.com", "x.com", "www.instagram.com", "scontent.cdninstagram.com", "rr1---sn-4g5e6nzl.googlevideo.com")) }
                            .getOrElse { "failed: ${it.message}" }
                        runOnUiThread { out = r; busy = false }
                    }.start()
                }) { Text(if (busy) "Testing..." else "Test DNS now (shows which resolver gives a working IP)") }
                if (out.isNotEmpty()) {
                    SelectionContainer { Text(out, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
                    OutlinedButton(onClick = {
                        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("dns-test", out))
                        Toast.makeText(this@MainActivity, "Copied", Toast.LENGTH_SHORT).show()
                    }) { Text("Copy result") }
                }
            }
            if (s.dnsMode == DnsMode.DOH) {
                val all = remember { Resolvers.all(this@MainActivity) }
                var q by remember { mutableStateOf("") }
                Text("Selected: ${all.firstOrNull { it.url == s.dohUrl }?.name ?: s.dohUrl}", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(q, { q = it }, label = { Text("Search ${all.size} DoH resolvers") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 360.dp)) {
                    items(all.filter { q.isBlank() || it.name.contains(q, true) || it.url.contains(q, true) }, key = { it.url + it.name }) { r ->
                        Row(Modifier.fillMaxWidth().clickable { update(s.copy(dohUrl = r.url)) }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = s.dohUrl == r.url, onClick = { update(s.copy(dohUrl = r.url)) })
                            Column { Text(r.name); Text(r.url, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                var custom by remember { mutableStateOf(if (all.any { it.url == s.dohUrl }) "" else s.dohUrl) }
                OutlinedTextField(custom, { custom = it; if (it.startsWith("https://")) update(s.copy(dohUrl = it)) },
                    label = { Text("Custom DoH URL (https://…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            if (s.dnsMode == DnsMode.SYSTEM) {
                OutlinedTextField(s.plainDns, { update(s.copy(plainDns = it.trim())) }, label = { Text("DNS server IP") }, singleLine = true,
                    keyboardOptions = KeyboardOptions.Default, modifier = Modifier.fillMaxWidth())
            }
        }
    }

    // ---------------------------------------------------------------- Apps
    @Composable
    private fun AppsTab(s: Settings, update: (Settings) -> Unit) {
        val pm = packageManager
        val apps = remember {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .filter { it.first != packageName }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
        }
        var q by remember { mutableStateOf("") }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = s.appMode == AppMode.EXCLUDE, onClick = { update(s.copy(appMode = AppMode.EXCLUDE)) }, label = { Text("All except checked") })
                FilterChip(selected = s.appMode == AppMode.ONLY, onClick = { update(s.copy(appMode = AppMode.ONLY)) }, label = { Text("Only checked") })
            }
            OutlinedTextField(q, { q = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            LazyColumn {
                items(apps.filter { q.isBlank() || it.second.contains(q, true) || it.first.contains(q, true) }, key = { it.first }) { (pkg, label) ->
                    Row(Modifier.fillMaxWidth().clickable {
                        update(s.copy(apps = if (pkg in s.apps) s.apps - pkg else s.apps + pkg))
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = pkg in s.apps, onCheckedChange = { update(s.copy(apps = if (it) s.apps + pkg else s.apps - pkg)) })
                        Column { Text(label); Text(pkg, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}

private const val HELP_TEXT = """كيف تضيف استراتيجية جديدة؟

1) اضغط New.
2) اكتب اسماً، ثم الخيارات بصيغة byedpi (برنامج ciadpi). يمكنك استعمال عدة أسطر، والسطر الذي يبدأ بـ # تعليق.
3) اضغط Save ثم اختر الاستراتيجية بالدائرة في القائمة، وأعد تشغيل Konata.

أمثلة:
• بسيطة (الأساسية):
  --disorder 1 --tlsrec 1+s
• لمواقع محددة فقط (مجموعة بقائمة نطاقات):
  --hosts ":youtube.com googlevideo.com" -q1+s -s29+s -o5+s -f-1 -S
• احتياطية (تُجرَّب عند فشل الاتصال، مثل RST أو timeout):
  -At,r,s -n www.google.com -Qr -f-1 -S -d1 -d0+sm

أهم الخيارات:
-d disorder (إرسال الأجزاء بترتيب خاطئ) | -s split (تقسيم) | -o / -q OOB | -f fake (حزمة وهمية) | -t TTL للوهمية | -S md5sig | -n SNI وهمي | -Qr TLS عشوائي | -r تقسيم TLS record | -a عدد حزم UDP/QUIC الوهمية.
المواضع: 1 = بعد أول بايت، 1+s = عند SNI، 0+sm = منتصف SNI، 2 = بعد اسم الأمر في HTTP.
المجموعات: كل --auto=none أو -At,r,s أو --hosts أو --proto تبدأ مجموعة جديدة، وتُطبَّق الأولى المطابقة للموقع.

لا تدعم: خيارات تحتاج Root (badsum، seqovl، autottl...) ولا تكتب --ip أو --port أو --daemon، فالتطبيق يضبطها.
للنقل بين الأجهزة: Export ينسخ JSON، و Import يلصقه.

English: New → name + byedpi options (multi-line, # comments) → Save → select the radio button → restart Konata. Groups start with --auto=none, -At,r,s (failover), --hosts ":a.com b.com" or --proto=h. Root-only options are not supported."""
