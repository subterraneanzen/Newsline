package ca.newsarchive.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.DisposableEffect
import ca.newsarchive.app.data.ArticleListItem
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ca.newsarchive.app.LocalContainer
import ca.newsarchive.app.data.ArticleFilter
import ca.newsarchive.app.data.ArticleQuery
import ca.newsarchive.app.data.HomeSection
import ca.newsarchive.app.data.StoryListItem
import ca.newsarchive.app.ui.Fmt
import ca.newsarchive.app.ui.components.ArticleRow
import ca.newsarchive.app.ui.components.EmptyState
import ca.newsarchive.app.ui.components.Nav
import ca.newsarchive.app.ui.components.SectionHeader
import ca.newsarchive.app.ui.enc
import ca.newsarchive.core.geo.Gazetteer
import ca.newsarchive.core.home.HomePlacement
import ca.newsarchive.core.text.TextNormalizer
import ca.newsarchive.core.text.UrlCanonicalizer
import ca.newsarchive.app.AppContainer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Keeps the home page's scroll position and last-loaded section contents while the user reads an
 * article, so returning lands on the same headline instead of the list collapsing while data reloads.
 */
private object HomeMemory {
    var index = 0
    var offset = 0
    var total = -1
    var placed: Map<String, List<ArticleListItem>>? = null
    var stories: List<StoryListItem>? = null
}

fun sectionTitle(code: String, homePlace: String): String = when (code) {
    HomeSection.LOCAL -> "Local · " + (Gazetteer.get(homePlace)?.name ?: homePlace)
    HomeSection.MULTI -> "Multi-source coverage"
    HomeSection.LATEST -> "Latest"
    HomeSection.UPDATED -> "Recently updated articles"
    Gazetteer.WORLD_CODE -> "World"
    Gazetteer.SPACE_CODE -> "Space"
    else -> Gazetteer.get(code)?.name ?: code
}

@Composable
fun HomeScreen(nav: Nav) {
    val c = LocalContainer.current
    val settings by c.settings.flow.collectAsState()
    val total by remember { c.db.articles().observeCount() }.collectAsState(HomeMemory.total)
    HomeMemory.total = total
    val listState = rememberLazyListState(HomeMemory.index, HomeMemory.offset)
    DisposableEffect(listState) {
        onDispose { HomeMemory.index = listState.firstVisibleItemIndex; HomeMemory.offset = listState.firstVisibleItemScrollOffset }
    }
    val placedFlow = remember(settings.homeSections, settings.homePlace) { homePlacement(c, settings.homeSections, settings.homePlace) }
    val placed by placedFlow.collectAsState(HomeMemory.placed)
    placed?.let { HomeMemory.placed = it }
    val lastSync by remember { c.db.misc().observeLastSync() }.collectAsState(null)

    if (total == 0) {
        EmptyState(
            "Your archive is empty",
            "Articles are collected from the sources you enable. Pull in news now, or choose sources first.",
            Modifier.fillMaxSize(),
        ) {
            Column {
                Button(onClick = { c.refresh("manual", force = true) }) { Text("Collect news now") }
                TextButton(onClick = { nav.navigate("sources") }) { Text("Choose sources") }
            }
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        item {
            Text(
                (lastSync?.let { "Last collection ${Fmt.relative(it)}" } ?: "Not collected yet") + " · ${if (total >= 0) total else 0} articles archived",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        for (code in settings.homeSections) {
            when (code) {
                HomeSection.MULTI -> multiSourceSection(nav)
                else -> {
                    val target = when (code) {
                        HomeSection.LOCAL -> "place/${enc(settings.homePlace)}"
                        HomeSection.LATEST -> "place/${Gazetteer.WORLD_CODE}"
                        HomeSection.UPDATED -> "place/UPDATED"
                        else -> "place/${enc(code)}"
                    }
                    articleSection(code, sectionTitle(code, settings.homePlace), placed?.get(code), placed != null, target, nav, settings.showImages)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/**
 * One flow for every article section on the home page: gathers recent candidates for each section,
 * drops repeats of the same story from the same publisher, then gives each article to its single most
 * pertinent section (see [HomePlacement]).
 */
private fun homePlacement(c: AppContainer, sections: List<String>, homePlace: String): Flow<Map<String, List<ArticleListItem>>> {
    val specs = sections.filter { it != HomeSection.MULTI }.map { code ->
        when (code) {
            HomeSection.LOCAL -> HomePlacement.Place(code, homePlace) to ArticleFilter(placeCode = homePlace, limit = 40)
            HomeSection.LATEST, Gazetteer.WORLD_CODE -> HomePlacement.CatchAll(code) to ArticleFilter(limit = 40)
            HomeSection.UPDATED -> HomePlacement.Updated(code) to ArticleFilter(minVersions = 2, limit = 40)
            else -> HomePlacement.Place(code, code) to ArticleFilter(placeCode = code, limit = 40)
        }
    }
    if (specs.isEmpty()) return flowOf(emptyMap())
    return combine(specs.map { c.db.articles().observeList(ArticleQuery.build(it.second)) }) { it.toList() }.map { lists ->
        val pool = lists.flatten().distinctBy { it.id }.sortedByDescending { it.sortTime }
            .distinctBy { (UrlCanonicalizer.host(it.url) ?: it.url) + "|" + TextNormalizer.clean(it.title).lowercase() }
        val tags = c.db.tags().geoForMany(pool.map { it.id }).groupBy { it.articleId }
        val candidates = pool.map { a ->
            HomePlacement.Candidate(a.id, a.versionCount, tags[a.id].orEmpty().map { HomePlacement.Tag(it.code, it.confidence, it.inferred) })
        }
        val byId = pool.associateBy { it.id }
        HomePlacement.assign(specs.map { it.first }, candidates).mapValues { (_, ids) -> ids.mapNotNull(byId::get) }
    }
}

private fun LazyListScope.articleSection(key: String, title: String, list: List<ArticleListItem>?, loaded: Boolean, target: String, nav: Nav, showImages: Boolean) {
    item(key = "h-$key") {
        SectionHeader(title, Modifier.padding(top = 12.dp)) { TextButton(onClick = { nav.navigate(target) }) { Text("More") } }
    }
    item(key = "b-$key") {
        when {
            !loaded -> Spacer(Modifier.height(40.dp))
            list.isNullOrEmpty() -> Text(
                "No other recent articles for this section. Tap More to see everything tagged here.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            else -> Column {
                list.forEach { a ->
                    ArticleRow(a, onOpen = { nav.openArticle(a.id, list.map { it.id }) }, showImage = showImages,
                        onOpenStory = { sid -> if (a.publisherCount >= 3) nav.openCompare(sid) else nav.openStory(sid) })
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

private fun LazyListScope.multiSourceSection(nav: Nav) {
    item(key = "h-multi") { SectionHeader("Multi-source coverage", Modifier.padding(top = 12.dp)) }
    item(key = "b-multi") {
        val c = LocalContainer.current
        val since = remember { System.currentTimeMillis() - 3L * 86_400_000 }
        val stories by remember { c.db.stories().observeMultiSource(2, since, 12) }.collectAsState(HomeMemory.stories)
        val list = stories
        if (list != null) HomeMemory.stories = list
        when {
            list == null -> Spacer(Modifier.height(40.dp))
            list.isEmpty() -> Text(
                "No story has been covered by two or more publishers in the last three days yet.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp),
            )
            else -> Column {
                // Stories covered by five or more publishers are shown first and larger.
                val (major, minor) = list.partition { it.publisherCount >= 5 }
                major.forEach { StoryRow(it, prominent = true, nav) }
                minor.forEach { StoryRow(it, prominent = false, nav) }
            }
        }
    }
}

@Composable
fun StoryRow(s: StoryListItem, prominent: Boolean, nav: Nav) {
    Column(
        Modifier.fillMaxWidth()
            .clickable { if (s.publisherCount >= 3) nav.openCompare(s.id) else nav.openStory(s.id) }
            .padding(horizontal = 16.dp, vertical = if (prominent) 14.dp else 10.dp),
    ) {
        Text(
            s.title,
            style = if (prominent) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "${s.publisherCount} publishers · ${s.articleCount} articles · latest ${Fmt.relative(s.lastPublishedAt)}",
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary,
        )
        s.publishers?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Row {
            if (s.publisherCount >= 3) TextButton(onClick = { nav.openCompare(s.id) }) { Text("Compare coverage") }
            TextButton(onClick = { nav.openStory(s.id) }) { Text("Story timeline") }
        }
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
}
