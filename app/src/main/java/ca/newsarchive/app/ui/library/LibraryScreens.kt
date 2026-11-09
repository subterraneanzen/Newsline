@file:OptIn(ExperimentalMaterial3Api::class)

package ca.newsarchive.app.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ca.newsarchive.app.LocalContainer
import ca.newsarchive.app.data.ArticleFilter
import ca.newsarchive.app.data.ArticleQuery
import ca.newsarchive.app.data.FollowKind
import ca.newsarchive.app.ui.DetailBar
import ca.newsarchive.app.ui.components.ArticleList
import ca.newsarchive.app.ui.components.EmptyState
import ca.newsarchive.app.ui.components.Nav
import ca.newsarchive.app.ui.components.SectionHeader
import ca.newsarchive.app.ui.enc
import kotlinx.coroutines.launch

private val libraryTabs = listOf("Bookmarks", "Read later", "Collections", "History", "Following")

@Composable
fun LibraryScreen(nav: Nav) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            libraryTabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        when (tab) {
            0 -> FilteredList(ArticleFilter(bookmarked = true, limit = 500), nav, "No bookmarks", "Tap the bookmark icon on any article to keep it here.")
            1 -> FilteredList(ArticleFilter(saved = true, limit = 500), nav, "Nothing saved for later", "Use “Read later” from an article's menu.")
            2 -> CollectionsTab(nav)
            3 -> HistoryTab(nav)
            4 -> FollowingTab(nav)
        }
    }
}

@Composable
private fun FilteredList(filter: ArticleFilter, nav: Nav, emptyTitle: String, emptyBody: String) {
    val c = LocalContainer.current
    val items by remember(filter) { c.db.articles().observeList(ArticleQuery.build(filter)) }.collectAsState(emptyList())
    ArticleList(items, nav, empty = { EmptyState(emptyTitle, emptyBody) })
}

@Composable
private fun HistoryTab(nav: Nav) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    val items by remember { c.db.articles().observeList(ArticleQuery.build(ArticleFilter(history = true, limit = 300))) }.collectAsState(emptyList())
    ArticleList(items, nav, empty = { EmptyState("No reading history", "Articles you open are listed here.") }) {
        if (items.isNotEmpty()) item {
            TextButton(onClick = { confirm = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Clear reading history") }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Clear reading history?") },
        text = { Text("This only clears the list of what you've read. Articles stay in the archive.") },
        confirmButton = { TextButton(onClick = { scope.launch { c.repo.clearHistory() }; confirm = false }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}

@Composable
private fun CollectionsTab(nav: Nav) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val cols by remember { c.db.library().observeCollections() }.collectAsState(emptyList())
    var name by remember { mutableStateOf("") }
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(name, { name = it }, label = { Text("New collection") }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(enabled = name.isNotBlank(), onClick = { val n = name; name = ""; scope.launch { c.repo.createCollection(n) } }) { Text("Create") }
            }
        }
        if (cols.isEmpty()) item { EmptyState("No collections", "Group articles into your own collections, e.g. “Housing crisis” or “Election 2027”.") }
        items(cols.size) { i ->
            val col = cols[i]
            Column(Modifier.fillMaxWidth().clickable { nav.navigate("collection/${col.id}") }.padding(16.dp)) {
                Text(col.name, fontWeight = FontWeight.SemiBold)
                Text("${col.count} articles", style = MaterialTheme.typography.labelSmall)
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        }
    }
}

@Composable
fun CollectionScreen(id: Long, nav: Nav) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val col by remember(id) { c.db.library().observeCollection(id) }.collectAsState(null)
    var rename by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        DetailBar(col?.name ?: "Collection", nav) {
            TextButton(onClick = { rename = true }) { Text("Rename") }
            TextButton(onClick = { delete = true }) { Text("Delete") }
        }
        val items by remember(id) { c.db.articles().observeList(ArticleQuery.build(ArticleFilter(collectionId = id, limit = 2000))) }.collectAsState(emptyList())
        ArticleList(items, nav, empty = { EmptyState("Empty collection", "Add articles from their menu → Add to collection.") })
    }
    if (rename) {
        var n by remember { mutableStateOf(col?.name ?: "") }
        AlertDialog(
            onDismissRequest = { rename = false }, title = { Text("Rename collection") },
            text = { OutlinedTextField(n, { n = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { scope.launch { c.repo.renameCollection(id, n) }; rename = false }) { Text("Save") } },
        )
    }
    if (delete) AlertDialog(
        onDismissRequest = { delete = false }, title = { Text("Delete collection?") },
        text = { Text("The collection is removed. The articles themselves stay in your archive.") },
        confirmButton = { TextButton(onClick = { scope.launch { c.repo.deleteCollection(id) }; delete = false; nav.back() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { delete = false }) { Text("Cancel") } },
    )
}

@Composable
private fun FollowingTab(nav: Nav) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val follows by remember { c.db.library().observeFollows() }.collectAsState(emptyList())
    val sources by remember { c.db.sources().observeAll() }.collectAsState(emptyList())
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize()) {
        item { SectionHeader("Topics, keywords & places") }
        if (follows.isEmpty()) item {
            Text("Follow topics and keywords from the Timeline tab, and places from Explore.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
        }
        items(follows.size) { i ->
            val f = follows[i]
            Row(Modifier.fillMaxWidth().clickable {
                if (f.kind == FollowKind.PLACE) nav.navigate("place/${enc(f.value)}") else nav.navigate("topic/${f.kind}/${enc(f.value)}")
            }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(f.label, fontWeight = FontWeight.SemiBold)
                    Text(when (f.kind) { FollowKind.PLACE -> "Place"; FollowKind.KEYWORD -> "Keyword"; else -> "Topic" } + " · notify", style = MaterialTheme.typography.labelSmall)
                }
                Switch(f.notify, { v -> scope.launch { c.repo.setFollowNotify(f.id, v) } })
                TextButton(onClick = { scope.launch { c.repo.unfollow(f.id) } }) { Text("Unfollow") }
            }
        }
        item { SectionHeader("Publishers (followed = enabled sources)", Modifier.padding(top = 12.dp)) }
        val enabled = sources.filter { it.enabled }
        items(enabled.size) { i ->
            val s = enabled[i]
            Row(Modifier.fillMaxWidth().clickable { nav.openSource(s.id) }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.name, fontWeight = FontWeight.SemiBold)
                    Text("Notify for every new article", style = MaterialTheme.typography.labelSmall)
                }
                Switch(s.notify, { v -> scope.launch { c.repo.setSourceNotify(s.id, v) } })
            }
        }
    }
}
