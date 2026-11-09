package ca.newsarchive.core.telegram

data class TelegramDirectoryEntry(val username: String, val title: String, val category: String, val note: String)

/**
 * Suggested public channels. Availability of each web preview is checked when it is followed;
 * channels that disable previews are shown as unavailable rather than hidden or faked.
 */
object TelegramDirectory {
    val all = listOf(
        TelegramDirectoryEntry("KyivIndependent_official", "The Kyiv Independent", "Europe", "Ukrainian English-language outlet"),
        TelegramDirectoryEntry("nexta_live", "NEXTA Live", "Europe", "Belarusian outlet based in Poland"),
        TelegramDirectoryEntry("meduzalive", "Meduza", "Europe", "Independent Russian-language outlet (Latvia)"),
        TelegramDirectoryEntry("bbcrussian", "BBC News Russian", "International", "BBC World Service, Russian language"),
        TelegramDirectoryEntry("tass_agency", "TASS", "Europe", "Russian state news agency"),
        TelegramDirectoryEntry("rian_ru", "RIA Novosti", "Europe", "Russian state news agency"),
        TelegramDirectoryEntry("insiderpaper", "Insider Paper", "International", "Breaking-news aggregator"),
        TelegramDirectoryEntry("disclosetv", "Disclose.tv", "International", "Breaking-news aggregator"),
        TelegramDirectoryEntry("durov", "Pavel Durov", "Technology", "Telegram founder's channel"),
        TelegramDirectoryEntry("telegram", "Telegram News", "Technology", "Official Telegram announcements"),
    )
}
