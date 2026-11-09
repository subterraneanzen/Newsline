@file:OptIn(ExperimentalMaterial3Api::class)

package ca.newsarchive.app.ui.search

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import ca.newsarchive.app.LocalContainer
import ca.newsarchive.app.data.ArticleFilter
import ca.newsarchive.app.data.ArticleQuery
import ca.newsarchive.app.data.SortOrder
import ca.newsarchive.app.ui.components.ArticleList
import ca.newsarchive.app.ui.components.EmptyState
import ca.newsarchive.app.ui.components.Nav
import ca.newsarchive.app.ui.explore.DateRange
import ca.newsarchive.app.ui.explore.articleTypes
import ca.newsarchive.app.ui.explore.from
import ca.newsarchive.core.geo.Gazetteer
import ca.newsarchive.core.topic.TopicClassifier
import kotlinx.coroutines.delay

/** Full-text search across everything ever archived (SQLite FTS4), with filters. */
@Composable
fun SearchScreen(initial: String, nav: Nav) {
    val c = LocalContainer.current
    var text by rememberSaveable { mutableStateOf(initial) }
    var debounced by remember { mutableStateOf(initial) }
    var range by rememberSaveable { mutableStateOf(DateRange.ANY) }
    var type by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceId by rememberSaveable { mutableStateOf<Long?>(null) }
    var place by rememberSaveable { mutableStateOf<String?>(null) }
    var topic by rememberSaveable { mutableStateOf<String?>(null) }
    var oldestFirst by rememberSaveable { mutableStateOf(false) }
    var limit by remember { mutableIntStateOf(100) }
    var placeQuery by remember { mutableStateOf("") }
    var sourceMenu by remember { mutableStateOf(false) }
    var topicMenu by remember { mutableStateOf(false) }
    val sources by remember { c.db.sources().observeAll() }.collectAsState(emptyList())
    val focus = remember { FocusRequester() }

    LaunchedEffect(text) { delay(250); debounced = text }
    LaunchedEffect(Unit) { if (initial.isEmpty()) runCatching { focus.requestFocus() } }

    val anyFilter = range != DateRange.ANY || type != null || sourceId != null || place != null || topic != null
    val filter = ArticleFilter(
        text = debounced.ifBlank { null }, from = range.from(), articleType = type, sourceId = sourceId, placeCode = place, topic = topic,
        sort = if (oldestFirst) SortOrder.OLDEST else SortOrder.NEWEST, limit = limit,
    )
    val active = debounced.isNotBlank() || anyFilter
    val items by remember(filter, active) {
        if (active) c.db.articles().observeList(ArticleQuery.build(filter)) else kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsState(emptyList())

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = nav.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            OutlinedTextField(text, { text = it; limit = 100 }, singleLine = true, placeholder = { Text("Search headlines, text, authors, places…") },
                modifier = Modifier.weight(1f).focusRequester(focus))
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DateRange.entries.forEach { r -> FilterChip(range == r, { range = r }, label = { Text(r.label) }) }
            FilterChip(oldestFirst, { oldestFirst = !oldestFirst }, label = { Text("Oldest first") })
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box {
                FilterChip(sourceId != null, { sourceMenu = true }, label = { Text(sources.firstOrNull { it.id == sourceId }?.name ?: "Publisher") })
                DropdownMenu(sourceMenu, { sourceMenu = false }) {
                    DropdownMenuItem({ Text("Any publisher") }, onClick = { sourceId = null; sourceMenu = false })
                    sources.forEach { s -> DropdownMenuItem({ Text(s.name) }, onClick = { sourceId = s.id; sourceMenu = false }) }
                }
            }
            Box {
                FilterChip(topic != null, { topicMenu = true }, label = { Text(topic ?: "Topic") })
                DropdownMenu(topicMenu, { topicMenu = false }) {
                    DropdownMenuItem({ Text("Any topic") }, onClick = { topic = null; topicMenu = false })
                    TopicClassifier.topics.keys.forEach { t -> DropdownMenuItem({ Text(t) }, onClick = { topic = t; topicMenu = false }) }
                }
            }
            articleTypes.forEach { (v, l) -> if (v != null) FilterChip(type == v, { type = if (type == v) null else v }, label = { Text(l) }) }
        }
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (place != null) {
                AssistChip(onClick = { place = null }, label = { Text("Place: ${Gazetteer.pathLabel(place!!).ifBlank { place!! }}  ✕") })
            } else {
                OutlinedTextField(placeQuery, { placeQuery = it }, singleLine = true, label = { Text("Filter by city, province/state or country") }, modifier = Modifier.fillMaxWidth())
            }
        }
        if (place == null && placeQuery.length >= 2) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                Gazetteer.search(placeQuery).take(10).forEach { p -> TextButton(onClick = { place = p.code; placeQuery = "" }) { Text(p.name) } }
                if ("space".startsWith(placeQuery.lowercase())) TextButton(onClick = { place = Gazetteer.SPACE_CODE; placeQuery = "" }) { Text("Space") }
            }
        }
        if (!active) {
            EmptyState("Search your archive", "Searches every article ever collected, including older and updated ones. Use quotes for exact phrases, e.g. \"carbon tax\".")
        } else {
            Text("${items.size}${if (items.size >= limit) "+" else ""} results", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp))
            ArticleList(items, nav, canLoadMore = items.size >= limit, onLoadMore = { limit += 100 },
                empty = { EmptyState("No matches", "Try fewer words or remove filters.") })
        }
    }
}
