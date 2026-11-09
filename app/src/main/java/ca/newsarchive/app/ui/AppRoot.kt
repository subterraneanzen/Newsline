@file:OptIn(ExperimentalMaterial3Api::class)

package ca.newsarchive.app.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalLibrary
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ca.newsarchive.app.LocalContainer
import ca.newsarchive.app.ingest.SyncState
import ca.newsarchive.app.ui.components.Nav
import ca.newsarchive.app.ui.components.ReaderQueue
import ca.newsarchive.app.ui.compare.CompareScreen
import ca.newsarchive.app.ui.explore.ExploreScreen
import ca.newsarchive.app.ui.explore.PlaceScreen
import ca.newsarchive.app.ui.history.HistoryScreen
import ca.newsarchive.app.ui.home.HomeScreen
import ca.newsarchive.app.ui.library.CollectionScreen
import ca.newsarchive.app.ui.library.LibraryScreen
import ca.newsarchive.app.ui.reader.ReaderScreen
import ca.newsarchive.app.ui.search.SearchScreen
import ca.newsarchive.app.ui.settings.SettingsScreen
import ca.newsarchive.app.ui.settings.StorageScreen
import ca.newsarchive.app.ui.sources.AddSourceScreen
import ca.newsarchive.app.ui.sources.DirectoryScreen
import ca.newsarchive.app.ui.sources.SourceDetailScreen
import ca.newsarchive.app.ui.sources.SourcesScreen
import ca.newsarchive.app.ui.telegram.TelegramChannelsScreen
import ca.newsarchive.app.ui.telegram.TelegramScreen
import ca.newsarchive.app.ui.timeline.StoryTimelineScreen
import ca.newsarchive.app.ui.timeline.TimelineScreen
import ca.newsarchive.app.ui.timeline.TopicTimelineScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "Home", Icons.Filled.Home),
    Tab("explore", "Explore", Icons.Filled.Explore),
    Tab("timeline", "Timeline", Icons.Filled.Timeline),
    Tab("telegram", "Telegram", Icons.Filled.Send),
    Tab("library", "Library", Icons.Filled.LocalLibrary),
)

fun enc(s: String): String = Uri.encode(s)

@Composable
fun AppRoot(pendingArticle: Long?, onPendingConsumed: () -> Unit) {
    val nav = rememberNavController()
    val navActions = remember(nav) { navActions(nav) }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val isTab = tabs.any { it.route == route }

    LaunchedEffect(pendingArticle) {
        if (pendingArticle != null) { nav.navigate("reader/$pendingArticle"); onPendingConsumed() }
    }

    Scaffold(
        bottomBar = {
            if (isTab) NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = route == t.route,
                        onClick = {
                            nav.navigate(t.route) {
                                popUpTo("home") { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(bottom = pad.calculateBottomPadding())) {
            composable("home") { TabScaffold("News Archive", navActions) { HomeScreen(navActions) } }
            composable("explore") { TabScaffold("Explore", navActions) { ExploreScreen(navActions) } }
            composable("timeline") { TabScaffold("Timeline", navActions) { TimelineScreen(navActions) } }
            composable("telegram") { TabScaffold("Telegram", navActions) { TelegramScreen(navActions) } }
            composable("library") { TabScaffold("Library", navActions) { LibraryScreen(navActions) } }

            composable("reader/{id}", listOf(navArgument("id") { type = NavType.LongType })) {
                ReaderScreen(it.arguments!!.getLong("id"), navActions)
            }
            composable("history/{id}", listOf(navArgument("id") { type = NavType.LongType })) {
                HistoryScreen(it.arguments!!.getLong("id"), navActions)
            }
            composable("compare/{id}", listOf(navArgument("id") { type = NavType.LongType })) {
                CompareScreen(it.arguments!!.getLong("id"), navActions)
            }
            composable("story/{id}", listOf(navArgument("id") { type = NavType.LongType })) {
                StoryTimelineScreen(it.arguments!!.getLong("id"), navActions)
            }
            composable("topic/{kind}/{value}") {
                TopicTimelineScreen(it.arguments!!.getString("kind")!!, it.arguments!!.getString("value")!!, navActions)
            }
            composable("place/{code}") { PlaceScreen(it.arguments!!.getString("code")!!, navActions) }
            composable("search?q={q}", listOf(navArgument("q") { type = NavType.StringType; defaultValue = "" })) {
                SearchScreen(it.arguments?.getString("q").orEmpty(), navActions)
            }
            composable("sources") { SourcesScreen(navActions) }
            composable("sources/add") { AddSourceScreen(navActions) }
            composable("sources/directory") { DirectoryScreen(navActions) }
            composable("source/{id}", listOf(navArgument("id") { type = NavType.LongType })) {
                SourceDetailScreen(it.arguments!!.getLong("id"), navActions)
            }
            composable("collection/{id}", listOf(navArgument("id") { type = NavType.LongType })) {
                CollectionScreen(it.arguments!!.getLong("id"), navActions)
            }
            composable("telegram/channels") { TelegramChannelsScreen(navActions) }
            composable("settings") { SettingsScreen(navActions) }
            composable("storage") { StorageScreen(navActions) }
        }
    }
}

private fun navActions(nav: NavHostController) = Nav(
    openArticle = { id, list -> ReaderQueue.ids = list; nav.navigate("reader/$id") },
    openStory = { nav.navigate("story/$it") },
    openCompare = { nav.navigate("compare/$it") },
    openSource = { nav.navigate("source/$it") },
    navigate = { nav.navigate(it) },
    back = { nav.popBackStack() },
)

/** Top bar for the five main tabs: search, refresh with progress, and the settings menu. */
@Composable
fun TabScaffold(title: String, nav: Nav, content: @Composable () -> Unit) {
    val c = LocalContainer.current
    val sync by c.sync.state.collectAsState()
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            actions = {
                IconButton(onClick = { nav.navigate("search?q=") }) { Icon(Icons.Outlined.Search, "Search archive") }
                IconButton(onClick = { c.refresh("manual", force = false) }, enabled = sync is SyncState.Idle) { Icon(Icons.Outlined.Refresh, "Refresh now") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Menu") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Sources") }, onClick = { menu = false; nav.navigate("sources") })
                        DropdownMenuItem({ Text("Telegram channels") }, onClick = { menu = false; nav.navigate("telegram/channels") })
                        DropdownMenuItem({ Text("Storage & export") }, onClick = { menu = false; nav.navigate("storage") })
                        DropdownMenuItem({ Text("Settings") }, onClick = { menu = false; nav.navigate("settings") })
                    }
                }
            },
        )
        SyncBar(sync)
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
fun SyncBar(sync: SyncState) {
    if (sync is SyncState.Running) {
        Column(Modifier.fillMaxWidth()) {
            if (sync.total > 0) LinearProgressIndicator(progress = { sync.done.toFloat() / sync.total }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                "Collecting: ${sync.label} · ${sync.done}/${sync.total} sources · ${sync.newArticles} new",
                style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
    }
}

/** Back-arrow top bar for detail screens. */
@Composable
fun DetailBar(title: String, nav: Nav, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
        navigationIcon = { IconButton(onClick = nav.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        actions = { actions() },
    )
}
