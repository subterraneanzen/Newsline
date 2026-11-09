package ca.newsarchive.app.ingest

import ca.newsarchive.app.data.AppDatabase
import ca.newsarchive.app.data.SettingsStore
import ca.newsarchive.app.data.SyncRunEntity
import ca.newsarchive.app.notify.Notifier
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

sealed interface SyncState {
    data object Idle : SyncState
    data class Running(val done: Int, val total: Int, val label: String, val newArticles: Int) : SyncState
}

/** Runs one collection pass: due sources → Telegram → revisits → notifications → retention. */
class SyncEngine(
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val collector: Collector,
    private val telegram: TelegramCollector,
    private val processor: ArticleProcessor,
    private val stories: StoryService,
    private val notifier: Notifier,
    private val maintenance: Maintenance,
) {
    private val running = Mutex()
    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /**
     * @param sourceIds limit to these sources (null = all due sources)
     * @param force ignore intervals and HTTP caching
     * @param timeBudgetMs stop starting new work after this long (WorkManager allows ~10 minutes)
     */
    suspend fun run(trigger: String, force: Boolean = false, sourceIds: List<Long>? = null, timeBudgetMs: Long = 8 * 60_000L): SyncRunEntity? {
        if (!running.tryLock()) return null
        val start = System.currentTimeMillis()
        val deadline = start + timeBudgetMs
        var run = SyncRunEntity(startedAt = start, trigger = trigger)
        run = run.copy(id = db.misc().insertRun(run))
        try {
            stories.invalidate()
            val all = db.sources().enabled()
            val due = all.filter { s ->
                (sourceIds == null || s.id in sourceIds) &&
                    (force || sourceIds != null || s.lastAttemptAt == null || start - s.lastAttemptAt >= s.intervalMinutes * 60_000L - 120_000L)
            }.sortedBy { it.lastAttemptAt ?: 0L }
            val done = AtomicInteger(0)
            val newCount = AtomicInteger(0)
            val failed = AtomicInteger(0)
            val newIds = java.util.Collections.synchronizedList(mutableListOf<Long>())
            _state.value = SyncState.Running(0, due.size, "Checking ${due.size} sources", 0)
            val gate = Semaphore(3)
            coroutineScope {
                due.map { s ->
                    async {
                        gate.withPermit {
                            if (System.currentTimeMillis() > deadline) return@withPermit
                            val r = collector.collect(s, force) { id -> newIds += id; newCount.incrementAndGet() }
                            if (r.error != null) failed.incrementAndGet()
                            _state.value = SyncState.Running(done.incrementAndGet(), due.size, s.name, newCount.get())
                        }
                    }
                }.awaitAll()
            }
            run = run.copy(sourcesChecked = done.get(), sourcesFailed = failed.get(), newArticles = newIds.size)

            if (sourceIds == null && System.currentTimeMillis() < deadline) {
                _state.value = SyncState.Running(done.get(), due.size, "Telegram channels", newCount.get())
                runCatching { telegram.collectAll() }
            }

            var revisited = 0
            var changed = 0
            if (sourceIds == null && settings.current.revisitEnabled) {
                val dueArticles = db.articles().dueForRevisit(System.currentTimeMillis(), 80)
                for (a in dueArticles) {
                    if (System.currentTimeMillis() > deadline) break
                    _state.value = SyncState.Running(done.get(), due.size, "Checking articles for changes ($revisited/${dueArticles.size})", newCount.get())
                    val outcome = runCatching { processor.revisit(a) }.getOrNull()
                    revisited++
                    if (outcome == ArticleProcessor.RevisitOutcome.NEW_VERSION) changed++
                }
            }
            run = run.copy(articlesRevisited = revisited, newVersions = changed)

            runCatching { notifier.notifyNew(newIds.toList()) }
            runCatching { maintenance.applyRetention() }
            db.misc().pruneRuns(start - 30L * 86_400_000L)
            run = run.copy(finishedAt = System.currentTimeMillis(), message = "OK")
        } catch (e: Exception) {
            run = run.copy(finishedAt = System.currentTimeMillis(), message = "Stopped: ${e.message ?: e.javaClass.simpleName}")
            throw e
        } finally {
            val finalRun = run
            withContext(NonCancellable) { db.misc().updateRun(finalRun) }
            _state.value = SyncState.Idle
            running.unlock()
        }
        return run
    }
}
