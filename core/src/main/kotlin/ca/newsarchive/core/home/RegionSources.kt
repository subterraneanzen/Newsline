package ca.newsarchive.core.home

import ca.newsarchive.core.geo.Gazetteer

/**
 * The publishers the user wants as the main voices for each region's news.
 *
 * In a region's home section (or a section for a place inside that region) articles from its
 * [Rule.primaryHosts] are listed first; other publishers only fill the remaining room. Articles from
 * [Rule.excludedHosts] are left out of that region's sections altogether (CBC and CTV stories about the
 * United States, say), though they still appear in their own Canadian sections.
 */
object RegionSources {
    data class Rule(val primaryHosts: Set<String>, val excludedHosts: Set<String> = emptySet())

    /**
     * Paywalled or often hard-to-read outlets the user named are left out on purpose: Financial Times,
     * Reuters, Nikkei Asia, Caixin, Xinhua and CCTV's own site (no feeds), NHK World (script-built pages).
     * CCTV is represented by CGTN, its international English channel.
     */
    private val china = setOf("cgtn.com", "scmp.com")

    /** Keyed by region code: United States, Europe, China, Asia. */
    val rules: Map<String, Rule> = mapOf(
        "US" to Rule(
            primaryHosts = setOf("cnbc.com", "ms.now", "msnbc.com", "nbcnews.com", "abcnews.go.com", "cnn.com"),
            excludedHosts = setOf("cbc.ca", "ctvnews.ca"),
        ),
        "C-EU" to Rule(setOf(
            "dw.com", "france24.com", "aljazeera.com", "bbc.com", "bbc.co.uk", "theguardian.com",
            "euronews.com",
        )),
        "CN" to Rule(china),
        // China is part of Asia, so the China list also counts as primary in the Asia section.
        "C-AS" to Rule(setOf(
            "channelnewsasia.com", "thejakartapost.com",
            "timesofindia.indiatimes.com", "bangkokpost.com",
        ) + china),
    )

    /** The rule for a home section on [code]: its own, else the nearest region containing it, else null. */
    fun ruleFor(code: String): Rule? =
        rules[code] ?: Gazetteer.ancestors(code).firstNotNullOfOrNull { rules[it.code] }

    fun isPrimary(code: String, host: String?): Boolean =
        ruleFor(code)?.let { HomePlacement.hostMatches(host, it.primaryHosts) } == true

    fun isExcluded(code: String, host: String?): Boolean =
        ruleFor(code)?.let { HomePlacement.hostMatches(host, it.excludedHosts) } == true
}
