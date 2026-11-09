package ca.newsarchive.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SourceEntity::class, ArticleEntity::class, ArticleVersionEntity::class, ArticleFts::class,
        GeoTagEntity::class, TopicTagEntity::class, StoryEntity::class, CollectionEntity::class,
        CollectionItemEntity::class, FollowEntity::class, TelegramChannelEntity::class, TelegramPostEntity::class,
        NotificationLogEntity::class, SyncRunEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sources(): SourceDao
    abstract fun articles(): ArticleDao
    abstract fun versions(): VersionDao
    abstract fun fts(): FtsDao
    abstract fun tags(): TagDao
    abstract fun stories(): StoryDao
    abstract fun library(): LibraryDao
    abstract fun telegram(): TelegramDao
    abstract fun misc(): MiscDao
    abstract fun maintenance(): MaintenanceDao

    companion object {
        const val NAME = "news_archive.db"

        /**
         * Future schema changes must add explicit Migration objects here. Destructive migration is
         * deliberately NOT enabled: the archive must never be wiped by an app update.
         */
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
