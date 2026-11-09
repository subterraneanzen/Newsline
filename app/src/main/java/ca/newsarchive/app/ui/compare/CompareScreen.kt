@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ca.newsarchive.app.ui.compare

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ca.newsarchive.app.LocalContainer
import ca.newsarchive.app.data.ArticleEntity
import ca.newsarchive.app.data.ArticleVersionEntity
import ca.newsarchive.app.ingest.StoryService
import ca.newsarchive.app.ui.DetailBar
import ca.newsarchive.app.ui.Fmt
import ca.newsarchive.app.ui.components.Nav
import ca.newsarchive.app.ui.components.openUrl
import ca.newsarchive.app.ui.theme.Serif

private data class CoverageRow(val a: ArticleEntity, val publisher: String, val first: ArticleVersionEntity?, val latest: ArticleVersionEntity?)

/**
 * Side-by-side listing of how each publisher covered one story. Purely descriptive: headlines,
 * times, summaries and links as published. No ranking, bias labels or judgement of correctness.
 */
@Composable
fun CompareScreen(storyId: Long, nav: Nav) {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val story by remember(storyId) { c.db.stories().observe(storyId) }.collectAsState(null)
    var rows by remember { mutableStateOf<List<CoverageRow>?>(null) }
    LaunchedEffect(storyId, story?.articleCount) {
        val arts = c.db.articles().inStory(storyId)
        rows = arts.map { a ->
            val vs = c.db.versions().allFor(a.id)
            CoverageRow(a, c.db.sources().get(a.sourceId)?.name ?: StoryService.publisherKey(a.url), vs.firstOrNull(), vs.lastOrNull())
        }
    }
    Column(Modifier.fillMaxSize()) {
        DetailBar("Compare coverage", nav)
        val st = story
        val list = rows ?: return@Column
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(Modifier.padding(16.dp)) {
                    st?.let {
                        Text(it.title, style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(4.dp))
                        Text("${it.publisherCount} publishers · ${it.articleCount} articles · first report ${Fmt.full(it.firstPublishedAt)}",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Articles were grouped automatically by similar headlines, names and timing. Grouping can be wrong; " +
                            "remove an article from the group from its reader menu. Listed in order of publication.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { nav.openStory(storyId) }) { Text("View as story timeline") }
                }
            }
            items(list.sortedBy { it.a.sortTime }, key = { it.a.id }) { r ->
                Card(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth(), onClick = { nav.openArticle(r.a.id, list.map { it.a.id }) }) {
                    Column(Modifier.padding(14.dp)) {
                        Text(r.publisher.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        Fmt.articleType(r.a.articleType)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary) }
                        Spacer(Modifier.height(4.dp))
                        val original = r.first?.title ?: r.a.title
                        Text(original, fontFamily = Serif, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                        if (r.latest != null && r.latest.versionNo > 1 && r.latest.title != original) {
                            Text("Current headline: ${r.latest.title}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("Published: " + (r.a.publishedAt?.let(Fmt::full) ?: "not provided") + " · discovered ${Fmt.relative(r.a.discoveredAt)}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (r.a.versionCount > 1) Text("${r.a.versionCount} versions archived · latest captured ${Fmt.relative(r.latest?.capturedAt ?: 0)}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val summary = r.a.summary ?: r.latest?.paragraphs()?.firstOrNull()
                        summary?.let { Spacer(Modifier.height(6.dp)); Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                        Row {
                            TextButton(onClick = { nav.openArticle(r.a.id, list.map { it.a.id }) }) { Text("Read archived") }
                            TextButton(onClick = { openUrl(ctx, r.a.url) }) { Text("Original") }
                            if (r.a.versionCount > 1) TextButton(onClick = { nav.navigate("history/${r.a.id}") }) { Text("Changes") }
                        }
                    }
                }
            }
        }
    }
}
