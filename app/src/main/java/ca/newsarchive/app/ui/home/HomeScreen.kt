package ca.newsarchive.app.ui.home

import ca.newsarchive.core.filter.OutsideTeamSports
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.DisposableEffect
import ca.newsarchive.app.data.ArticleListItem
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import ca.newsarchive.app.ui.DetailBar
import ca.newsarchive.app.ui.Fmt
import ca.newsarchive.app.ui.enc
import ca.newsarchive.app.ui.components.ArticleList
import ca.newsarchive.app.ui.components.ArticleRow
import ca.newsarchive.app.ui.components.EmptyState
import ca.newsarchive.app.ui.components.Nav
import ca.newsarchive.app.ui.components.CollapsibleSectionHeader
import ca.newsarchive.app.ui.components.SectionCard
import ca.newsarchive.core.geo.Gazetteer
import ca.newsarchive.core.home.HomePlacement
import ca.newsarchive.core.home.RegionSources
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
    /** Finish time of the last collection the page has already taken in. */
    var lastSync: Long? = null
}

fun sectionTitle(code: String, homePlace: String): String = when (code) {
    HomeSection.LOCAL -> "Local · " + (Gazetteer.get(homePlace)?.name ?: homePlace)
    HomeSection.MULTI -> "Multi-source coverage"
    HomeSection.LATEST -> "Latest"
    HomeSection.UPDATED -> "Recently updated articles"
    HomeSection.SPACE_DEFENSE -> "Space Defense News"
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
    val from = remember(settings.feedWindowDays) { windowStart(settings.feedWindowDays) }
    val placedFlow = remember(settings.homeSections, settings.homePlace, from) { homePlacement(c, settings.homeSections, settings.homePlace, from) }
    // New arrivals are shown when a collection finishes; anything arriving in between waits behind a
    // "Show new" button so the page doesn't reshuffle mid-read.
    val live by placedFlow.collectAsState(HomeMemory.placed)
    var shown by remember { mutableStateOf(HomeMemory.placed) }
    val shownIds = remember(shown) { shown?.values?.flatten()?.map { it.id }?.toSet() }
    val newestShown = remember(shown) { shown?.values?.flatten()?.maxOfOrNull { it.discoveredAt } ?: 0L }
    // Only articles collected after the ones on screen count as new (not older ones sliding into a section).
    val fresh = if (shownIds == null) 0 else live?.values?.flatten()?.count { it.id !in shownIds && it.discoveredAt > newestShown } ?: 0
    LaunchedEffect(live, shownIds) { if (shown == null || fresh == 0 || shown?.keys != live?.keys) shown = live }
    val lastSync by remember { c.db.misc().observeLastSync() }.collectAsState(HomeMemory.lastSync)
    // Set when a collection finishes, so the section lists catch up even if they recompute a moment later.
    var revealNext by remember { mutableStateOf(false) }
    LaunchedEffect(lastSync) {
        val t = lastSync ?: return@LaunchedEffect
        val prev = HomeMemory.lastSync
        HomeMemory.lastSync = t
        if (prev != null && t > prev) { shown = live; revealNext = true }
    }
    LaunchedEffect(live) { if (revealNext) { shown = live; revealNext = false } }
    val liveById = remember(live) { live?.values?.flatten()?.associateBy { it.id }.orEmpty() }
    // Keep read marks etc. current for the articles already on screen.
    val placed = shown?.mapValues { (_, l) -> l.map { liveById[it.id] ?: it } }
    shown?.let { HomeMemory.placed = it }
    val scope = rememberCoroutineScope()

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

    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        item {
            Text(
                (lastSync?.let { "Last collection ${Fmt.relative(it)}" } ?: "Not collected yet") + " · ${if (total >= 0) total else 0} articles archived",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        val toggle: (String) -> Unit = { code ->
            c.settings.update { s ->
                s.copy(collapsedSections = if (code in s.collapsedSections) s.collapsedSections - code else s.collapsedSections + code)
            }
        }
        for (code in settings.homeSections) {
            val expanded = code !in settings.collapsedSections
            when (code) {
                HomeSection.MULTI -> multiSourceSection(nav, from, expanded) { toggle(HomeSection.MULTI) }
                else -> {
                    articleSection(
                        code, sectionTitle(code, settings.homePlace), placed?.get(code), placed != null,
                        nav, settings.showImages, expanded,
                        onOpen = { nav.navigate("section/${enc(code)}") },
                    ) { toggle(code) }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    if (fresh > 0) {
        FilledTonalButton(
            onClick = { shown = live; scope.launch { listState.animateScrollToItem(0) } },
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
        ) { Text(if (fresh == 1) "Show 1 new article" else "Show $fresh new articles") }
    }
    }
}

/** Space Defense News: everything from Defense News and SpaceNews, and nothing else. */
fun spaceDefenseFilter(limit: Int, from: Long? = null, articleType: String? = null) = ArticleFilter(
    anyUrlHosts = HomeSection.spaceDefenseHosts, from = from, articleType = articleType, limit = limit,
)

/** The article filter for one home-section code, optionally windowed to articles on/after [from]. */
fun sectionFilter(code: String, homePlace: String, from: Long?, limit: Int): ArticleFilter = when (code) {
    HomeSection.LOCAL -> ArticleFilter(placeCode = homePlace, from = from, limit = limit)
    HomeSection.LATEST, Gazetteer.WORLD_CODE -> ArticleFilter(from = from, limit = limit)
    HomeSection.UPDATED -> ArticleFilter(minVersions = 2, from = from, limit = limit)
    HomeSection.SPACE_DEFENSE -> spaceDefenseFilter(limit = limit, from = from)
    else -> ArticleFilter(placeCode = code, from = from, limit = limit)
}

/** Local midnight at the start of the day [daysBack] days before today. */
fun windowStart(days: Int): Long =
    Fmt.startOfToday() - (days.coerceAtLeast(1) - 1) * 86_400_000L

/**
 * One flow for every article section on the home page: gathers recent candidates for each section,
 * drops repeats of the same story from the same publisher, then gives each article to its single most
 * pertinent section (see [HomePlacement]).
 */
private fun homePlacement(c: AppContainer, sections: List<String>, homePlace: String, from: Long): Flow<Map<String, List<ArticleListItem>>> {
    val specs = sections.filter { it != HomeSection.MULTI }.map { code ->
        val section = when (code) {
            HomeSection.LOCAL -> HomePlacement.Place(code, homePlace)
            HomeSection.LATEST, Gazetteer.WORLD_CODE -> HomePlacement.CatchAll(code)
            HomeSection.UPDATED -> HomePlacement.Updated(code)
            HomeSection.SPACE_DEFENSE -> HomePlacement.Publisher(code, HomeSection.spaceDefenseHosts.toSet())
            else -> HomePlacement.Place(code, code, dropOutsideTeamSports = code in OutsideTeamSports.filteredSections)
        }
        section to sectionFilter(code, homePlace, from, 40)
    }
    if (specs.isEmpty()) return flowOf(emptyMap())
    return combine(specs.map { c.db.articles().observeList(ArticleQuery.build(it.second)) }) { it.toList() }.map { lists ->
        val pool = lists.flatten().distinctBy { it.id }.sortedByDescending { it.sortTime }
            .distinctBy { (UrlCanonicalizer.host(it.url) ?: it.url) + "|" + TextNormalizer.clean(it.title).lowercase() }
        val ids = pool.map { it.id }
        val tags = c.db.tags().geoForMany(ids).groupBy { it.articleId }
        val topics = if (specs.any { it.first is HomePlacement.Subject }) c.db.tags().topicsForMany(ids).groupBy { it.articleId } else emptyMap()
        val categories = c.db.sources().all().associate { it.id to it.category }
        val candidates = pool.map { a ->
            HomePlacement.Candidate(
                a.id, a.versionCount, tags[a.id].orEmpty().map { HomePlacement.Tag(it.code, it.confidence, it.inferred) },
                topics[a.id].orEmpty().map { HomePlacement.TopicScore(it.topic, it.confidence) }, categories[a.sourceId],
                UrlCanonicalizer.host(a.url),
                outsideTeamSports = OutsideTeamSports.isOutsideTeamStory(a.title, a.summary),
            )
        }
        val byId = pool.associateBy { it.id }
        HomePlacement.assign(specs.map { it.first }, candidates).mapValues { (_, ids) -> ids.mapNotNull(byId::get) }
    }
}

private fun LazyListScope.articleSection(
    key: String, title: String, list: List<ArticleListItem>?, loaded: Boolean,
    nav: Nav, showImages: Boolean, expanded: Boolean, onOpen: () -> Unit, onToggle: () -> Unit,
) {
    item(key = "s-$key") {
        SectionCard(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            CollapsibleSectionHeader(title, list?.count { !it.isRead } ?: 0, expanded, onToggle, onOpen = onOpen)
            // A collapsed section costs one row: its articles are never composed.
            if (expanded) when {
                !loaded -> Spacer(Modifier.height(40.dp))
                list.isNullOrEmpty() -> Text(
                    "No other recent articles for this section.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                else -> Column {
                    list.forEachIndexed { i, a ->
                        if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        ArticleRow(a, onOpen = { nav.openArticle(a.id, list.map { it.id }) }, showImage = showImages,
                            onOpenStory = { sid -> if (a.publisherCount >= 3) nav.openCompare(sid) else nav.openStory(sid) })
                    }
                }
            }
        }
    }
}

private fun LazyListScope.multiSourceSection(nav: Nav, since: Long, expanded: Boolean, onToggle: () -> Unit) {
    item(key = "s-multi") {
        val c = LocalContainer.current
        val stories by remember(since) { c.db.stories().observeMultiSource(2, since, 12) }.collectAsState(HomeMemory.stories)
        val list = stories
        if (list != null) HomeMemory.stories = list
        SectionCard(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            CollapsibleSectionHeader("Multi-source coverage", list?.size ?: 0, expanded, onToggle)
            if (expanded) when {
                list == null -> Spacer(Modifier.height(40.dp))
                list.isEmpty() -> Text(
                    "No story has been covered by two or more publishers in this window yet.",
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

/**
 * The fuller view of one home section, opened by tapping the section's header title on the home page.
 * Shows the last 7 days by default (the home page itself only shows the shorter feed window), with
 * chips to widen to 14 or 30 days.
 */
@Composable
fun SectionScreen(code: String, nav: Nav) {
    val c = LocalContainer.current
    val homePlace = c.settings.current.homePlace
    var days by rememberSaveable { mutableIntStateOf(7) }
    var limit by remember { mutableIntStateOf(60) }
    val filter = remember(code, days, limit) { sectionFilter(code, homePlace, windowStart(days), limit) }
    // Canada, United States and Europe pages leave out other cities' team sports stories.
    val items by remember(filter) {
        c.db.articles().observeList(ArticleQuery.build(filter)).map { list ->
            val kept = if (code in OutsideTeamSports.filteredSections) list.filterNot { OutsideTeamSports.isOutsideTeamStory(it.title, it.summary) } else list
            // Publishers the user doesn't want for this region (CBC and CTV under United States).
            kept.filterNot { RegionSources.isExcluded(code, UrlCanonicalizer.host(it.url)) }
        }
    }.collectAsState(null)
    Column(Modifier.fillMaxSize()) {
        DetailBar(sectionTitle(code, homePlace), nav)
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(7 to "7 days", 14 to "14 days", 30 to "30 days").forEach { (d, l) ->
                FilterChip(days == d, { days = d; limit = 60 }, label = { Text(l) })
            }
        }
        val list = items
        if (list == null) { Spacer(Modifier.height(40.dp)); return@Column }
        ArticleList(
            list, nav, canLoadMore = list.size >= limit, onLoadMore = { limit += 60 },
            empty = { EmptyState("Nothing in this period", "No articles for this section in the last $days days.") },
        )
    }
}
