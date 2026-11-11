package app.zapret.mobile

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
        setContent {
            MaterialTheme(colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App(store) }
            }
        }
    }

    override fun onDestroy() { unregisterReceiver(stateReceiver); super.onDestroy() }

    @Composable
    private fun App(store: Store) {
        var tab by remember { mutableIntStateOf(0) }
        var settings by remember { mutableStateOf(store.settings()) }
        var strategies by remember { mutableStateOf(store.allStrategies()) }
        fun update(s: Settings) { settings = s; store.saveSettings(s) }

        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            TabRow(selectedTabIndex = tab) {
                listOf("Home", "Strategies", "DNS", "Apps").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            Box(Modifier.weight(1f).padding(16.dp)) {
                when (tab) {
                    0 -> HomeTab(store, settings, strategies, ::update)
                    1 -> StrategiesTab(store, settings, strategies, { strategies = store.allStrategies() }, ::update)
                    2 -> DnsTab(settings, ::update)
                    else -> AppsTab(settings, ::update)
                }
            }
        }
    }

    // ---------------------------------------------------------------- Home
    @Composable
    private fun HomeTab(store: Store, s: Settings, strategies: List<Strategy>, update: (Settings) -> Unit) {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == RESULT_OK) startVpn(store, s, strategies)
        }
        val chosen = strategies.firstOrNull { it.id == s.strategyId } ?: strategies.first()
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (running) "Running" else "Stopped", style = MaterialTheme.typography.headlineMedium)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = {
                if (running) startService(Intent(this@MainActivity, ZapretVpnService::class.java).setAction(ZapretVpnService.ACTION_STOP))
                else {
                    ArgParser.validate(chosen.args)?.let { error = it; return@Button }
                    error = null
                    val p = VpnService.prepare(this@MainActivity)
                    if (p != null) launcher.launch(p) else startVpn(store, s, strategies)
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(if (running) "Stop" else "Start") }
            Text("Strategy: ${chosen.name}")
            Text("DNS: ${s.dnsMode}   •   Apps: ${s.appMode.name.lowercase()} (${s.apps.size})")
        }
    }

    private fun startVpn(store: Store, s: Settings, strategies: List<Strategy>) {
        val chosen = strategies.firstOrNull { it.id == s.strategyId } ?: strategies.first()
        startService(
            Intent(this, ZapretVpnService::class.java).setAction(ZapretVpnService.ACTION_START)
                .putExtra(ZapretVpnService.EXTRA_CONFIG, s.toJson(chosen.args))
        )
    }

    // ---------------------------------------------------------------- Strategies
    @Composable
    private fun StrategiesTab(store: Store, s: Settings, list: List<Strategy>, refresh: () -> Unit, update: (Settings) -> Unit) {
        var editing by remember { mutableStateOf<Strategy?>(null) }
        var importText by remember { mutableStateOf<String?>(null) }
        var msg by remember { mutableStateOf<String?>(null) }

        editing?.let { e ->
            var name by remember(e.id) { mutableStateOf(e.name) }
            var args by remember(e.id) { mutableStateOf(e.args) }
            val problem = ArgParser.validate(args)
            AlertDialog(onDismissRequest = { editing = null },
                title = { Text(if (e.builtin) "Built-in (copy to edit)" else "Strategy") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                        OutlinedTextField(args, { args = it }, label = { Text("Arguments (byedpi syntax)") }, minLines = 3)
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

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { editing = Strategy("", "", "") }) { Text("New") }
                OutlinedButton(onClick = {
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("strategies", store.exportStrategies()))
                    msg = "Copied to clipboard"
                }) { Text("Export") }
                OutlinedButton(onClick = { importText = "" }) { Text("Import") }
            }
            msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(list, key = { it.id }) { st ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = s.strategyId == st.id, onClick = { update(s.copy(strategyId = st.id)) })
                            Column(Modifier.weight(1f).clickable { editing = st }) {
                                Text(st.name + if (st.builtin) "  (built-in)" else "", style = MaterialTheme.typography.titleSmall)
                                Text(st.args, style = MaterialTheme.typography.bodySmall)
                            }
                            if (!st.builtin) TextButton(onClick = {
                                store.saveUserStrategies(store.userStrategies().filter { it.id != st.id })
                                if (s.strategyId == st.id) update(s.copy(strategyId = Presets.all.first().id))
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
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(DnsMode.AUTO to "Auto (fastest working DoH)", DnsMode.DOH to "DoH (choose resolver)", DnsMode.SYSTEM to "Plain DNS").forEach { (m, label) ->
                Row(Modifier.fillMaxWidth().clickable { update(s.copy(dnsMode = m)) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = s.dnsMode == m, onClick = { update(s.copy(dnsMode = m)) }); Text(label)
                }
            }
            if (s.dnsMode == DnsMode.DOH) {
                Resolvers.builtin.forEach { r ->
                    Row(Modifier.fillMaxWidth().clickable { update(s.copy(dohUrl = r.url)) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = s.dohUrl == r.url, onClick = { update(s.copy(dohUrl = r.url)) }); Text("${r.name}  —  ${r.url}")
                    }
                }
                var custom by remember { mutableStateOf(if (Resolvers.builtin.any { it.url == s.dohUrl }) "" else s.dohUrl) }
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
