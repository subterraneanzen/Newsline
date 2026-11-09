package ca.newsarchive.core.telegram

import ca.newsarchive.core.text.Dates
import ca.newsarchive.core.text.TextNormalizer
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

data class TelegramPost(
    val channel: String,
    val postId: Long,
    val text: String,
    val postedAt: Long?,
    val url: String,
    val views: String?,
    val hasMedia: Boolean,
    val imageUrl: String?,
    val linkUrls: List<String>,
)

data class TelegramChannelPage(
    val username: String,
    val title: String?,
    val description: String?,
    val subscribers: String?,
    val posts: List<TelegramPost>,
    /** False when Telegram does not offer a public web preview for this channel. */
    val previewAvailable: Boolean,
)

/**
 * Reads Telegram's public web preview (https://t.me/s/<channel>). This works without an account
 * for public channels that have previews enabled. It cannot read private channels, groups, or
 * channels with preview disabled — those are reported as unavailable rather than faked.
 */
object TelegramPreview {
    private val usernameRe = Regex("^[A-Za-z][A-Za-z0-9_]{3,31}$")

    /** Accepts "@name", "name", "t.me/name", "https://t.me/s/name", "https://telegram.me/name/123". */
    fun normalizeUsername(input: String): String? {
        var s = input.trim().removePrefix("@")
        s = s.replace(Regex("^(https?://)?(www\\.)?(t\\.me|telegram\\.me|telegram\\.dog)/"), "")
        s = s.removePrefix("s/").substringBefore('/').substringBefore('?')
        return s.takeIf { usernameRe.matches(it) && !it.startsWith("joinchat") && !it.startsWith("+") }
    }

    fun previewUrl(username: String, before: Long? = null) =
        "https://t.me/s/$username" + (before?.let { "?before=$it" } ?: "")

    fun parse(html: String, username: String): TelegramChannelPage {
        val doc = Jsoup.parse(html, "https://t.me/s/$username")
        val messages = doc.select(".tgme_widget_message[data-post]")
        val title = doc.selectFirst(".tgme_channel_info_header_title")?.text()?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.ifBlank { null }
        val desc = doc.selectFirst(".tgme_channel_info_description")?.text()?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.ifBlank { null }
        val subs = doc.select(".tgme_channel_info_counter").firstOrNull { it.selectFirst(".counter_type")?.text()?.contains("subscriber") == true }
            ?.selectFirst(".counter_value")?.text()
        val isPreviewPage = doc.selectFirst(".tgme_channel_info, .tgme_widget_message") != null
        val posts = messages.mapNotNull { parseMessage(it) }.sortedByDescending { it.postId }
        return TelegramChannelPage(username, title, desc, subs, posts, isPreviewPage)
    }

    private fun parseMessage(m: Element): TelegramPost? {
        val dataPost = m.attr("data-post")
        val channel = dataPost.substringBefore('/')
        val id = dataPost.substringAfter('/').toLongOrNull() ?: return null
        val textEl = m.selectFirst(".tgme_widget_message_text")
        val text = textEl?.let { el ->
            el.wholeText().lines().map { TextNormalizer.clean(it) }.joinToString("\n").trim()
        }.orEmpty()
        val time = m.selectFirst(".tgme_widget_message_date time[datetime]")?.attr("datetime")
        val views = m.selectFirst(".tgme_widget_message_views")?.text()?.ifBlank { null }
        val photo = m.selectFirst(".tgme_widget_message_photo_wrap")?.attr("style")?.let {
            Regex("url\\('?(.*?)'?\\)").find(it)?.groupValues?.get(1)
        }
        val hasMedia = photo != null || m.selectFirst(".tgme_widget_message_video_player, .tgme_widget_message_document, .tgme_widget_message_roundvideo") != null
        val links = textEl?.select("a[href]")?.map { it.absUrl("href") }?.filter { it.startsWith("http") && !it.contains("t.me/") }?.distinct().orEmpty()
        if (text.isEmpty() && !hasMedia) return null
        return TelegramPost(
            channel = channel, postId = id, text = text, postedAt = Dates.parse(time),
            url = "https://t.me/$channel/$id", views = views, hasMedia = hasMedia, imageUrl = photo, linkUrls = links,
        )
    }
}
