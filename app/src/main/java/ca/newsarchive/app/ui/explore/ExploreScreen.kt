@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package ca.newsarchive.app.ui.explore

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ca.newsarchive.app.LocalContainer
import ca.newsarchive.app.data.ArticleFilter
import ca.newsarchive.app.data.ArticleQuery
import ca.newsarchive.app.data.FollowKind
import ca.newsarchive.app.ui.DetailBar
import ca.newsarchive.app.ui.Fmt
import ca.newsarchive.app.ui.components.ArticleList
import ca.newsarchive.app.ui.components.EmptyState
import ca.newsarchive.app.ui.components.Nav
import ca.newsarchive.app.ui.components.SectionHeader
import ca.newsarchive.app.ui.enc
import ca.newsarchive.app.ui.home.sectionTitle
import ca.newsarchive.app.ui.home.spaceDefenseFilter
import ca.newsarchive.app.data.HomeSection
import ca.newsarchive.core.geo.Gazetteer
import ca.newsarchive.core.topic.TopicClassifier
import kotlinx.coroutines.launch

enum class DateRange(val label: String) { ANY("Any time"), TODAY("Today"), WEEK("7 days"), MONTH("30 days"), YEAR("1 year") }

fun DateRange.from(): Long? = when (this) {
    DateRange.ANY -> null
    DateRange.TODAY -> Fmt.startOfToday()
    DateRange.WEEK -> System.currentTimeMillis() - 7L * 86_400_000
    DateRange.MONTH -> System.currentTimeMillis() - 30L * 86_400_000
    DateRange.YEAR -> System.currentTimeMillis() - 365L * 86_400_000
}

val articleTypes = listOf(null to "All types", "OPINION" to "Opinion", "EDITORIAL" to "Editorial", "ANALYSIS" to "Analysis", "REVIEW" to "Review")

@Composable
fun ExploreScreen(nav: Nav) = PlaceBrowser(Gazetteer.WORLD_CODE, nav, showTopics = true)

@Composable
fun PlaceScreen(code: String, nav: Nav) {
    Column(Modifier.fillMaxSize()) {
        DetailBar(if (code == "UPDATED") "Recently updated articles" else sectionTitle(code, LocalContainer.current.settings.current.homePlace), nav)
        PlaceBrowser(code, nav, showTopics = false)
    }
}

/**
 * Geographic drill-down: World → continent → country → province/state → city, plus Space.
 * The date-range and article-type filters stay applied while moving between levels.
 */
@Composable
fun PlaceBrowser(initial: String, nav: Nav, showTopics: Boolean) {
    val c = LocalContainer.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var code by rememberSaveable { mutableStateOf(initial) }
    var range by rememberSaveable { mutableStateOf(DateRange.ANY) }
    var type by rememberSaveable { mutableStateOf<String?>(null) }
    var limit by remember { mutableIntStateOf(60) }
    val counts by remember { c.db.tags().observeGeoCounts() }.collectAsState(emptyList())
    val countMap = remember(counts) { counts.associate { it.name to it.count } }
    val follows by remember { c.db.library().observeFollows() }.collectAsState(emptyList())

    val filter = when (code) {
        "UPDATED" -> ArticleFilter(minVersions = 2, from = range.from(), articleType = type, limit = limit)
        HomeSection.SPACE_DEFENSE -> spaceDefenseFilter(limit, range.from(), type)
        else -> ArticleFilter(placeCode = code, from = range.from(), articleType = type, limit = limit)
    }
    val items by remember(filter) { c.db.articles().observeList(ArticleQuery.build(filter)) }.collectAsState(emptyList())

    ArticleList(
        items, nav, canLoadMore = items.size >= limit, onLoadMore = { limit += 60 },
        empty = { EmptyState("No articles", "Nothing archived for this place and filter yet. Places are assigned from article text; you can also tag articles manually from the reader.") },
    ) {
        item {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                // Breadcrumbs
                if (code != "UPDATED" && code != HomeSection.SPACE_DEFENSE) {
                    val chain = (listOfNotNull(Gazetteer.get(code)) + Gazetteer.ancestors(code)).reversed()
                    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { code = Gazetteer.WORLD_CODE; limit = 60 }) { Text("World") }
                        if (code == Gazetteer.SPACE_CODE) { Text("›"); TextButton(onClick = {}) { Text("Space") } }
                        chain.forEach { p -> Text("›"); TextButton(onClick = { code = p.code; limit = 60 }) { Text(p.name) } }
                    }
                }
                // Children of the current level that have articles (World also offers Space).
                val children = when (code) {
                    Gazetteer.WORLD_CODE -> Gazetteer.continents()
                    Gazetteer.SPACE_CODE, "UPDATED", HomeSection.SPACE_DEFENSE -> emptyList()
                    else -> Gazetteer.children(code)
                }.filter { (countMap[it.code] ?: 0) > 0 }.sortedByDescending { countMap[it.code] ?: 0 }
                if (children.isNotEmpty() || code == Gazetteer.WORLD_CODE) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (code == Gazetteer.WORLD_CODE) AssistChip(onClick = { code = Gazetteer.SPACE_CODE; limit = 60 },
                            label = { Text("Space (${countMap[Gazetteer.SPACE_CODE] ?: 0})") })
                        children.forEach { p ->
                            AssistChip(onClick = { code = p.code; limit = 60 }, label = { Text("${p.name} (${countMap[p.code] ?: 0})") })
                        }
                    }
                }
                // Filters (kept while navigating levels)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DateRange.entries.forEach { r -> FilterChip(selected = range == r, onClick = { range = r }, label = { Text(r.label) }) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    articleTypes.forEach { (v, l) -> FilterChip(selected = type == v, onClick = { type = v }, label = { Text(l) }) }
                }
                if (code != Gazetteer.WORLD_CODE && code != "UPDATED" && code != HomeSection.SPACE_DEFENSE) {
                    val followed = follows.any { it.kind == FollowKind.PLACE && it.value == code }
                    TextButton(onClick = {
                        scope.launch {
                            if (followed) c.repo.unfollow(FollowKind.PLACE, code)
                            else c.repo.follow(FollowKind.PLACE, code, sectionTitle(code, c.settings.current.homePlace))
                        }
                    }) { Text(if (followed) "Following this place ✓" else "Follow this place") }
                }
            }
        }
        if (showTopics && code == Gazetteer.WORLD_CODE) item {
            SectionHeader("Topics")
            FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TopicClassifier.topics.keys.forEach { t ->
                    AssistChip(onClick = { nav.navigate("topic/${FollowKind.TOPIC}/${enc(t)}") }, label = { Text(t) })
                }
            }
            SectionHeader("Latest articles")
        }
        item {
            Text(
                "Showing articles tagged ${if (code == "UPDATED") "with more than one version" else sectionTitle(code, c.settings.current.homePlace)}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}
