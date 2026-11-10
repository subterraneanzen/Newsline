package ca.newsarchive.core.home

import ca.newsarchive.core.geo.Gazetteer

/**
 * Decides which single home-page section each article belongs to, so an article never appears
 * under more than one header.
 *
 * An article goes to the section that best matches its own place or subject: the place it is most
 * confidently about wins, and within the same chain the most specific section wins (a Regina story
 * goes under Saskatchewan rather than Canada). Articles that match no place section fall back to
 * "Recently updated" (if they changed) and then to the catch-all World / Latest sections.
 */
object HomePlacement {
    sealed interface Section { val key: String }
    /** Shows articles tagged with [code] or any place inside it. */
    data class Place(override val key: String, val code: String) : Section
    data class Updated(override val key: String) : Section
    data class CatchAll(override val key: String) : Section

    data class Tag(val code: String, val confidence: Double, val inferred: Boolean)
    data class Candidate(val id: Long, val versionCount: Int, val tags: List<Tag>)

    /** [candidates] newest first; returns up to [perSection] article ids per section key, newest first. */
    fun assign(sections: List<Section>, candidates: List<Candidate>, perSection: Int = 5): Map<String, List<Long>> {
        val out = sections.associate { it.key to ArrayList<Long>() }
        val seen = HashSet<Long>()
        for (a in candidates) {
            if (!seen.add(a.id)) continue
            val best = bestSection(sections, a) ?: continue
            val list = out.getValue(best.key)
            if (list.size < perSection) list += a.id
        }
        return out
    }

    fun bestSection(sections: List<Section>, a: Candidate): Section? {
        // Tags the classifier found directly in the text; inferred parents are implied by the hierarchy.
        val direct = a.tags.filter { !it.inferred }.ifEmpty { a.tags }
        var best: Section? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (s in sections) {
            val score = when (s) {
                is Place -> {
                    val conf = direct.filter { it.code == s.code || Gazetteer.isAncestor(s.code, it.code) }.maxOfOrNull { it.confidence }
                    // Confidence decides between different places; depth breaks ties in favour of the more specific section.
                    conf?.let { it * 100 + (Gazetteer.get(s.code)?.level?.depth ?: 0) }
                }
                is Updated -> if (a.versionCount >= 2) -1.0 else null
                is CatchAll -> -2.0
            } ?: continue
            if (score > bestScore) { best = s; bestScore = score } // ties keep the earlier section
        }
        return best
    }
}
