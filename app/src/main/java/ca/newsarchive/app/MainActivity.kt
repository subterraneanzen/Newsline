package ca.newsarchive.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.launch
import ca.newsarchive.app.notify.Notifier
import ca.newsarchive.app.ui.AppRoot
import ca.newsarchive.app.ui.theme.NewsTheme

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("No container") }

class MainActivity : ComponentActivity() {
    /** Article requested by a notification tap; consumed by the navigation host. */
    private val pendingArticle = mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as NewsApp).container
        readIntent(intent)
        if (savedInstanceState == null) {
            container.appScope.launchStartup(container)
        }
        setContent {
            val settings by container.settings.flow.collectAsState()
            CompositionLocalProvider(LocalContainer provides container) {
                NewsTheme(settings.theme, settings.backgroundColor, settings.textColor, settings.appTextScale) {
                    AppRoot(pendingArticle.value, onPendingConsumed = { pendingArticle.value = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
    }

    private fun readIntent(i: Intent?) {
        val id = i?.getLongExtra(Notifier.EXTRA_ARTICLE_ID, -1L) ?: -1L
        if (id > 0) pendingArticle.value = id
    }
}

/** First-run seeding and the "collect on launch" pass. */
private fun kotlinx.coroutines.CoroutineScope.launchStartup(c: AppContainer) = launch {
    if (!c.settings.current.firstRunDone) {
        c.repo.seedStarterSources()
        c.settings.update { it.copy(firstRunDone = true) }
    }
    val dirVersion = c.settings.current.directoryVersion
    if (dirVersion < ca.newsarchive.app.data.DIRECTORY_VERSION) {
        runCatching { c.repo.applyDirectoryUpdates(dirVersion) }
        // v6: stricter Space / Defense rules; take the old, too-loose tags off recent articles.
        if (dirVersion < 6) runCatching { c.maintenance.recheckSpaceDefenseTags() }
        if (dirVersion < 8) c.settings.update { s ->
            // Saskatoon is the new default home place: move installs still on the old Regina default over.
            val place = if (s.homePlace == "CA-SK:regina") "CA-SK:saskatoon" else s.homePlace
            // Multi-source coverage now sits at the bottom of the home list; relocate it, keeping the rest in place.
            val sections = if (ca.newsarchive.app.data.HomeSection.MULTI in s.homeSections)
                s.homeSections.filterNot { it == ca.newsarchive.app.data.HomeSection.MULTI } + ca.newsarchive.app.data.HomeSection.MULTI
            else s.homeSections
            s.copy(homePlace = place, homeSections = sections)
        }
        if (dirVersion < 9) {
            // Home opens with only the top two sections expanded; everything below starts folded.
            c.settings.update { s -> s.copy(collapsedSections = ca.newsarchive.app.data.HomeSection.defaultCollapsed(s.homeSections)) }
            // Move CBC / CTV / Global / CKOM local stories already archived into their city's section.
            runCatching { c.maintenance.applyUrlPlaces() }
        }
        if (dirVersion < 4) c.settings.update { s ->
            // The Space section becomes Space & Defense (added if the user had no Space section).
            val hs = s.homeSections
            val next = when {
                ca.newsarchive.app.data.HomeSection.SPACE_DEFENSE in hs -> hs
                "SPACE" in hs -> hs.map { if (it == "SPACE") ca.newsarchive.app.data.HomeSection.SPACE_DEFENSE else it }
                else -> hs + ca.newsarchive.app.data.HomeSection.SPACE_DEFENSE
            }
            s.copy(homeSections = next)
        }
        c.settings.update { it.copy(directoryVersion = ca.newsarchive.app.data.DIRECTORY_VERSION) }
    }
    c.images.trim()
    runCatching { c.sync.run("app launch") }
}
