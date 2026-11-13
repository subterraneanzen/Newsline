package ca.newsarchive.core.geo

enum class GeoLevel(val label: String, val depth: Int) {
    SPACE("Space", 0), WORLD("World", 0), CONTINENT("Continent", 1), COUNTRY("Country", 2),
    REGION("Province / State", 3), CITY("City", 4)
}

data class Place(val code: String, val name: String, val level: GeoLevel, val parentCode: String?)

data class GeoTag(val code: String, val name: String, val level: GeoLevel, val confidence: Double, val inferred: Boolean = false)

/** Static place hierarchy. Codes: continents "C-XX", countries ISO-3166 alpha-2, regions "CA-SK", cities "CA-SK:regina". */
object Gazetteer {
    const val SPACE_CODE = "SPACE"
    const val WORLD_CODE = "WORLD"

    val places: Map<String, Place>
    internal val surfaces: Map<String, List<Surface>> // keyed by first token

    internal data class Surface(val tokens: List<String>, val code: String, val weak: Boolean)

    private val weakNames = setOf("Jordan", "Chad", "Georgia", "Victoria", "Kingston", "Sofia", "Lima", "Wellington",
        "Perth", "English", "Santiago", "Phoenix", "Niger", "Turkey", "Guinea", "Mali", "Hamilton", "Richmond", "Washington")

    init {
        val p = LinkedHashMap<String, Place>()
        val surf = mutableListOf<Surface>()
        fun load(block: String, level: GeoLevel, codeOf: (String) -> String, parentOf: (String) -> String?) {
            for (line in block.lines()) {
                val f = line.trim().split('|')
                if (f.size < 3) continue
                val code = codeOf(f[0])
                p[code] = Place(code, f[1], level, parentOf(f[2]))
                val baseName = f[1].substringBefore(" (").trim()
                val nameIsAmbiguous = f[1].contains(" (") || baseName in weakNames
                surf += Surface(tokenize(baseName), code, nameIsAmbiguous)
                f.getOrNull(3)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { a ->
                    val weak = a.startsWith("?") || a.removePrefix("?") in weakNames
                    surf += Surface(tokenize(a.removePrefix("?")), code, weak)
                }
            }
        }
        load(GazetteerData.continents, GeoLevel.CONTINENT, { "C-$it" }, { null })
        load(GazetteerData.countries, GeoLevel.COUNTRY, { it }, { "C-$it" })
        load(GazetteerData.regions, GeoLevel.REGION, { it }, { it })
        load(GazetteerData.cities, GeoLevel.CITY, { it }, { it })
        places = p
        surfaces = surf.filter { it.tokens.isNotEmpty() }.groupBy { it.tokens.first() }
            .mapValues { (_, v) -> v.sortedByDescending { it.tokens.size } }
    }

    fun tokenize(s: String): List<String> =
        Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}.'’&-]*").findAll(s).map { it.value }.toList()

    fun get(code: String): Place? = places[code]

    fun ancestors(code: String): List<Place> {
        val out = mutableListOf<Place>()
        var cur = places[code]?.parentCode
        while (cur != null) { val pl = places[cur] ?: break; out += pl; cur = pl.parentCode }
        return out
    }

    fun isAncestor(ancestor: String, code: String) = ancestors(code).any { it.code == ancestor }

    fun children(code: String?): List<Place> = places.values.filter { it.parentCode == code }.sortedBy { it.name }
    fun continents() = children(null)

    fun pathLabel(code: String): String = (listOf(places[code]) + ancestors(code)).filterNotNull().reversed().joinToString(" › ") { it.name }

    fun search(query: String): List<Place> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return places.values.filter { it.name.lowercase().contains(q) }.sortedWith(compareBy({ !it.name.lowercase().startsWith(q) }, { it.level.depth }, { it.name })).take(30)
    }
}

/**
 * Assigns geographic tags from article text. Publisher location is NOT assumed: it is only used,
 * marked as inferred and low-confidence, when the text names no place at all and the source is local.
 */
object GeoClassifier {
    private const val TAG_THRESHOLD = 2.0

    fun classify(
        title: String,
        paragraphs: List<String>,
        categories: List<String> = emptyList(),
        localSourcePlace: String? = null,
    ): List<GeoTag> {
        val raw = HashMap<String, Double>()
        fun scan(text: String, weight: Double) = mentions(text).forEach { (code, _) -> raw.merge(code, weight, Double::plus) }
        scan(title, 3.0)
        paragraphs.forEachIndexed { i, p -> scan(p, if (i < 3) 1.5 else 1.0) }
        for (c in categories) {
            val cc = c.trim().lowercase()
            Gazetteer.places.values.firstOrNull { it.name.lowercase() == cc }?.let { raw.merge(it.code, 2.0, Double::plus) }
        }

        // Propagate to ancestors.
        val total = HashMap<String, Double>()
        for ((code, s) in raw) {
            total.merge(code, s, Double::plus)
            Gazetteer.ancestors(code).forEach { total.merge(it.code, s, Double::plus) }
        }
        val tags = total.filter { it.value >= TAG_THRESHOLD }.mapNotNull { (code, s) ->
            val p = Gazetteer.get(code) ?: return@mapNotNull null
            GeoTag(code, p.name, p.level, minOf(1.0, s / 6.0))
        }.toMutableList()

        if (spaceScore(title, paragraphs) >= 3.0) tags += GeoTag(Gazetteer.SPACE_CODE, "Space", GeoLevel.SPACE, 0.8)

        if (tags.none { it.level != GeoLevel.SPACE } && localSourcePlace != null) {
            val home = Gazetteer.get(localSourcePlace)
            if (home != null) {
                tags += GeoTag(home.code, home.name, home.level, 0.3, inferred = true)
                Gazetteer.ancestors(home.code).forEach { tags += GeoTag(it.code, it.name, it.level, 0.3, inferred = true) }
            }
        }
        return tags.sortedWith(compareByDescending<GeoTag> { it.level.depth }.thenByDescending { it.confidence })
    }

    /** Returns (placeCode, surface) for each place mention, resolving overlaps and ambiguity. */
    fun mentions(text: String): List<Pair<String, String>> {
        val toks = Gazetteer.tokenize(text).map { normalizeToken(it) }
        data class Hit(val candidates: List<Gazetteer.Surface>, val surface: String)
        val hits = mutableListOf<Hit>()
        var i = 0
        while (i < toks.size) {
            val (exact, stripped) = toks[i]
            val options = (Gazetteer.surfaces[exact].orEmpty() + Gazetteer.surfaces[stripped].orEmpty())
            var matched = 0
            var matchedSurfaces: List<Gazetteer.Surface> = emptyList()
            for (s in options.sortedByDescending { it.tokens.size }) {
                val n = s.tokens.size
                if (n < matched || i + n > toks.size) continue
                val ok = (0 until n).all { k -> toks[i + k].first == s.tokens[k] || toks[i + k].second == s.tokens[k] }
                if (!ok) continue
                if (n > matched) { matched = n; matchedSurfaces = listOf(s) } else matchedSurfaces = matchedSurfaces + s
            }
            if (matched > 0) {
                hits += Hit(matchedSurfaces.distinctBy { it.code }, (i until i + matched).joinToString(" ") { toks[it].second })
                i += matched
            } else i++
        }
        val strongCodes = hits.filter { h -> h.candidates.size == 1 && !h.candidates[0].weak }.map { it.candidates[0].code }.toSet()
        val context = strongCodes.flatMap { listOf(it) + Gazetteer.ancestors(it).map { a -> a.code } }.toSet()
        val out = mutableListOf<Pair<String, String>>()
        for (h in hits) {
            // Prefer the most specific candidate when they are on one ancestor chain (D.C. vs U.S.).
            var cands = h.candidates
            cands = cands.filter { c -> cands.none { o -> o !== c && Gazetteer.isAncestor(c.code, o.code) } }
            val resolved = when {
                cands.size == 1 && !cands[0].weak -> cands[0]
                else -> cands.filter { c -> Gazetteer.ancestors(c.code).any { it.code in context } || c.code in strongCodes }
                    .let { if (it.size == 1) it[0] else null }
            }
            resolved?.let { out += it.code to h.surface }
        }
        return out
    }

    private fun normalizeToken(t: String): Pair<String, String> {
        var s = t
        if (s.endsWith("'s") || s.endsWith("’s")) s = s.dropLast(2)
        val stripped = s.trimEnd('.', '\'', '’', '-').let { if ('-' in it && !it.first().isLowerCase()) it.substringBefore('-') else it }
        return s to stripped
    }

    private val spaceRegex by lazy {
        Regex("\\b(" + GazetteerData.spaceTerms.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")\\b", RegexOption.IGNORE_CASE)
    }

    fun spaceScore(title: String, paragraphs: List<String>): Double {
        var score = 0.0
        val distinct = HashSet<String>()
        // "astronomer" and "astronomers" are one term, not two.
        fun key(v: String) = v.lowercase().removeSuffix("s")
        spaceRegex.findAll(title).forEach { score += 2.0; distinct += key(it.value) }
        paragraphs.take(8).forEach { p -> spaceRegex.findAll(p).forEach { score += 0.5; distinct += key(it.value) } }
        return if (distinct.size >= 2) score else score / 2
    }
}
