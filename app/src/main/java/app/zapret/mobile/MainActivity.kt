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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.graphics.drawable.toBitmap
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

private val Blue = Color(0xFF3558D6)
private val Green = Color(0xFF1F9D63)
private val Paper = Color(0xFFF2E9D3)
private val InkC = Color(0xFF1B2250)

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
                primary = Color(0xFF86A6FF), onPrimary = Color(0xFF101733), secondary = Color(0xFF55D69A), tertiary = Color(0xFFFFD84D),
                background = Color(0xFF14172B), surface = Color(0xFF1D2140), surfaceVariant = Color(0xFF262B52), outline = Color(0xFFEDE6D3),
                onBackground = Color(0xFFEDE6D3), onSurface = Color(0xFFEDE6D3), onSurfaceVariant = Color(0xFFB9B4A6),
                primaryContainer = Color(0xFF2E3A7A), onPrimaryContainer = Color(0xFFEDE6D3), error = Color(0xFFFF7A93),
            ) else lightColorScheme(
                primary = Blue, onPrimary = Color.White, secondary = Green, tertiary = Color(0xFFFFD84D),
                background = Paper, surface = Color(0xFFFFFBF1), surfaceVariant = Color(0xFFEADFC4), outline = InkC,
                onBackground = InkC, onSurface = InkC, onSurfaceVariant = Color(0xFF5A5F82),
                primaryContainer = Color(0xFFDCE4FF), onPrimaryContainer = InkC, error = Color(0xFFC62E4D),
            )
            val shapes = Shapes(extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(4.dp),
                medium = RoundedCornerShape(6.dp), large = RoundedCornerShape(8.dp))
            // The UI is English: force left-to-right even on an Arabic phone, otherwise everything mirrors.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                MaterialTheme(colorScheme = scheme, shapes = shapes) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) { App(store) }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The VPN lives in its own process and keeps running when this screen is closed: re-detect it.
        running = VpnControl.isRunning(this)
    }

    override fun onDestroy() { unregisterReceiver(stateReceiver); super.onDestroy() }

    // ---- small "ink on paper" kit: flat fills, 2dp ink outlines, hard offset shadows, monospace labels
    private val Mono = FontFamily.Monospace

    private val dark @Composable get() = MaterialTheme.colorScheme.background.luminance() < 0.5f
    /** Outline colour: full ink on paper, softened cream on the dark theme. */
    private val edge @Composable get() = MaterialTheme.colorScheme.onBackground.copy(alpha = if (dark) 0.55f else 1f)
    private val shadowC @Composable get() = if (dark) Color(0xFF070914) else MaterialTheme.colorScheme.onBackground

    /** Flat button that replaces the stock pill-shaped Material buttons everywhere (same call shape). */
    @Composable
    private fun ChipBase(onClick: () -> Unit, modifier: Modifier, enabled: Boolean, primary: Boolean, content: @Composable RowScope.() -> Unit) {
        val shape = RoundedCornerShape(6.dp)
        val fg = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onBackground
        Row(modifier.alpha(if (enabled) 1f else 0.45f).clip(shape)
            .background(if (primary) MaterialTheme.colorScheme.primary else Color.Transparent)
            .border(1.5.dp, if (primary) MaterialTheme.colorScheme.primary else edge, shape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            CompositionLocalProvider(LocalContentColor provides fg, LocalTextStyle provides TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp)) { content() }
        }
    }

    @Composable
    private fun OutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
        ChipBase(onClick, modifier, enabled, false, content)

    @Composable
    private fun Button(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
        ChipBase(onClick, modifier, enabled, true, content)

    @Composable
    private fun Ink(modifier: Modifier = Modifier, fill: Color = MaterialTheme.colorScheme.surface, content: @Composable ColumnScope.() -> Unit) {
        val ink = edge; val sh = shadowC
        val shape = RoundedCornerShape(6.dp)
        Box(modifier.padding(end = 4.dp, bottom = 4.dp)) {
            Column(Modifier.fillMaxWidth()
                .drawBehind { drawRoundRect(sh, Offset(4.dp.toPx(), 4.dp.toPx()), size, CornerRadius(6.dp.toPx())) }
                .background(fill, shape).border(2.dp, ink, shape).padding(14.dp), content = content)
        }
    }

    @Composable
    private fun InkButton(text: String, fill: Color, textColor: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
        val ink = edge; val sh = shadowC
        val shape = RoundedCornerShape(6.dp)
        Box(modifier.padding(end = 4.dp, bottom = 4.dp)) {
            Box(Modifier.fillMaxWidth()
                .drawBehind { drawRoundRect(sh, Offset(4.dp.toPx(), 4.dp.toPx()), size, CornerRadius(6.dp.toPx())) }
                .background(fill, shape).border(2.dp, ink, shape).clickable(onClick = onClick).padding(vertical = 16.dp),
                contentAlignment = Alignment.Center) {
                Text(text, color = textColor, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 4.sp)
            }
        }
    }

    @Composable
    private fun Seg(options: List<String>, selected: Int, enabled: Boolean = true, onSelect: (Int) -> Unit) {
        val ink = edge
        val shape = RoundedCornerShape(6.dp)
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(shape).border(2.dp, ink, shape)) {
            options.forEachIndexed { i, t ->
                if (i > 0) Box(Modifier.width(2.dp).fillMaxHeight().background(ink))
                Box(Modifier.weight(1f).fillMaxHeight()
                    .background(if (i == selected) MaterialTheme.colorScheme.tertiary else Color.Transparent)
                    .clickable(enabled = enabled) { onSelect(i) }.padding(vertical = 11.dp), contentAlignment = Alignment.Center) {
                    Text(t, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                        color = if (i == selected) Color(0xFF1B2250) else MaterialTheme.colorScheme.onBackground.copy(alpha = if (enabled) 1f else 0.4f))
                }
            }
        }
    }

    @Composable
    private fun Label(t: String) = Text(t.uppercase(), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 11.sp,
        letterSpacing = 2.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

    @Composable
    private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
        Ink(Modifier.fillMaxWidth()) {
            Label(title)
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
    }

    @Composable
    private fun App(store: Store) {
        var tab by remember { mutableIntStateOf(0) }
        var settings by remember { mutableStateOf(store.settings()) }
        var strategies by remember { mutableStateOf(store.allStrategies()) }
        fun update(s: Settings) { settings = s; store.saveSettings(s) }

        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.konata), null,
                    Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).border(2.dp, edge, RoundedCornerShape(6.dp)),
                    contentScale = ContentScale.Crop)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("KONATA", fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 24.sp, letterSpacing = 5.sp)
                    Text("dpi bypass + smart dns", fontFamily = Mono, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Seg(listOf("HOME", "STRATEGY", "DNS", "APPS"), tab) { tab = it }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> HomeTab(settings, strategies, ::update)
                    1 -> StrategiesTab(store, settings, strategies, { strategies = store.allStrategies() }, ::update)
                    2 -> DnsTab(settings, ::update)
                    else -> AppsTab(settings, ::update)
                }
            }
        }
    }

    // ---------------------------------------------------------------- Home
    @Composable
    private fun HomeTab(s: Settings, strategies: List<Strategy>, update: (Settings) -> Unit) {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == RESULT_OK) VpnControl.start(this)
        }
        val chosen = strategies.firstOrNull { it.id == s.strategyId } ?: strategies.first()
        val dnsOnly = s.runMode == RunMode.DNS_ONLY
        var best by remember { mutableStateOf("") }
        LaunchedEffect(running) {
            while (true) {
                best = runCatching { File(filesDir, "dns-status.txt").readLines().firstOrNull().orEmpty() }.getOrDefault("")
                kotlinx.coroutines.delay(2000)
            }
        }
        val ink = edge
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Label("Mode")
                Seg(listOf("ZAPRET + DNS", "DNS ONLY"), if (dnsOnly) 1 else 0, enabled = !running) {
                    update(s.copy(runMode = if (it == 1) RunMode.DNS_ONLY else RunMode.FULL))
                }
                if (running) Text("Stop first to change the mode.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Ink(Modifier.fillMaxWidth(), fill = if (running) Green.copy(alpha = 0.25f).compositeOver(MaterialTheme.colorScheme.surface) else MaterialTheme.colorScheme.surface) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.konata), null,
                        Modifier.size(92.dp).clip(RoundedCornerShape(6.dp)).border(2.dp, ink, RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(if (running) "ON" else "OFF", fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 48.sp, lineHeight = 50.sp)
                        Text(if (running) (if (dnsOnly) "dns only" else "zapret + dns") else "tap start", fontFamily = Mono, fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            }
            InkButton(if (running) "STOP" else "START",
                fill = if (running) Color(0xFFFF6F8E) else MaterialTheme.colorScheme.primary,
                textColor = if (running) InkC else MaterialTheme.colorScheme.onPrimary, modifier = Modifier.fillMaxWidth()) {
                if (running) VpnControl.stop(this@MainActivity)
                else {
                    if (!dnsOnly) ArgParser.validate(chosen.args)?.let { error = it; return@InkButton }
                    error = null
                    val p = VpnService.prepare(this@MainActivity)
                    if (p != null) launcher.launch(p) else VpnControl.start(this@MainActivity)
                }
            }
            Section("Status") {
                @Composable fun kv(k: String, v: String) = Row {
                    Text(k.uppercase(), fontFamily = Mono, fontSize = 11.sp, letterSpacing = 1.sp, modifier = Modifier.width(88.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(v, fontSize = 14.sp, modifier = Modifier.weight(1f))
                }
                kv("strategy", if (dnsOnly) "- (dns only)" else chosen.name)
                kv("dns", when (s.dnsMode) { DnsMode.AUTO -> "auto"; DnsMode.DOH -> "doh"; DnsMode.SYSTEM -> "plain ${s.plainDns}" })
                if (s.dnsMode == DnsMode.AUTO) kv("best", if (running && best.isNotBlank() && !best.startsWith("none")) best.replace("|", "  ") + " ms" else "-")
                kv("apps", (if (s.appMode == AppMode.ONLY) "only " else "all except ") + s.apps.size)
            }
            Ink(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Start on boot", fontWeight = FontWeight.Bold)
                        Text("Konata turns itself on after the phone restarts (needs the VPN permission granted once).", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = s.autoStart, onCheckedChange = { update(s.copy(autoStart = it)) })
                }
            }
            Text("Quick button: pull down the notification panel > pencil > drag the Konata tile.", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            var check by remember { mutableStateOf("") }
            var checking by remember { mutableStateOf(false) }
            Section("Connection check") {
                Text("Opens a few sites through Konata and shows which ones answer. Start Konata first.", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = {
                    checking = true; check = "Checking..."
                    runCheck { r -> runOnUiThread { check = r; checking = false } }
                }, enabled = running && !checking) { Text(if (checking) "Checking..." else "Run check") }
                if (check.isNotBlank()) SelectionContainer { Text(check, fontFamily = Mono, fontSize = 12.sp, lineHeight = 17.sp) }
            }
            var log by remember { mutableStateOf("") }
            var open by remember { mutableStateOf(false) }
            var simple by remember { mutableStateOf(true) }
            val shown = if (!simple) log else log.lines().mapNotNull { friendlyLine(it) }.joinToString("\n")
            val logScroll = rememberScrollState()
            LaunchedEffect(open) {
                while (open) { log = VpnLog.read(filesDir, 400); kotlinx.coroutines.delay(1500) }
            }
            LaunchedEffect(log) {
                if (logScroll.maxValue - logScroll.value < 150) logScroll.scrollTo(logScroll.maxValue)
            }
            Section("Log") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Show") }
                    OutlinedButton(onClick = { simple = !simple }) { Text(if (simple) "Simple" else "Raw") }
                    OutlinedButton(onClick = {
                        val t = VpnLog.read(filesDir, 400)
                        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("konata log", t))
                        Toast.makeText(this@MainActivity, "Log copied", Toast.LENGTH_SHORT).show()
                    }) { Text("Copy all") }
                    OutlinedButton(onClick = { runCatching { File(filesDir, "vpn.log").writeText("") }; log = "" }) { Text("Clear") }
                }
                if (open) Box(Modifier.fillMaxWidth().height(300.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))) {
                    SelectionContainer {
                        Text(shown.ifBlank { "(nothing yet)" }, Modifier.fillMaxWidth().verticalScroll(logScroll).padding(8.dp),
                            fontSize = 11.sp, lineHeight = 14.sp, fontFamily = Mono)
                    }
                }
            }
            OutlinedButton(onClick = { update(Settings()) }, enabled = !running) { Text("Reset all settings") }
            Spacer(Modifier.height(8.dp))
        }
    }

    /** Fetches a few sites through byedpi (127.0.0.1:1080) so the result shows what the tunnel really does. */
    private fun runCheck(done: (String) -> Unit) {
        Thread {
            val client = OkHttpClient.Builder().proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", ZapretVpnService.SOCKS_PORT)))
                .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
            val sites = listOf("x.com", "pbs.twimg.com", "video.twimg.com", "abs.twimg.com", "www.instagram.com", "scontent.cdninstagram.com",
                "www.youtube.com", "i.ytimg.com", "discord.com", "web.whatsapp.com", "www.google.com")
            val out = java.util.concurrent.ConcurrentHashMap<String, String>()
            val ts = sites.map { h ->
                Thread {
                    val t0 = System.currentTimeMillis()
                    out[h] = try {
                        client.newCall(Request.Builder().url("https://$h/").header("User-Agent", "Mozilla/5.0").build()).execute()
                            .use { "OK   ${it.code}  ${System.currentTimeMillis() - t0} ms" }
                    } catch (e: Exception) { "FAIL ${e.javaClass.simpleName} ${System.currentTimeMillis() - t0} ms" }
                }.also { it.start() }
            }
            ts.forEach { it.join() }
            done(sites.joinToString("\n") { String.format("%-26s %s", it, out[it]) })
        }.start()
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
                OutlinedButton(onClick = { help = true }) { Text("?", fontSize = 16.sp) }
            }
            msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Section("My domains (for V6)") {
                        Text("One domain per line. Used by the built-in V6 only.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(s.excludeDomains, { update(s.copy(excludeDomains = it)) }, modifier = Modifier.fillMaxWidth(),
                            label = { Text("No bypass (leave untouched)") }, minLines = 2, maxLines = 5)
                        OutlinedTextField(s.heavyDomains, { update(s.copy(heavyDomains = it)) }, modifier = Modifier.fillMaxWidth(),
                            label = { Text("Strong bypass (like X / Twitter)") }, minLines = 2, maxLines = 5)
                        Text("Restart Konata to apply.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(list, key = { it.id }) { st ->
                    Ink(Modifier.fillMaxWidth()) {
                        Row(Modifier, verticalAlignment = Alignment.CenterVertically) {
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
                var dom by remember { mutableStateOf("") }
                OutlinedTextField(dom, { dom = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Test one domain (empty = standard set)") })
                Button(enabled = !busy, onClick = {
                    busy = true; out = "Testing..."
                    val one = dom.trim().lowercase().removePrefix("https://").substringBefore('/')
                    Thread {
                        val eng = DnsEngine(DnsMode.AUTO, s.dohUrl, s.plainDns, Resolvers.all(this@MainActivity))
                        val r = runCatching { eng.selfTest(if (one.isNotBlank()) listOf(one) else listOf("pbs.twimg.com", "video.twimg.com", "x.com", "www.instagram.com", "scontent.cdninstagram.com", "rr1---sn-4g5e6nzl.googlevideo.com")) }
                            .getOrElse { "failed: ${it.message}" }
                        runOnUiThread { out = r; busy = false }
                    }.start()
                }) { Text(if (busy) "Testing..." else "Test now (which server gives a working IP)") }
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
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Seg(listOf("ALL EXCEPT CHECKED", "ONLY CHECKED"), if (s.appMode == AppMode.ONLY) 1 else 0) {
                update(s.copy(appMode = if (it == 1) AppMode.ONLY else AppMode.EXCLUDE))
            }
            Text(if (s.appMode == AppMode.ONLY) "Only the checked apps go through Konata." else "Every app goes through Konata except the checked ones.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(q, { q = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            LazyColumn {
                items(apps.filter { q.isBlank() || it.second.contains(q, true) || it.first.contains(q, true) }, key = { it.first }) { (pkg, label) ->
                    val icon = remember(pkg) { runCatching { pm.getApplicationIcon(pkg).toBitmap(80, 80).asImageBitmap() }.getOrNull() }
                    Row(Modifier.fillMaxWidth().clickable {
                        update(s.copy(apps = if (pkg in s.apps) s.apps - pkg else s.apps + pkg))
                    }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (icon != null) Image(icon, null, Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)))
                        else Spacer(Modifier.size(40.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(label, fontWeight = FontWeight.Medium)
                            Text(pkg, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Checkbox(checked = pkg in s.apps, onCheckedChange = { update(s.copy(apps = if (it) s.apps + pkg else s.apps - pkg)) })
                    }
                }
            }
        }
    }
}

/** Human-friendly view of one log line, or null to hide it in the simple log. */
private fun friendlyLine(l: String): String? {
    val t = l.substringBefore(' ', ""); val m = l.substringAfter(' ', "")
    Regex("byedpi: save: ip=([0-9.]+), id=(\\d+)").find(m)?.let { return "$t  fallback #${it.groupValues[2]} worked for ${it.groupValues[1]}" }
    Regex("byedpi: unreach ip: ([0-9.]+)").find(m)?.let { return "$t  every strategy failed for ${it.groupValues[1]}" }
    val keep = listOf("DNS ", "DNS:", "starting", "mode:", "stopping", "START FAILED", "VPN interface", "tunnel started", "proxy listening", "DoH ranking")
    return if (keep.any { m.startsWith(it) }) l else null
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
