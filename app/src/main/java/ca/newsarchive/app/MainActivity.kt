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
        c.settings.update { it.copy(directoryVersion = ca.newsarchive.app.data.DIRECTORY_VERSION) }
    }
    c.images.trim()
    runCatching { c.sync.run("app launch") }
}
