@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package ca.newsarchive.app.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.InputChip
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ca.newsarchive.app.LocalContainer
import ca.newsarchive.app.data.ArticleFilter
import ca.newsarchive.app.data.ArticleListItem
import ca.newsarchive.app.data.ArticleQuery
import ca.newsarchive.app.data.FollowKind
import ca.newsarchive.app.data.SortOrder
import ca.newsarchive.app.ui.DetailBar
import ca.newsarchive.app.ui.Fmt
import ca.newsarchive.app.ui.components.EmptyState
import ca.newsarchive.app.ui.components.Nav
import ca.newsarchive.app.ui.components.SectionHeader
import ca.newsarchive.app.ui.enc
import ca.newsarchive.app.ui.home.StoryRow
import ca.newsarchive.core.topic.TopicClassifier
import kotlinx.coroutines.launch

enum class TimelineRange(val label: String, val days: Long?) { TODAY("Today", 0), WEEK("7 days", 7), MONTH("30 days", 30), YEAR("Last year", 365), ALL("All archived", null) }

private fun TimelineRange.from(): Long? = when (days) { null -> null; 0L -> Fmt.startOfToday(); else -> System.currentTimeMillis() - days * 86_400_000 }

@Composable
fun TimelineScreen(nav: Nav) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val follows by remember { c.db.library().observeFollows() }.collectAsState(emptyList())
    val since = remember { System.currentTimeMillis() - 7L * 86_400_000 }
    val developing by remember { c.db.stories().observeDeveloping(since, 40) }.collectAsState(emptyList())
    var keyword by remember { mutableStateOf("") }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            SectionHeader("Topics you follow")
            val topicFollows = follows.filter { it.kind == FollowKind.TOPIC || it.kind == FollowKind.KEYWORD }
            Column(Modifier.padding(horizontal = 12.dp)) {
                if (topicFollows.isEmpty()) Text("Follow a topic or keyword to get a running timeline of its coverage.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    topicFollows.forEach { f ->
                        AssistChip(onClick = { nav.navigate("topic/${f.kind}/${enc(f.value)}") },
                            label = { Text(if (f.kind == FollowKind.KEYWORD) "“${f.label}”" else f.label) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(keyword, { keyword = it }, singleLine = true, modifier = Modifier.weight(1f),
                        label = { Text("Follow a keyword (e.g. a company, conflict, person)") })
                    TextButton(enabled = keyword.isNotBlank(), onClick = {
                        val k = keyword.trim(); keyword = ""
                        scope.launch { c.repo.follow(FollowKind.KEYWORD, k, k) }
                    }) { Text("Follow") }
                }
                Text("Built-in topics", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TopicClassifier.topics.keys.forEach { t ->
                        AssistChip(onClick = { nav.navigate("topic/${FollowKind.TOPIC}/${enc(t)}") }, label = { Text(t) })
                    }
                }
            }
        }
        item { SectionHeader("Developing stories · last 7 days", Modifier.padding(top = 16.dp)) }
        if (developing.isEmpty()) item {
            EmptyState("No developing stories yet", "Stories appear here when several articles about the same event are archived.")
        }
        items(developing, key = { it.id }) { s -> StoryRow(s, prominent = s.publisherCount >= 5, nav) }
    }
}

/** Chronological feed for a topic or keyword, grouped by publication day. */
@Composable
fun TopicTimelineScreen(kind: String, value: String, nav: Nav) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var range by rememberSaveable { mutableStateOf(TimelineRange.WEEK) }
    var limit by remember { mutableIntStateOf(200) }
    val follows by remember { c.db.library().observeFollows() }.collectAsState(emptyList())
    val follow = follows.firstOrNull { it.kind == kind && it.value == value }
    val filter = when (kind) {
        FollowKind.KEYWORD -> ArticleFilter(text = value, from = range.from(), sort = SortOrder.NEWEST, limit = limit)
        else -> ArticleFilter(topic = value, from = range.from(), sort = SortOrder.NEWEST, limit = limit)
    }
    val items by remember(filter) { c.db.articles().observeList(ArticleQuery.build(filter)) }.collectAsState(null)

    Column(Modifier.fillMaxSize()) {
        DetailBar(if (kind == FollowKind.KEYWORD) "“$value”" else value, nav) {
            TextButton(onClick = {
                scope.launch { if (follow != null) c.repo.unfollow(follow.id) else c.repo.follow(kind, value, value) }
            }) { Text(if (follow != null) "Following" else "Follow") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TimelineRange.entries.forEach { r -> FilterChip(range == r, { range = r; limit = 200 }, label = { Text(r.label) }) }
        }
        if (follow != null) Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Notify me about new articles", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            androidx.compose.material3.Switch(follow.notify, { v -> scope.launch { c.repo.setFollowNotify(follow.id, v) } })
        }
        Text(
            "Dates shown are publication dates reported by each publisher (or the discovery date when none was given), not the date events occurred.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        val list = items ?: return@Column
        if (list.isEmpty()) { EmptyState("Nothing in this period", "Try a longer range."); return@Column }
        val byDay = list.groupBy { Fmt.dayKey(it.sortTime) }
        LazyColumn(Modifier.fillMaxSize()) {
            byDay.forEach { (_, dayItems) ->
                item { DayHeader(Fmt.dayHeader(dayItems.first().sortTime), dayItems.size) }
                items(dayItems, key = { it.id }) { a -> TimelineEntry(a, nav, list) }
            }
            if (list.size >= limit) item { TextButton(onClick = { limit += 200 }, modifier = Modifier.fillMaxWidth()) { Text("Load more") } }
        }
    }
}

@Composable
private fun DayHeader(label: String, count: Int) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Text("$count", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun TimelineEntry(a: ArticleListItem, nav: Nav, list: List<ArticleListItem>) {
    Row(Modifier.fillMaxWidth().clickable { nav.openArticle(a.id, list.map { it.id }) }.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.width(64.dp)) {
            Text(Fmt.relative(a.sortTime).substringAfter("Today ").substringAfter("Yesterday "), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            if (a.publishedAt == null) Text("(found)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.padding(top = 4.dp, end = 10.dp).size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        Column(Modifier.weight(1f)) {
            Text(a.title, style = MaterialTheme.typography.titleMedium)
            val extra = listOfNotNull(
                a.sourceName, Fmt.articleType(a.articleType),
                if (a.publisherCount >= 2) "${a.publisherCount} outlets" else null,
                if (a.versionCount > 1) "updated" else null,
            )
            Text(extra.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (a.storyId != null && a.publisherCount >= 2) TextButton(onClick = { nav.openStory(a.storyId) }) { Text("Story timeline") }
        }
    }
    HorizontalDivider(Modifier.padding(start = 90.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

private data class StoryEvent(val time: Long, val articleId: Long, val publisher: String, val headline: String, val detail: String, val isUpdate: Boolean, val dateKnown: Boolean)

/** Chronology of one story: each publisher's report plus later captured updates. */
@Composable
fun StoryTimelineScreen(storyId: Long, nav: Nav) {
    val c = LocalContainer.current
    val story by remember(storyId) { c.db.stories().observe(storyId) }.collectAsState(null)
    var events by remember { mutableStateOf<List<StoryEvent>?>(null) }
    LaunchedEffect(storyId, story?.articleCount) {
        val arts = c.db.articles().inStory(storyId)
        val ev = mutableListOf<StoryEvent>()
        for (a in arts) {
            val pub = c.db.sources().get(a.sourceId)?.name ?: ""
            ev += StoryEvent(a.sortTime, a.id, pub, c.db.versions().allFor(a.id).firstOrNull()?.title ?: a.title,
                if (a.publishedAt != null) "Published" else "Discovered (no publication date given)", false, a.publishedAt != null)
            c.db.versions().allFor(a.id).filter { it.versionNo > 1 }.forEach { v ->
                ev += StoryEvent(v.capturedAt, a.id, pub, v.title, "Change captured by this app: ${v.changeSummary ?: "updated"}", true, false)
            }
        }
        events = ev.sortedBy { it.time }
    }
    Column(Modifier.fillMaxSize()) {
        DetailBar("Story timeline", nav) {
            if ((story?.publisherCount ?: 0) >= 3) TextButton(onClick = { nav.openCompare(storyId) }) { Text("Compare") }
        }
        val list = events ?: return@Column
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(Modifier.padding(16.dp)) {
                    story?.let { Text(it.title, style = MaterialTheme.typography.titleLarge) }
                    Spacer(Modifier.height(4.dp))
                    Text("Initial report first, then later reports and updates. Update times are when this app noticed the change, which may be after the publisher made it.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(list.size) { i ->
                val e = list[i]
                Row(Modifier.fillMaxWidth().clickable { nav.openArticle(e.articleId, list.map { it.articleId }.distinct()) }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Column(Modifier.width(96.dp)) {
                        Text(Fmt.relative(e.time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (i == 0) Text("FIRST REPORT", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    Box(Modifier.padding(top = 4.dp, end = 10.dp).size(if (e.isUpdate) 6.dp else 10.dp).clip(CircleShape)
                        .background(if (e.isUpdate) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary))
                    Column(Modifier.weight(1f)) {
                        Text(e.publisher.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        Text(e.headline, style = if (e.isUpdate) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium)
                        Text(e.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(Modifier.padding(start = 122.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}
