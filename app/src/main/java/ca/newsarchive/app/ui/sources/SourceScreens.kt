@file:OptIn(ExperimentalMaterial3Api::class)

package ca.newsarchive.app.ui.sources

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ca.newsarchive.app.LocalContainer
import ca.newsarchive.app.data.AddResult
import ca.newsarchive.app.data.ArticleFilter
import ca.newsarchive.app.data.ArticleQuery
import ca.newsarchive.app.data.SourceStatus
import ca.newsarchive.app.data.SourceWithStats
import ca.newsarchive.app.ui.DetailBar
import ca.newsarchive.app.ui.Fmt
import ca.newsarchive.app.ui.components.ArticleList
import ca.newsarchive.app.ui.components.Nav
import ca.newsarchive.app.ui.components.SectionHeader
import ca.newsarchive.app.ui.components.openUrl
import ca.newsarchive.app.ui.components.toast
import ca.newsarchive.core.feed.ProbeResult
import ca.newsarchive.core.geo.Gazetteer
import ca.newsarchive.core.sources.SourceDirectory
import kotlinx.coroutines.launch

@Composable
fun statusColor(status: String): Color = when (status) {
    SourceStatus.OK -> MaterialTheme.colorScheme.primary
    SourceStatus.PARTIAL -> MaterialTheme.colorScheme.tertiary
    SourceStatus.ERROR, SourceStatus.UNAVAILABLE -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

fun statusLabel(status: String) = when (status) {
    SourceStatus.OK -> "Working"
    SourceStatus.PARTIAL -> "Partially supported"
    SourceStatus.ERROR -> "Error"
    SourceStatus.UNAVAILABLE -> "Unavailable"
    else -> "Not checked yet"
}

@Composable
fun SourcesScreen(nav: Nav) {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sources by remember { c.db.sources().observeWithStats() }.collectAsState(emptyList())
    var filter by remember { mutableStateOf<String?>(null) }
    var importPreview by remember { mutableStateOf<List<ca.newsarchive.core.feed.OpmlEntry>?>(null) }

    val exportOpml = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml")) { uri ->
        if (uri != null) scope.launch { runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.io.exportOpml(uri) } }.onSuccess { toast(ctx, "Exported $it sources") }.onFailure { toast(ctx, "Export failed: ${it.message}") } }
    }
    val importOpml = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching { c.io.readOpml(uri) }.onSuccess { importPreview = it }.onFailure { toast(ctx, "Couldn't read OPML: ${it.message}") }
    }

    val categories = sources.map { it.category }.distinct()
    val shown = sources.filter { s ->
        when (filter) { null -> true; "__problems" -> s.status == SourceStatus.ERROR || s.status == SourceStatus.UNAVAILABLE; else -> s.category == filter }
    }

    Column(Modifier.fillMaxSize()) {
        DetailBar("Sources", nav) { TextButton(onClick = { c.refresh("manual", force = true) }) { Text("Check all") } }
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(Modifier.padding(horizontal = 12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { nav.navigate("sources/add") }) { Text("Add by URL") }
                        OutlinedButton(onClick = { nav.navigate("sources/directory") }) { Text("Browse directory") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { importOpml.launch(arrayOf("*/*")) }) { Text("Import OPML") }
                        TextButton(onClick = { exportOpml.launch("news-archive-sources.opml") }) { Text("Export OPML") }
                    }
                    Text("${sources.count { it.enabled }} of ${sources.size} sources enabled · ${sources.sumOf { it.articleCount }} articles",
                        style = MaterialTheme.typography.labelSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(filter == null, { filter = null }, label = { Text("All") })
                        FilterChip(filter == "__problems", { filter = "__problems" }, label = { Text("Problems (${sources.count { it.status == SourceStatus.ERROR || it.status == SourceStatus.UNAVAILABLE }})") })
                        categories.forEach { cat -> FilterChip(filter == cat, { filter = cat }, label = { Text(cat) }) }
                    }
                }
            }
            items(shown, key = { it.id }) { s -> SourceRow(s, nav) }
        }
    }

    importPreview?.let { entries ->
        AlertDialog(
            onDismissRequest = { importPreview = null },
            title = { Text("Import ${entries.size} feeds?") },
            text = { Text("Feeds already in your list are skipped. Imported feeds are enabled and collected on the next run.") },
            confirmButton = {
                TextButton(onClick = {
                    importPreview = null
                    scope.launch { val (a, s) = c.repo.importOpml(entries); toast(ctx, "Added $a, skipped $s duplicates"); c.refresh("import") }
                }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { importPreview = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SourceRow(s: SourceWithStats, nav: Nav) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth().clickable { nav.openSource(s.id) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(s.name, fontWeight = FontWeight.SemiBold)
            Text("${s.category} · ${s.kind} · ${s.articleCount} articles", style = MaterialTheme.typography.labelSmall)
            Text(
                statusLabel(s.status) + (s.lastSuccessAt?.let { " · last success ${Fmt.relative(it)}" } ?: "") + (s.lastError?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.labelSmall, color = statusColor(s.status), maxLines = 2,
            )
        }
        Switch(s.enabled, { v -> scope.launch { c.repo.setSourceEnabled(s.id, v); if (v) c.refresh("enable", sourceIds = listOf(s.id)) } })
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
fun AddSourceScreen(nav: Nav) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ProbeResult?>(null) }
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var placeQuery by remember { mutableStateOf("") }
    var place by remember { mutableStateOf<String?>(null) }
    var local by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var sameHost by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        DetailBar("Add source", nav)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                Text("Paste an RSS/Atom feed, a sitemap, or a publisher's web page. The app checks it and finds a feed if the page advertises one.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(url, { url = it; result = null; message = null }, singleLine = true, label = { Text("URL") }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(enabled = url.isNotBlank() && !busy, onClick = {
                        busy = true; message = null
                        scope.launch {
                            val r = c.repo.probe(url)
                            result = r; busy = false
                            if (r.ok) {
                                name = r.title ?: ""
                                sameHost = r.feedUrl?.let { c.repo.sameHostCount(it) } ?: 0
                                c.repo.findDuplicate(r.feedUrl ?: url)?.let { message = "Already in your sources as “${it.name}”." }
                            }
                        }
                    }) { Text("Check") }
                    if (busy) { Spacer(Modifier.padding(8.dp)); CircularProgressIndicator(Modifier.padding(8.dp)) }
                }
            }
            result?.let { r ->
                item {
                    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(if (r.ok) "✓ ${r.message}" else "✗ ${r.message}", fontWeight = FontWeight.SemiBold,
                                color = if (r.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                            r.feedUrl?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                            if (r.alternatives.isNotEmpty()) Text("Other feeds on this site: " + r.alternatives.joinToString(", "), style = MaterialTheme.typography.labelSmall)
                            if (sameHost > 0) Text("You already have $sameHost source(s) from this site.", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (r.ok) item {
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(category, { category = it }, singleLine = true, label = { Text("Category (e.g. Saskatchewan, Science)") }, modifier = Modifier.fillMaxWidth())
                    Text("Publisher location (optional)", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                    if (place != null) TextButton(onClick = { place = null }) { Text(Gazetteer.pathLabel(place!!) + "  ✕") }
                    else OutlinedTextField(placeQuery, { placeQuery = it }, singleLine = true, label = { Text("City, province or country") }, modifier = Modifier.fillMaxWidth())
                    if (place == null && placeQuery.length >= 2) Gazetteer.search(placeQuery).take(6).forEach { p ->
                        TextButton(onClick = { place = p.code; placeQuery = "" }) { Text(Gazetteer.pathLabel(p.code)) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Local publisher")
                            Text("If an article names no place at all, tag it with the publisher's location (shown as “from source”). Articles that name places are never overridden.",
                                style = MaterialTheme.typography.labelSmall)
                        }
                        Switch(local, { local = it }, enabled = place != null)
                    }
                    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(enabled = message == null, onClick = {
                        scope.launch {
                            when (val a = c.repo.addProbed(r, name, category, place, local && place != null)) {
                                is AddResult.Added -> { c.refresh("new source", sourceIds = listOf(a.id)); nav.back() }
                                is AddResult.Duplicate -> message = "Already in your sources as “${a.existing.name}”."
                                is AddResult.Invalid -> message = a.message
                            }
                        }
                    }, modifier = Modifier.padding(vertical = 12.dp)) { Text("Add and collect") }
                }
            }
        }
    }
}

@Composable
fun DirectoryScreen(nav: Nav) {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sources by remember { c.db.sources().observeAll() }.collectAsState(emptyList())
    val existing = remember(sources) { sources.map { it.url }.toSet() }
    var cat by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        DetailBar("Source directory", nav)
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(Modifier.padding(horizontal = 12.dp)) {
                    Text("A starting selection of publishers across regions and subjects. Feeds can move; each is validated when added and its status appears in Sources. " +
                        "No political ratings are assigned.", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(cat == null, { cat = null }, label = { Text("All") })
                        SourceDirectory.categories.forEach { k -> FilterChip(cat == k, { cat = k }, label = { Text(k) }) }
                    }
                }
            }
            SourceDirectory.all.filter { cat == null || it.category == cat }.groupBy { it.category }.forEach { (category, list) ->
                item { SectionHeader(category, Modifier.padding(top = 8.dp)) }
                items(list, key = { it.feedUrl }) { d ->
                    val added = d.feedUrl in existing
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.name, fontWeight = FontWeight.SemiBold)
                            Text(d.homepage.removePrefix("https://").removePrefix("www."), style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.clickable { openUrl(ctx, d.homepage) })
                        }
                        TextButton(enabled = !added, onClick = {
                            scope.launch {
                                when (val r = c.repo.addFromDirectory(d)) {
                                    is AddResult.Added -> c.refresh("new source", sourceIds = listOf(r.id))
                                    is AddResult.Duplicate -> toast(ctx, "Already added")
                                    is AddResult.Invalid -> toast(ctx, r.message)
                                }
                            }
                        }) { Text(if (added) "Added" else "Add") }
                    }
                }
            }
        }
    }
}

private val intervals = listOf(15, 30, 60, 120, 240, 720, 1440)

@Composable
fun SourceDetailScreen(id: Long, nav: Nav) {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val source by remember(id) { c.db.sources().observe(id) }.collectAsState(null)
    var limit by remember { mutableIntStateOf(60) }
    val articles by remember(id, limit) { c.db.articles().observeList(ArticleQuery.build(ArticleFilter(sourceId = id, limit = limit))) }.collectAsState(emptyList())
    var confirmDelete by remember { mutableStateOf(false) }
    var articleCount by remember { mutableIntStateOf(0) }
    var editName by remember { mutableStateOf(false) }
    LaunchedEffect(id, articles.size) { articleCount = c.db.articles().countForSource(id) }

    Column(Modifier.fillMaxSize()) {
        val s = source
        DetailBar(s?.name ?: "Source", nav)
        if (s == null) return@Column
        ArticleList(articles, nav, canLoadMore = articles.size >= limit, onLoadMore = { limit += 60 }) {
            item {
                Column(Modifier.padding(16.dp)) {
                    Text(s.url, style = MaterialTheme.typography.labelSmall, modifier = Modifier.clickable { openUrl(ctx, s.url) })
                    s.homepage?.let { Text("Website: $it", style = MaterialTheme.typography.labelSmall, modifier = Modifier.clickable { openUrl(ctx, it) }) }
                    Spacer(Modifier.height(6.dp))
                    Text("Status: ${statusLabel(s.status)}", color = statusColor(s.status), fontWeight = FontWeight.SemiBold)
                    s.statusNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text("Type: ${s.kind} · Category: ${s.category}", style = MaterialTheme.typography.bodySmall)
                    s.placeCode?.let { Text("Publisher location: ${Gazetteer.pathLabel(it)}" + if (s.isLocal) " (local publisher)" else "", style = MaterialTheme.typography.bodySmall) }
                    Text("Last successful update: " + (s.lastSuccessAt?.let(Fmt::full) ?: "never"), style = MaterialTheme.typography.bodySmall)
                    Text("Last attempt: " + (s.lastAttemptAt?.let(Fmt::full) ?: "never"), style = MaterialTheme.typography.bodySmall)
                    s.lastNewArticleAt?.let { Text("Last new article: ${Fmt.full(it)}", style = MaterialTheme.typography.bodySmall) }
                    s.lastError?.let { Text("Last error: $it (${s.consecutiveFailures} consecutive failures)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    Text("$articleCount archived articles", style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Enabled (collect automatically)", Modifier.weight(1f)); Switch(s.enabled, { v -> scope.launch { c.repo.setSourceEnabled(s.id, v) } })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Notify for new articles", Modifier.weight(1f)); Switch(s.notify, { v -> scope.launch { c.repo.setSourceNotify(s.id, v) } })
                    }
                    Text("Check at most every:", style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        intervals.forEach { m ->
                            FilterChip(s.intervalMinutes == m, { scope.launch { c.repo.updateSource(s.copy(intervalMinutes = m)) } },
                                label = { Text(if (m < 60) "$m min" else "${m / 60} h") })
                        }
                    }
                    Text("Background runs also depend on the global schedule and Android's battery management.", style = MaterialTheme.typography.labelSmall)
                    Row {
                        TextButton(onClick = { c.refresh("manual", force = true, sourceIds = listOf(s.id)) }) { Text("Check now") }
                        TextButton(onClick = { editName = true }) { Text("Edit") }
                        TextButton(onClick = { confirmDelete = true }) { Text("Remove…", color = MaterialTheme.colorScheme.error) }
                    }
                }
                SectionHeader("Articles from ${s.name}")
            }
        }
        if (editName) {
            var n by remember { mutableStateOf(s.name) }
            var cat by remember { mutableStateOf(s.category) }
            AlertDialog(
                onDismissRequest = { editName = false }, title = { Text("Edit source") },
                text = {
                    Column {
                        OutlinedTextField(n, { n = it }, label = { Text("Name") }, singleLine = true)
                        OutlinedTextField(cat, { cat = it }, label = { Text("Category") }, singleLine = true)
                    }
                },
                confirmButton = { TextButton(onClick = { scope.launch { c.repo.updateSource(s.copy(name = n.ifBlank { s.name }, category = cat.ifBlank { "Uncategorized" })) }; editName = false }) { Text("Save") } },
            )
        }
        if (confirmDelete) AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove ${s.name}?") },
            text = {
                Text("To stop collecting but keep everything archived, just disable the source.\n\n" +
                    "Removing the source permanently deletes its $articleCount archived articles and all their saved versions. " +
                    "Bookmarked and collected articles from it are deleted too. This can't be undone unless you have a backup.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { val n = c.repo.deleteSourceAndArticles(s.id); toast(ctx, "Removed source and $n articles"); nav.back() }
                }) { Text("Delete source and articles", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false; scope.launch { c.repo.setSourceEnabled(s.id, false) } }) { Text("Just disable") }
            },
        )
    }
}
