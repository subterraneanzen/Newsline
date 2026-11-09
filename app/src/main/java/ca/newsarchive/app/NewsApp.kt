package ca.newsarchive.app

import android.app.Application
import android.content.Context
import ca.newsarchive.app.data.AppDatabase
import ca.newsarchive.app.data.Repository
import ca.newsarchive.app.data.SettingsStore
import ca.newsarchive.app.ingest.ArticleProcessor
import ca.newsarchive.app.ingest.Collector
import ca.newsarchive.app.ingest.Maintenance
import ca.newsarchive.app.ingest.StoryService
import ca.newsarchive.app.ingest.SyncEngine
import ca.newsarchive.app.ingest.TelegramCollector
import ca.newsarchive.app.io.ArchiveIO
import ca.newsarchive.app.notify.Notifier
import ca.newsarchive.app.ui.components.ImageLoader
import ca.newsarchive.app.work.SyncWorker
import ca.newsarchive.core.feed.SourceProbe
import ca.newsarchive.core.net.HttpFetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Manual dependency container – no DI framework needed for an app of this size. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val settings = SettingsStore(context)
    val db = AppDatabase.build(context)
    val http = HttpFetcher()
    val stories = StoryService(db)
    val processor = ArticleProcessor(db, http, settings, stories)
    val collector = Collector(db, http, processor)
    val telegram = TelegramCollector(db, http)
    val notifier = Notifier(context, db, settings)
    val maintenance = Maintenance(context, db, settings, stories)
    val sync = SyncEngine(db, settings, collector, telegram, processor, stories, notifier, maintenance)
    val repo = Repository(db, SourceProbe(http), stories, maintenance)
    val io = ArchiveIO(context, db, maintenance)
    val images = ImageLoader(context, http)

    /** Runs a collection pass in the app process (used while the app is open). */
    fun refresh(trigger: String, force: Boolean = false, sourceIds: List<Long>? = null) {
        appScope.launch { runCatching { sync.run(trigger, force, sourceIds) } }
    }
}

class NewsApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        ArchiveIO.applyStagedRestore(this)
        container = AppContainer(this)
        container.notifier.createChannels()
        SyncWorker.schedule(this, container.settings.current)
    }
}
