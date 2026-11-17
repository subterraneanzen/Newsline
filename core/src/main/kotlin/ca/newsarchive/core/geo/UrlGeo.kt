package ca.newsarchive.core.geo

import ca.newsarchive.core.home.HomePlacement

/**
 * Location taken from the article's own web address. National broadcasters file local stories under
 * a city or province path (cbc.ca/news/canada/saskatoon/…, ctvnews.ca/saskatoon/…), which is a
 * firmer signal than counting place names in the text. When the address names a Canadian city or
 * province, the article is tagged with that place (and its parents) at full confidence, so a CBC or
 * CTV Saskatoon story lands in the Local · Saskatoon section rather than under Canada.
 */
object UrlGeo {
    /** Publishers whose address paths carry a reliable location. */
    val hosts = setOf("cbc.ca", "ctvnews.ca", "globalnews.ca", "ckom.com")

    private val aliases = mapOf(
        "bc" to "CA-BC",
        "newfoundland-labrador" to "CA-NL",
        "pei" to "CA-PE",
    )

    private fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    /** Path slug → Canadian city or province code. */
    private val slugs: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        for (p in Gazetteer.places.values) {
            if (!p.code.startsWith("CA-")) continue
            val key = when (p.level) {
                GeoLevel.CITY -> p.code.substringAfter(':')
                GeoLevel.REGION -> slug(p.name)
                else -> continue
            }
            m.putIfAbsent(key, p.code)
        }
        m + aliases
    }

    /** The place an article's address files it under, or null. Only the first few path segments count. */
    fun placeFor(url: String): String? {
        val m = Regex("^https?://([^/?#]+)(/[^?#]*)?").find(url) ?: return null
        if (!HomePlacement.hostMatches(m.groupValues[1], hosts)) return null
        return m.groupValues[2].split('/').filter { it.isNotEmpty() }.take(3)
            .firstNotNullOfOrNull { slugs[it.lowercase()] }
    }

    /** [tags] with the address's place (and its parents) added or raised to full, non-inferred confidence. */
    fun apply(tags: List<GeoTag>, url: String): List<GeoTag> {
        val code = placeFor(url) ?: return tags
        val byCode = LinkedHashMap<String, GeoTag>()
        tags.forEach { byCode[it.code] = it }
        for (p in Gazetteer.lineage(code)) byCode[p.code] = GeoTag(p.code, p.name, p.level, 1.0, inferred = false)
        return byCode.values.sortedWith(compareByDescending<GeoTag> { it.level.depth }.thenByDescending { it.confidence })
    }
}
