package ca.newsarchive.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Special home-section codes besides geographic place codes. */
object HomeSection {
    const val LOCAL = "LOCAL"          // the user's default location
    const val MULTI = "MULTI"          // multi-source coverage
    const val LATEST = "LATEST"        // everything, newest first
    const val UPDATED = "UPDATED"      // recently changed articles
    const val SPACE_DEFENSE = "SPACE_DEFENSE" // space and defense/military news, by subject rather than place
    val defaults = listOf(LOCAL, "CA-SK", "CA", "US", "C-EU", "WORLD", SPACE_DEFENSE, MULTI)

    /** Home opens with only the top two sections expanded; the rest start folded. */
    fun defaultCollapsed(sections: List<String>): Set<String> = sections.drop(2).toSet()

    /**
     * Space Defense News is fed by these two publishers only. Picking the section by publisher rather
     * than by subject keeps world and war stories out of it without any keyword guessing.
     */
    val spaceDefenseHosts = listOf("defensenews.com", "spacenews.com")
}

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val fontScale: Float = 1.0f,
    /** Text size across the whole app (1.0 = system size). */
    val appTextScale: Float = 1.0f,
    /** ARGB background colour; 0 = follow the theme. */
    val backgroundColor: Long = 0L,
    /** ARGB text colour; 0 = follow the theme. */
    val textColor: Long = 0L,
    val lineSpacing: Float = 1.5f,
    val showImages: Boolean = true,
    val homeSections: List<String> = HomeSection.defaults,
    /** Home sections the user has folded shut; they stay shut until tapped open again. Default: all but the top two. */
    val collapsedSections: Set<String> = HomeSection.defaultCollapsed(HomeSection.defaults),
    /**
     * How many days of news the home page shows, and how far back each refresh re-populates (today
     * plus the previous [feedWindowDays] − 1 days). Default 3 = today, yesterday and the day before.
     */
    val feedWindowDays: Int = 3,
    /**
     * How many days apart articles may be and still be grouped into one story (timelines and
     * multi-source coverage). Default 14; raise it to let a story's timeline reach further back.
     */
    val storyWindowDays: Int = 14,
    val homePlace: String = "CA-SK:saskatoon",
    val syncIntervalMinutes: Int = 60,
    val wifiOnly: Boolean = false,
    val revisitEnabled: Boolean = true,
    val revisitDays: Int = 14,
    val notificationsEnabled: Boolean = false,
    val notifyMultiSource: Boolean = true,
    val maxNotificationsPerHour: Int = 4,
    val quietHours: Boolean = true,
    val retentionDays: Int = 0,
    val firstRunDone: Boolean = false,
    val directoryVersion: Int = 0,
)

/** Bump when SourceDirectory changes need applying to existing installs (see Repository.applyDirectoryUpdates). */
const val DIRECTORY_VERSION = 9

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val flow: StateFlow<AppSettings> = state.asStateFlow()
    val current: AppSettings get() = state.value

    private fun read() = AppSettings(
        theme = runCatching { ThemeMode.valueOf(prefs.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM),
        fontScale = prefs.getFloat("fontScale", 1.0f),
        appTextScale = prefs.getFloat("appTextScale", 1.0f),
        backgroundColor = prefs.getLong("backgroundColor", 0L),
        textColor = prefs.getLong("textColor", 0L),
        lineSpacing = prefs.getFloat("lineSpacing", 1.5f),
        showImages = prefs.getBoolean("showImages", true),
        homeSections = prefs.getString("homeSections", null)?.split(',')?.filter { it.isNotBlank() } ?: HomeSection.defaults,
        collapsedSections = prefs.getString("collapsedSections", null)?.split(',')?.filter { it.isNotBlank() }?.toSet()
            ?: HomeSection.defaultCollapsed(prefs.getString("homeSections", null)?.split(',')?.filter { it.isNotBlank() } ?: HomeSection.defaults),
        feedWindowDays = prefs.getInt("feedWindowDays", 3),
        storyWindowDays = prefs.getInt("storyWindowDays", 14),
        homePlace = prefs.getString("homePlace", "CA-SK:saskatoon")!!,
        syncIntervalMinutes = prefs.getInt("syncIntervalMinutes", 60),
        wifiOnly = prefs.getBoolean("wifiOnly", false),
        revisitEnabled = prefs.getBoolean("revisitEnabled", true),
        revisitDays = prefs.getInt("revisitDays", 14),
        notificationsEnabled = prefs.getBoolean("notificationsEnabled", false),
        notifyMultiSource = prefs.getBoolean("notifyMultiSource", true),
        maxNotificationsPerHour = prefs.getInt("maxNotificationsPerHour", 4),
        quietHours = prefs.getBoolean("quietHours", true),
        retentionDays = prefs.getInt("retentionDays", 0),
        firstRunDone = prefs.getBoolean("firstRunDone", false),
        directoryVersion = prefs.getInt("directoryVersion", 0),
    )

    fun update(block: (AppSettings) -> AppSettings) {
        val n = block(state.value)
        prefs.edit()
            .putString("theme", n.theme.name)
            .putFloat("fontScale", n.fontScale)
            .putFloat("appTextScale", n.appTextScale)
            .putLong("backgroundColor", n.backgroundColor)
            .putLong("textColor", n.textColor)
            .putFloat("lineSpacing", n.lineSpacing)
            .putBoolean("showImages", n.showImages)
            .putString("homeSections", n.homeSections.joinToString(","))
            .putString("collapsedSections", n.collapsedSections.joinToString(","))
            .putInt("feedWindowDays", n.feedWindowDays)
            .putInt("storyWindowDays", n.storyWindowDays)
            .putString("homePlace", n.homePlace)
            .putInt("syncIntervalMinutes", n.syncIntervalMinutes)
            .putBoolean("wifiOnly", n.wifiOnly)
            .putBoolean("revisitEnabled", n.revisitEnabled)
            .putInt("revisitDays", n.revisitDays)
            .putBoolean("notificationsEnabled", n.notificationsEnabled)
            .putBoolean("notifyMultiSource", n.notifyMultiSource)
            .putInt("maxNotificationsPerHour", n.maxNotificationsPerHour)
            .putBoolean("quietHours", n.quietHours)
            .putInt("retentionDays", n.retentionDays)
            .putBoolean("firstRunDone", n.firstRunDone)
            .putInt("directoryVersion", n.directoryVersion)
            .apply()
        state.value = n
    }
}
