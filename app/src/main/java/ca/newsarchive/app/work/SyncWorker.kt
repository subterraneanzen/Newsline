package ca.newsarchive.app.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ca.newsarchive.app.NewsApp
import ca.newsarchive.app.data.AppSettings
import java.util.concurrent.TimeUnit

/**
 * Periodic background collection. Android decides the exact timing (Doze, App Standby buckets,
 * battery saver), so the interval is a minimum, not a guarantee.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as NewsApp).container
        return try {
            c.sync.run(trigger = "background", timeBudgetMs = 8 * 60_000L)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val UNIQUE = "periodic-collection"

        /**
         * The worker wakes about four times per collection interval and checks a quarter of the sources each
         * time, so news arrives in small, steady batches instead of one big wave (see SyncEngine).
         */
        fun periodMinutes(s: AppSettings): Int = (s.syncIntervalMinutes / 4).coerceAtLeast(15)

        fun schedule(context: Context, s: AppSettings) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (s.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            val req = PeriodicWorkRequestBuilder<SyncWorker>(periodMinutes(s).toLong(), TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
