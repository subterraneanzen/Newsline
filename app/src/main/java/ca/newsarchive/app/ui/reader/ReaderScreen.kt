@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package ca.newsarchive.app.ui.reader

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import ca.newsarchive.app.LocalContainer
import ca.newsarchive.app.data.Availability
import ca.newsarchive.app.data.FollowKind
import ca.newsarchive.app.data.ThemeMode
import ca.newsarchive.app.ui.Fmt
import ca.newsarchive.app.ui.components.CollectionPickerDialog
import ca.newsarchive.app.ui.components.Nav
import ca.newsarchive.app.ui.components.ReaderQueue
import ca.newsarchive.app.ui.components.RemoteImage
import ca.newsarchive.app.ui.components.copyLink
import ca.newsarchive.app.ui.components.openUrl
import ca.newsarchive.app.ui.components.share
import ca.newsarchive.app.ui.components.toast
import ca.newsarchive.app.ui.enc
import ca.newsarchive.app.ui.theme.Serif
import ca.newsarchive.core.geo.Gazetteer
import ca.newsarchive.core.model.BlockType
import ca.newsarchive.core.topic.TopicClassifier
import kotlinx.coroutines.launch

@Composable
fun ReaderScreen(articleId: Long, nav: Nav) {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by c.settings.flow.collectAsState()
    val article by remember(articleId) { c.db.articles().observe(articleId) }.collectAsState(null)
    val version by remember(articleId) { c.db.versions().observeLatest(articleId) }.collectAsState(null)
    val geo by remember(articleId) { c.db.tags().observeGeo(articleId) }.collectAsState(emptyList())
    val topics by remember(articleId) { c.db.tags().observeTopics(articleId) }.collectAsState(emptyList())
    val source by remember(article?.sourceId) { c.db.sources().observe(article?.sourceId ?: -1) }.collectAsState(null)
    val story by remember(article?.storyId) { c.db.stories().observe(article?.storyId ?: -1) }.collectAsState(null)
    var menu by remember { mutableStateOf(false) }
    var showPrefs by remember { mutableStateOf(false) }
    var editTags by remember { mutableStateOf(false) }
    var pickCollection by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.io.exportArticleText(articleId, uri) } }
                .onSuccess { toast(ctx, "Article exported") }.onFailure { toast(ctx, "Export failed: ${it.message}") }
        }
    }

    LaunchedEffect(articleId) { c.repo.markRead(articleId, true) }

    val queue = ReaderQueue.ids
    val pos = queue.indexOf(articleId)
    val prevId = if (pos > 0) queue[pos - 1] else null
    val nextId = if (pos >= 0 && pos < queue.size - 1) queue[pos + 1] else null

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(source?.name ?: "", style = MaterialTheme.typography.titleMedium, maxLines = 1) },
            navigationIcon = { IconButton(onClick = nav.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                val a = article
                if (a != null) {
                    IconButton(onClick = { scope.launch { c.repo.setBookmarked(a.id, !a.isBookmarked) } }) {
                        Icon(if (a.isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, "Bookmark")
                    }
                    IconButton(onClick = { share(ctx, a.title, a.url) }) { Icon(Icons.Outlined.Share, "Share") }
                    IconButton(onClick = { nav.navigate("history/${a.id}") }) { Icon(Icons.Outlined.History, "Article history") }
                    IconButton(onClick = { showPrefs = true }) { Icon(Icons.Outlined.TextFields, "Reading settings") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Open original article") }, onClick = { menu = false; openUrl(ctx, a.url) })
                            DropdownMenuItem({ Text("Copy original link") }, onClick = { menu = false; copyLink(ctx, a.url) })
                            DropdownMenuItem({ Text(if (a.isSaved) "Remove from Read later" else "Read later") }, onClick = { menu = false; scope.launch { c.repo.setSaved(a.id, !a.isSaved) } })
                            DropdownMenuItem({ Text("Add to collection…") }, onClick = { menu = false; pickCollection = true })
                            DropdownMenuItem({ Text("Mark as unread") }, onClick = { menu = false; scope.launch { c.repo.markRead(a.id, false) }; nav.back() })
                            DropdownMenuItem({ Text("Edit places & topics") }, onClick = { menu = false; editTags = true })
                            DropdownMenuItem({ Text("Export as text file…") }, onClick = { menu = false; exportLauncher.launch(Fmt.fileSafe(a.title).take(60) + ".txt") })
                            DropdownMenuItem({ Text("Publisher: all articles") }, onClick = { menu = false; nav.openSource(a.sourceId) })
                            if (a.storyId != null) DropdownMenuItem({ Text("Remove from this story grouping") }, onClick = {
                                menu = false; scope.launch { c.repo.detachFromStory(a.id) }
                            })
                        }
                    }
                }
            },
        )
        val a = article
        val v = version
        if (a == null || v == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Loading…") }
            return@Column
        }
        val bodySize = (17 * settings.fontScale).sp
        val lineHeight = (settings.lineSpacing).em
        val blocks = v.blocks()
        val images = v.images?.lines()?.filter { it.isNotBlank() }.orEmpty()

        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Fmt.articleType(a.articleType)?.let {
                        Text(it.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.height(4.dp))
                    }
                    SelectionContainer {
                        Text(v.title, fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = (26 * settings.fontScale).sp, lineHeight = (32 * settings.fontScale).sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(listOfNotNull(source?.name, v.author?.let { "By $it" }).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    DateLines(a.publishedAt, a.publisherModifiedAt, a.discoveredAt, v.capturedAt, v.versionNo, a.versionCount)
                }
            }
            item { Notices(a.availability, a.unavailableSince, a.extractionStatus, a.extractionNote, a.versionCount, onOriginal = { openUrl(ctx, a.url) }, onHistory = { nav.navigate("history/${a.id}") }) }
            story?.takeIf { it.publisherCount >= 2 }?.let { st ->
                item {
                    Card(Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Covered by ${st.publisherCount} publishers (${st.articleCount} articles)", fontWeight = FontWeight.SemiBold)
                            Row {
                                if (st.publisherCount >= 3) TextButton(onClick = { nav.openCompare(st.id) }) { Text("Compare coverage") }
                                TextButton(onClick = { nav.openStory(st.id) }) { Text("Story timeline") }
                            }
                        }
                    }
                }
            }
            if (settings.showImages && a.imageUrl != null) item {
                RemoteImage(a.imageUrl, Modifier.padding(vertical = 8.dp).fillMaxWidth().aspectRatio(16f / 9f))
            }
            if (blocks.isEmpty()) item {
                Text("No article text was archived for this item.", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic,
                    modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(blocks.size) { i ->
                val b = blocks[i]
                SelectionContainer {
                    when (b.type) {
                        BlockType.HEADING -> Text(b.text, fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = bodySize * 1.15f,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp))
                        BlockType.QUOTE -> Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                            Box(Modifier.width(3.dp).height(24.dp).background(MaterialTheme.colorScheme.outline))
                            Spacer(Modifier.width(10.dp))
                            Text(b.text, fontFamily = Serif, fontStyle = FontStyle.Italic, fontSize = bodySize, lineHeight = lineHeight)
                        }
                        BlockType.LIST_ITEM -> Text("•  " + b.text, fontFamily = Serif, fontSize = bodySize, lineHeight = lineHeight,
                            modifier = Modifier.padding(start = 28.dp, end = 20.dp, top = 2.dp, bottom = 2.dp))
                        BlockType.PARAGRAPH -> Text(b.text, fontFamily = Serif, fontSize = bodySize, lineHeight = lineHeight,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 7.dp))
                    }
                }
            }
            if (settings.showImages) {
                val extra = images.filter { it != a.imageUrl }.take(6)
                items(extra) { url -> RemoteImage(url, Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth().aspectRatio(16f / 10f)) }
            }
            item {
                Column(Modifier.padding(20.dp)) {
                    HorizontalDivider()
                    Spacer(Modifier.height(10.dp))
                    if (geo.isNotEmpty() || topics.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            geo.filter { it.level != "CONTINENT" }.forEach { g ->
                                val name = if (g.code == Gazetteer.SPACE_CODE) "Space" else Gazetteer.get(g.code)?.name ?: g.code
                                AssistChip(onClick = { nav.navigate("place/${enc(g.code)}") }, label = { Text(if (g.inferred) "$name (from source)" else name) })
                            }
                            topics.forEach { t -> AssistChip(onClick = { nav.navigate("topic/${FollowKind.TOPIC}/${enc(t.topic)}") }, label = { Text(t.topic) }) }
                        }
                        TextButton(onClick = { editTags = true }) { Text("Correct places / topics") }
                    }
                    Text(a.url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { openUrl(ctx, a.url) }) { Text("Open original") }
                        OutlinedButton(onClick = { nav.navigate("history/${a.id}") }) { Text("History (${a.versionCount})") }
                    }
                }
            }
        }
        if (prevId != null || nextId != null) {
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(enabled = prevId != null, onClick = { prevId?.let { nav.back(); nav.openArticle(it, queue) } }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null); Spacer(Modifier.width(4.dp)); Text("Previous")
                }
                TextButton(enabled = nextId != null, onClick = { nextId?.let { nav.back(); nav.openArticle(it, queue) } }) {
                    Text("Next"); Spacer(Modifier.width(4.dp)); Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
                }
            }
        }
    }

    if (showPrefs) ReadingPrefsDialog { showPrefs = false }
    if (editTags) EditTagsDialog(articleId) { editTags = false }
    if (pickCollection) CollectionPickerDialog(articleId) { pickCollection = false }
}

@Composable
private fun DateLines(published: Long?, modified: Long?, discovered: Long, captured: Long, versionNo: Int, versionCount: Int) {
    val style = MaterialTheme.typography.labelMedium
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Text("Published: " + (published?.let(Fmt::full) ?: "not provided by publisher"), style = style, color = muted)
    modified?.let { Text("Updated (per publisher): ${Fmt.full(it)}", style = style, color = muted) }
    Text("Discovered by this app: ${Fmt.full(discovered)}", style = style, color = muted)
    Text("Showing snapshot captured ${Fmt.full(captured)} · version $versionNo of $versionCount", style = style, color = muted)
}

@Composable
private fun Notices(availability: String, unavailableSince: Long?, status: String, note: String?, versions: Int, onOriginal: () -> Unit, onHistory: () -> Unit) {
    val messages = buildList {
        if (availability == Availability.GONE) add("This article is no longer available from the publisher" +
            (unavailableSince?.let { " (first noticed ${Fmt.full(it)})" } ?: "") + ". The archived copy is shown.")
        if (availability == Availability.UNREACHABLE) add("The last attempt to re-check this article failed. The archived copy is shown.")
        when (status) {
            "FEED_ONLY", "FAILED" -> add(note ?: "The full article could not be extracted. Only the feed summary was saved.")
            "PARTIAL" -> add(note ?: "Only part of the article could be extracted.")
            "PAYWALLED" -> add(note ?: "The publisher marks this article as subscriber-only. Only freely accessible text was saved.")
        }
        if (status == "FULL" && note != null) add(note)
    }
    if (messages.isEmpty() && versions <= 1) return
    Card(Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp)) {
            messages.forEach { Text(it, style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(4.dp)) }
            if (versions > 1) Text("This article has changed since it was first archived ($versions versions).", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            Row {
                if (status != "FULL" || availability != Availability.AVAILABLE) TextButton(onClick = onOriginal) { Text("Open original") }
                if (versions > 1) TextButton(onClick = onHistory) { Text("See changes") }
            }
        }
    }
}

@Composable
fun ReadingPrefsDialog(onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val s by c.settings.flow.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reading") },
        text = {
            Column {
                Text("Text size: ${(s.fontScale * 100).toInt()}%")
                Slider(s.fontScale, { v -> c.settings.update { it.copy(fontScale = v) } }, valueRange = 0.8f..1.6f, steps = 7)
                Text("Line spacing: ${"%.1f".format(s.lineSpacing)}")
                Slider(s.lineSpacing, { v -> c.settings.update { it.copy(lineSpacing = v) } }, valueRange = 1.2f..2.2f, steps = 9)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Show images", Modifier.weight(1f)); Switch(s.showImages, { v -> c.settings.update { it.copy(showImages = v) } })
                }
                Text("Theme")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ThemeMode.entries.forEach { m ->
                        FilterChip(s.theme == m, { c.settings.update { it.copy(theme = m) } }, label = { Text(m.name.lowercase().replaceFirstChar { it.uppercase() }) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
fun EditTagsDialog(articleId: Long, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val geo by remember(articleId) { c.db.tags().observeGeo(articleId) }.collectAsState(emptyList())
    val topics by remember(articleId) { c.db.tags().observeTopics(articleId) }.collectAsState(emptyList())
    var q by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Places & topics") },
        text = {
            LazyColumn {
                item {
                    Text("Assigned automatically from the article text. Corrections you make are kept when the article is re-checked.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text("Places", fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        geo.forEach { g ->
                            val name = if (g.code == Gazetteer.SPACE_CODE) "Space" else Gazetteer.get(g.code)?.name ?: g.code
                            InputChip(selected = false, onClick = { scope.launch { c.repo.removeGeoTag(articleId, g.code) } },
                                label = { Text("$name ✕") })
                        }
                    }
                    OutlinedTextField(q, { q = it }, label = { Text("Add place (city, province, country…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                val results = if (q.length >= 2) Gazetteer.search(q) else emptyList()
                items(results.take(8)) { p ->
                    TextButton(onClick = { scope.launch { c.repo.addGeoTag(articleId, p.code); q = "" } }) { Text(Gazetteer.pathLabel(p.code)) }
                }
                item {
                    if (q.equals("space", true)) TextButton(onClick = { scope.launch { c.repo.addGeoTag(articleId, Gazetteer.SPACE_CODE); q = "" } }) { Text("Space") }
                    Spacer(Modifier.height(8.dp))
                    Text("Topics", fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TopicClassifier.topics.keys.forEach { t ->
                            val has = topics.any { it.topic == t }
                            FilterChip(has, { scope.launch { if (has) c.repo.removeTopic(articleId, t) else c.repo.addTopic(articleId, t) } }, label = { Text(t) })
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Done") } },
    )
}
