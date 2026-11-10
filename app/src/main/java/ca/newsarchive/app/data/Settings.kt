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
    val defaults = listOf(LOCAL, MULTI, "CA-SK", "CA", "US", "C-EU", "WORLD", "SPACE")
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
    val homePlace: String = "CA-SK:regina",
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
const val DIRECTORY_VERSION = 3

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
        homePlace = prefs.getString("homePlace", "CA-SK:regina")!!,
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
