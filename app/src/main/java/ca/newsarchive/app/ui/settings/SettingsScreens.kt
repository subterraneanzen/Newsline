@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package ca.newsarchive.app.ui.settings

import android.Manifest
import android.app.Activity
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ca.newsarchive.app.LocalContainer
import ca.newsarchive.app.data.AppSettings
import ca.newsarchive.app.data.ArticleFilter
import ca.newsarchive.app.data.HomeSection
import ca.newsarchive.app.data.ThemeMode
import ca.newsarchive.app.ingest.StorageStats
import ca.newsarchive.app.ui.DetailBar
import ca.newsarchive.app.ui.Fmt
import ca.newsarchive.app.ui.components.Nav
import ca.newsarchive.app.ui.components.SectionHeader
import ca.newsarchive.app.ui.components.toast
import ca.newsarchive.app.ui.home.sectionTitle
import ca.newsarchive.app.work.SyncWorker
import ca.newsarchive.core.geo.Gazetteer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.system.exitProcess

@Composable
private fun SwitchRow(title: String, sub: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            sub?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun <T> ChoiceRow(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(title)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (v, l) -> FilterChip(v == selected, { onSelect(v) }, label = { Text(l) }) }
        }
    }
}

private val backgroundChoices = listOf(
    0L to "Theme default", 0xFFFFFFFF to "White", 0xFFFAF9F6 to "Paper", 0xFFF4ECD8 to "Sepia",
    0xFFE8EEF4 to "Pale blue", 0xFFE9F1E7 to "Pale green", 0xFF2B2F36 to "Slate", 0xFF1B2433 to "Navy",
    0xFF121212 to "Dark", 0xFF000000 to "Black",
)
private val textChoices = listOf(
    0L to "Theme default", 0xFF000000 to "Black", 0xFF1A1A1A to "Ink", 0xFF3B2F2F to "Brown",
    0xFF1F3A5F to "Navy", 0xFFFFFFFF to "White", 0xFFE8E6E1 to "Off-white", 0xFFF2D7A0 to "Amber",
    0xFFB9F6CA to "Mint",
)

/** Row of colour circles; the first entry (0) means "follow the theme" and is drawn half light, half dark. */
@Composable
private fun ColorRow(title: String, choices: List<Pair<Long, String>>, selected: Long, onSelect: (Long) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text("$title: ${choices.firstOrNull { it.first == selected }?.second ?: "Custom"}")
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            choices.forEach { (argb, label) ->
                val isSel = argb == selected
                val fill = if (argb == 0L) Modifier.background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFFFAF9F6), Color(0xFFFAF9F6), Color(0xFF121212), Color(0xFF121212))))
                           else Modifier.background(Color(argb.toInt()))
                Box(
                    Modifier.size(38.dp).clip(CircleShape).then(fill)
                        .border(if (isSel) 3.dp else 1.dp, if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                        .clickable { onSelect(argb) }
                        .semantics { contentDescription = label; this.selected = isSel },
                )
            }
        }
    }
}

@Composable
fun SettingsScreen(nav: Nav) {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val s by c.settings.flow.collectAsState()
    val runs by remember { c.db.misc().observeRuns(10) }.collectAsState(emptyList())
    var placeQuery by remember { mutableStateOf("") }
    var addSection by remember { mutableStateOf("") }

    fun update(reschedule: Boolean = false, f: (AppSettings) -> AppSettings) {
        c.settings.update(f)
        if (reschedule) SyncWorker.schedule(ctx, c.settings.current)
    }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        update { it.copy(notificationsEnabled = granted) }
        if (!granted) toast(ctx, "Notifications permission was not granted")
    }

    Column(Modifier.fillMaxSize()) {
        DetailBar("Settings", nav)
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                SectionHeader("Appearance & reading")
                ChoiceRow("Theme", ThemeMode.entries.map { it to it.name.lowercase().replaceFirstChar { ch -> ch.uppercase() } }, s.theme) { v -> update { it.copy(theme = v) } }
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text("Reader text size: ${(s.fontScale * 100).toInt()}%")
                    Slider(s.fontScale, { v -> update { it.copy(fontScale = v) } }, valueRange = 0.8f..1.6f, steps = 7)
                    Text("Line spacing: ${"%.1f".format(s.lineSpacing)}")
                    Slider(s.lineSpacing, { v -> update { it.copy(lineSpacing = v) } }, valueRange = 1.2f..2.2f, steps = 9)
                }
                SwitchRow("Show images", "Off = text-only reading; images are never downloaded", s.showImages) { v -> update { it.copy(showImages = v) } }
            }
            item {
                SectionHeader("Colours & text size", Modifier.padding(top = 12.dp))
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text("App text size: ${(s.appTextScale * 100).toInt()}%")
                    Slider(s.appTextScale, { v -> update { it.copy(appTextScale = v) } }, valueRange = 0.85f..1.5f, steps = 12)
                    Text("Applies to every screen, including headlines on the home page.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ColorRow("Background colour", backgroundChoices, s.backgroundColor) { v -> update { it.copy(backgroundColor = v) } }
                ColorRow("Text colour", textChoices, s.textColor) { v -> update { it.copy(textColor = v) } }
                if (s.backgroundColor != 0L || s.textColor != 0L || s.appTextScale != 1f) {
                    TextButton(onClick = { update { it.copy(backgroundColor = 0L, textColor = 0L, appTextScale = 1f) } }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Reset colours and text size") }
                }
            }
            item {
                SectionHeader("Home screen", Modifier.padding(top = 12.dp))
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text("Default location: ${Gazetteer.pathLabel(s.homePlace).ifBlank { s.homePlace }}", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(placeQuery, { placeQuery = it }, singleLine = true, label = { Text("Change default location") }, modifier = Modifier.fillMaxWidth())
                    if (placeQuery.length >= 2) Gazetteer.search(placeQuery).take(6).forEach { p ->
                        TextButton(onClick = { update { it.copy(homePlace = p.code) }; placeQuery = "" }) { Text(Gazetteer.pathLabel(p.code)) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Sections (in order)", fontWeight = FontWeight.SemiBold)
                    s.homeSections.forEachIndexed { i, code ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(sectionTitle(code, s.homePlace), Modifier.weight(1f))
                            TextButton(enabled = i > 0, onClick = { update { it.copy(homeSections = it.homeSections.toMutableList().apply { add(i - 1, removeAt(i)) }) } }) { Text("↑") }
                            TextButton(enabled = i < s.homeSections.size - 1, onClick = { update { it.copy(homeSections = it.homeSections.toMutableList().apply { add(i + 1, removeAt(i)) }) } }) { Text("↓") }
                            TextButton(onClick = { update { it.copy(homeSections = it.homeSections - code) } }) { Text("Remove") }
                        }
                    }
                    Text("Add a section", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(HomeSection.LOCAL, HomeSection.MULTI, HomeSection.LATEST, HomeSection.UPDATED, Gazetteer.WORLD_CODE, Gazetteer.SPACE_CODE)
                            .filter { it !in s.homeSections }.forEach { code ->
                                FilterChip(false, { update { it.copy(homeSections = it.homeSections + code) } }, label = { Text(sectionTitle(code, s.homePlace)) })
                            }
                    }
                    OutlinedTextField(addSection, { addSection = it }, singleLine = true, label = { Text("Add a place section (e.g. Alberta, Europe)") }, modifier = Modifier.fillMaxWidth())
                    if (addSection.length >= 2) Gazetteer.search(addSection).take(6).forEach { p ->
                        TextButton(onClick = { update { it.copy(homeSections = (it.homeSections + p.code).distinct()) }; addSection = "" }) { Text(Gazetteer.pathLabel(p.code)) }
                    }
                }
            }
            item {
                SectionHeader("Collection", Modifier.padding(top = 12.dp))
                ChoiceRow("Background collection at most every", listOf(15 to "15 min", 30 to "30 min", 60 to "1 h", 120 to "2 h", 240 to "4 h", 720 to "12 h"), s.syncIntervalMinutes) { v ->
                    update(reschedule = true) { it.copy(syncIntervalMinutes = v) }
                }
                Text(
                    "Android decides exactly when background work runs (battery saver, Doze, how often you use the app), so collection may be later than this. " +
                        "News is also collected when you open the app and when you tap refresh.",
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp),
                )
                SwitchRow("Wi-Fi only", "Background collection waits for an unmetered connection", s.wifiOnly) { v -> update(reschedule = true) { it.copy(wifiOnly = v) } }
                SwitchRow("Track article changes", "Re-check archived articles and save a new version when their text changes", s.revisitEnabled) { v -> update { it.copy(revisitEnabled = v) } }
                ChoiceRow("Keep re-checking each article for", listOf(3 to "3 days", 7 to "7 days", 14 to "14 days", 30 to "30 days", 90 to "90 days"), s.revisitDays) { v -> update { it.copy(revisitDays = v) } }
            }
            item {
                SectionHeader("Notifications", Modifier.padding(top = 12.dp))
                SwitchRow("Breaking news notifications", "Local notifications only; nothing is sent anywhere", s.notificationsEnabled) { v ->
                    if (v && Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else update { it.copy(notificationsEnabled = v) }
                }
                Text("Choose what triggers alerts: per source (Sources → source → Notify), followed topics/keywords (Timeline) and places (Explore), all manageable in Library → Following.",
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp))
                SwitchRow("Stories covered by 5+ publishers", null, s.notifyMultiSource) { v -> update { it.copy(notifyMultiSource = v) } }
                ChoiceRow("At most per hour", listOf(1 to "1", 2 to "2", 4 to "4", 8 to "8"), s.maxNotificationsPerHour) { v -> update { it.copy(maxNotificationsPerHour = v) } }
                SwitchRow("Quiet hours (10 pm – 7 am)", null, s.quietHours) { v -> update { it.copy(quietHours = v) } }
                Text("Alerts arrive after a collection run finds new articles, so they are not instantaneous.", style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 16.dp))
            }
            item {
                SectionHeader("Recent collection runs", Modifier.padding(top = 12.dp))
                if (runs.isEmpty()) Text("None yet.", modifier = Modifier.padding(16.dp))
                runs.forEach { r ->
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text("${Fmt.full(r.startedAt)} · ${r.trigger}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (r.finishedAt == null) "Running or interrupted"
                            else "${r.sourcesChecked} sources checked (${r.sourcesFailed} failed) · ${r.newArticles} new articles · " +
                                "${r.articlesRevisited} re-checked · ${r.newVersions} new versions · ${(r.finishedAt - r.startedAt) / 1000}s" +
                                (r.message?.takeIf { it != "OK" }?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            item {
                SectionHeader("Privacy", Modifier.padding(top = 12.dp))
                Text(
                    "No account, no analytics, no ads, no trackers. The app contacts only the news sources and Telegram previews you enable, " +
                        "plus image hosts when images are on. Your archive stays on this device unless you export it.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp),
                )
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
fun StorageScreen(nav: Nav) {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by c.settings.flow.collectAsState()
    var stats by remember { mutableStateOf<StorageStats?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, suspend () -> Unit>?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    var pendingRetention by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(refreshKey) { stats = withContext(Dispatchers.IO) { c.maintenance.stats() } }

    fun run(label: String, block: suspend () -> String) {
        progress = label
        scope.launch {
            val msg = runCatching { withContext(Dispatchers.IO) { block() } }.getOrElse { "Failed: ${it.message}" }
            progress = null; refreshKey++; toast(ctx, msg)
        }
    }

    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) run("Exporting archive…") { "Exported ${c.io.exportJson(uri)} articles" }
    }
    val exportFolderAll = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) run("Writing text files…") { "Wrote ${c.io.exportTextFolder(uri, ArticleFilter())} files (newest 2,000)" }
    }
    val exportFolderBookmarks = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) run("Writing text files…") { "Wrote ${c.io.exportTextFolder(uri, ArticleFilter(bookmarked = true))} files" }
    }
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) run("Backing up…") { c.io.backup(uri); "Backup saved" }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            progress = "Checking backup…"
            val staged = runCatching { withContext(Dispatchers.IO) { c.io.stageRestore(uri) } }
            progress = null
            staged.onFailure { toast(ctx, it.message ?: "Not a valid backup") }
            staged.onSuccess {
                confirm = "Replace your current archive with this backup? Everything collected since the backup will be lost. The app will close; open it again to finish." to suspend {
                    (ctx as? Activity)?.finishAffinity(); exitProcess(0)
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        DetailBar("Storage & export", nav)
        progress?.let { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall) }
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                SectionHeader("Usage")
                val st = stats
                Column(Modifier.padding(16.dp)) {
                    if (st == null) Text("Calculating…") else {
                        Text("Database on device: ${Fmt.bytes(st.databaseBytes)}", fontWeight = FontWeight.SemiBold)
                        Text("${st.articleCount} articles · ${st.versionCount} archived versions · ${Fmt.bytes(st.textBytes)} of article text")
                        Text("${st.telegramPosts} Telegram posts")
                        Text("Image cache: ${Fmt.bytes(st.imageCacheBytes)} (temporary, can be cleared any time)")
                    }
                    TextButton(onClick = { c.maintenance.clearImageCache(); refreshKey++ }) { Text("Clear image cache") }
                }
            }
            item {
                SectionHeader("Export & backup")
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { exportJson.launch("news-archive-${Fmt.dayKey(System.currentTimeMillis())}.json") }) { Text("Export full archive (JSON)") }
                    Text("Portable file with every article, every saved version, tags and collections.", style = MaterialTheme.typography.labelSmall)
                    OutlinedButton(onClick = { exportFolderBookmarks.launch(null) }) { Text("Export bookmarks as text files…") }
                    OutlinedButton(onClick = { exportFolderAll.launch(null) }) { Text("Export articles as text files…") }
                    Text("Individual articles can also be exported from the reader menu.", style = MaterialTheme.typography.labelSmall)
                    HorizontalDivider()
                    Button(onClick = { backup.launch("news-archive-backup-${Fmt.dayKey(System.currentTimeMillis())}.db") }) { Text("Back up database") }
                    OutlinedButton(onClick = { restore.launch(arrayOf("*/*")) }) { Text("Restore from backup…") }
                    Text("Backups are a complete copy of the archive database (articles, versions, sources, collections). App settings are not included.", style = MaterialTheme.typography.labelSmall)
                }
            }
            item {
                SectionHeader("Clean-up")
                Column(Modifier.padding(16.dp)) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            val ids = c.maintenance.duplicatePreview()
                            if (ids.isEmpty()) toast(ctx, "No probable duplicates found")
                            else confirm = "Delete ${ids.size} probable duplicates (same publisher, same headline within 2 days, never updated, not bookmarked or collected)?" to suspend {
                                val n = c.maintenance.deleteArticles(ids); toast(ctx, "Removed $n duplicates"); refreshKey++; Unit
                            }
                        }
                    }) { Text("Find duplicate articles…") }
                    Spacer(Modifier.height(12.dp))
                    Text("Retention", fontWeight = FontWeight.SemiBold)
                    Text("By default the archive keeps everything forever. You can choose to remove old articles automatically; bookmarked, " +
                        "saved and collected articles are always kept. Versions of kept articles are never removed.", style = MaterialTheme.typography.labelSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0 to "Keep forever", 90 to "90 days", 180 to "6 months", 365 to "1 year", 730 to "2 years").forEach { (d, l) ->
                            FilterChip(s.retentionDays == d, {
                                if (d == 0) c.settings.update { it.copy(retentionDays = 0) } else pendingRetention = d
                            }, label = { Text(l) })
                        }
                    }
                }
            }
        }
    }

    pendingRetention?.let { days ->
        var count by remember(days) { mutableStateOf<Int?>(null) }
        LaunchedEffect(days) { count = c.maintenance.retentionPreview(days) }
        AlertDialog(
            onDismissRequest = { pendingRetention = null },
            title = { Text("Delete articles older than $days days?") },
            text = { Text("This will permanently delete ${count ?: "…"} articles now (and their versions), and keep doing so after each collection. Bookmarked, saved and collected articles are kept.") },
            confirmButton = {
                TextButton(enabled = count != null, onClick = {
                    c.settings.update { it.copy(retentionDays = days) }; pendingRetention = null
                    run("Applying retention…") { "Removed ${c.maintenance.applyRetention()} articles" }
                }) { Text("Delete and enable", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingRetention = null }) { Text("Cancel") } },
        )
    }

    confirm?.let { (text, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null; java.io.File(ctx.filesDir, "restore-staged.db").delete() },
            title = { Text("Are you sure?") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { confirm = null; scope.launch { action() } }) { Text("Continue", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirm = null; java.io.File(ctx.filesDir, "restore-staged.db").delete() }) { Text("Cancel") } },
        )
    }
}
