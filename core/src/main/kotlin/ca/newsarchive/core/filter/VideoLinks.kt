package ca.newsarchive.core.filter

/**
 * Recognises links that lead to a video clip rather than a written article, so they can be left
 * out of the archive and the lists. Patterns come from the publishers' own feeds (checked 2026-11-10):
 * CBS/ABC/NBC/Today/Al Jazeera/France 24/Defense News use a /video/ path, CBC uses /player/,
 * The Globe and Mail prefixes the slug with "video-", and ABC titles clips "WATCH:" or "LIVE:".
 */
object VideoLinks {
    private val path = Regex(
        "^https?://[^/]+(/[^?#]*)?/(video|videos|player|watch|clip|clips)(/|\\?|$)" +
            "|^https?://(www\\.)?(youtube\\.com|youtu\\.be|vimeo\\.com)/" +
            "|^https?://(www\\.)?theglobeandmail\\.com/.*/video-" +
            "|^https?://(www\\.)?abcnews\\.(go\\.)?com/live/?(\\?|$)",
        RegexOption.IGNORE_CASE,
    )
    private val title = Regex("^\\s*(watch|video|live)\\s*[:|]", RegexOption.IGNORE_CASE)

    fun isVideo(url: String, title: String?): Boolean =
        path.containsMatchIn(url) || (title != null && this.title.containsMatchIn(title))

    /** The same rules as SQL conditions on `a.url` / `a.title` (for articles archived before this filter existed). */
    const val SQL_NOT_VIDEO = "a.url NOT LIKE '%/video/%' AND a.url NOT LIKE '%/videos/%' AND a.url NOT LIKE '%/player/%'" +
        " AND a.url NOT LIKE '%/watch/%' AND a.url NOT LIKE '%/clip/%' AND a.url NOT LIKE '%youtube.com/%' AND a.url NOT LIKE '%youtu.be/%'" +
        " AND a.url NOT LIKE '%vimeo.com/%' AND a.url NOT LIKE '%theglobeandmail.com/%/video-%'" +
        " AND a.title NOT LIKE 'WATCH:%' AND a.title NOT LIKE 'VIDEO:%' AND a.title NOT LIKE 'LIVE:%'" +
        " AND a.title NOT LIKE 'WATCH |%' AND a.title NOT LIKE 'VIDEO |%'"
}
