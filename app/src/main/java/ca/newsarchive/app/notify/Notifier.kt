package ca.newsarchive.app.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ca.newsarchive.app.MainActivity
import ca.newsarchive.app.R
import ca.newsarchive.app.data.AppDatabase
import ca.newsarchive.app.data.FollowKind
import ca.newsarchive.app.data.NotificationLogEntity
import ca.newsarchive.app.data.SettingsStore
import java.util.Calendar

/**
 * Local notifications for new articles matching the user's choices. Alerts are de-duplicated per
 * story, rate-limited, and respect quiet hours. Timing depends on Android's background scheduling.
 */
class Notifier(private val context: Context, private val db: AppDatabase, private val settings: SettingsStore) {
    companion object {
        const val CHANNEL_NEWS = "news_alerts"
        const val CHANNEL_SYNC = "sync_status"
        const val EXTRA_ARTICLE_ID = "articleId"
        const val EXTRA_STORY_ID = "storyId"
    }

    fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_NEWS, "News alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "New articles from sources, topics and places you chose"
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_SYNC, "Collection progress", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Shown while news is being collected in the background"
        })
    }

    fun canPost(): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    suspend fun notifyNew(articleIds: List<Long>) {
        val s = settings.current
        if (!s.notificationsEnabled || articleIds.isEmpty() || !canPost()) return
        val now = System.currentTimeMillis()
        if (s.quietHours) {
            val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            if (h >= 22 || h < 7) return
        }
        db.misc().pruneNotifications(now - 14L * 86_400_000L)
        var budget = s.maxNotificationsPerHour - db.misc().notificationsSince(now - 3_600_000L)
        if (budget <= 0) return

        val follows = db.library().follows().filter { it.notify }
        val topicFollows = follows.filter { it.kind == FollowKind.TOPIC }.map { it.value }.toSet()
        val placeFollows = follows.filter { it.kind == FollowKind.PLACE }.map { it.value }.toSet()
        val keywordFollows = follows.filter { it.kind == FollowKind.KEYWORD }.map { it.value.lowercase() }
        val notifySources = db.sources().all().filter { it.notify }.map { it.id }.toSet()

        val articles = db.articles().byIds(articleIds).sortedByDescending { it.sortTime }
        val seenStories = HashSet<Long>()
        for (a in articles) {
            if (budget <= 0) break
            val story = a.storyId?.let { db.stories().get(it) }
            val reason: String? = when {
                a.sourceId in notifySources -> "From a source you follow"
                db.tags().topicsFor(a.id).any { it.topic in topicFollows } -> "Topic you follow"
                db.tags().geoFor(a.id).any { it.code in placeFollows } -> "Place you follow"
                keywordFollows.any { k -> a.title.lowercase().contains(k) || (a.summary?.lowercase()?.contains(k) == true) } -> "Keyword you follow"
                s.notifyMultiSource && story != null && story.publisherCount >= 5 -> "Covered by ${story.publisherCount} publishers"
                else -> null
            }
            if (reason == null) continue
            val key = story?.let { "story:${it.id}" } ?: "article:${a.id}"
            if (story != null && !seenStories.add(story.id)) continue
            if (db.misc().notified(key) != null) continue
            val source = db.sources().get(a.sourceId)
            post(a.id, a.title, "${source?.name ?: ""} · $reason", (a.id % Int.MAX_VALUE).toInt())
            db.misc().logNotification(NotificationLogEntity(key, now))
            budget--
        }
    }

    private fun post(articleId: Long, title: String, text: String, notificationId: Int) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_ARTICLE_ID, articleId)
        }
        val pi = PendingIntent.getActivity(context, notificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL_NEWS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(title))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notificationId, n)
        } catch (_: SecurityException) { }
    }
}
