package ca.newsarchive.core.cluster

import ca.newsarchive.core.text.TextNormalizer
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Compact representation of an article used to decide whether two articles report the same story.
 * Serializable to a single string so it can be stored alongside the article.
 */
data class StorySignature(
    val titleTokens: Set<String>,
    val bodyTerms: Map<String, Int>,
    val entities: Set<String>,
    val places: Set<String>,
    val time: Long,
) {
    fun serialize(): String = buildString {
        append(time).append('\u0001')
        append(titleTokens.joinToString(" ")).append('\u0001')
        append(bodyTerms.entries.joinToString(" ") { "${it.key}:${it.value}" }).append('\u0001')
        append(entities.joinToString("|")).append('\u0001')
        append(places.joinToString("|"))
    }

    companion object {
        fun deserialize(s: String): StorySignature? = runCatching {
            val p = s.split('\u0001')
            StorySignature(
                time = p[0].toLong(),
                titleTokens = p[1].split(' ').filter { it.isNotEmpty() }.toSet(),
                bodyTerms = p[2].split(' ').filter { ':' in it }.associate { it.substringBeforeLast(':') to it.substringAfterLast(':').toInt() },
                entities = p[3].split('|').filter { it.isNotEmpty() }.toSet(),
                places = p.getOrNull(4)?.split('|')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet(),
            )
        }.getOrNull()
    }
}

data class ClusterMatch(val clusterId: Long, val score: Double)

object StoryClusterer {
    /** Maximum distance in time between articles in the same story match. */
    const val TIME_WINDOW_MS = 4L * 24 * 3600 * 1000
    const val THRESHOLD = 0.34

    private val stop = (
        "a an the and or but if then than so of in on at to for from by with without about into over under after before " +
            "is are was were be been being has have had do does did will would can could should may might must shall it its " +
            "this that these those he she they we you i his her their our your them him us me my not no yes as up down out " +
            "new news says said say told tells report reports reported update updates live latest just also more most very " +
            "how what why when where who whom which while amid among against during through per via vs one two three year " +
            "years day days week weeks today yesterday tomorrow first last time people says watch video photos opinion " +
            "le la les un une des du de et en dans pour sur par au aux ce cette ces est sont qui que il elle ils elles " +
            "plus pas ne se sa son ses leur leurs avec été être"
        ).split(' ').toSet()

    private val sentenceStarters = setOf("The", "A", "An", "In", "On", "At", "But", "And", "He", "She", "They", "It", "This", "That", "After", "When", "As", "For", "If", "We", "I")

    fun tokens(text: String): List<String> =
        TextNormalizer.comparable(text).split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length > 2 && it !in stop && !it.all(Char::isDigit) }
            .map(::stem)

    private fun stem(w: String): String = when {
        w.length > 5 && w.endsWith("ies") -> w.dropLast(3) + "y"
        w.length > 5 && w.endsWith("ing") -> w.dropLast(3)
        w.length > 4 && w.endsWith("ed") -> w.dropLast(2)
        w.length > 4 && w.endsWith("es") && !w.endsWith("ses") -> w.dropLast(2)
        w.length > 3 && w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
        else -> w
    }

    /** Capitalised word sequences (people, organisations, places) from the original-case text. */
    fun entities(text: String): Set<String> {
        val out = HashSet<String>()
        val re = Regex("\\b(\\p{Lu}[\\p{L}'’.-]+(?:\\s+(?:of|de|du|la|van|von|al|bin)?\\s*\\p{Lu}[\\p{L}'’.-]+)*)")
        for (sentence in text.split(Regex("(?<=[.!?:;])\\s+|\\n"))) {
            for (m in re.findAll(sentence)) {
                var e = m.value.trim().trimEnd('.', '\'', '’')
                if (m.range.first == 0 || sentence.substring(0, m.range.first).isBlank()) {
                    val first = e.substringBefore(' ')
                    if (first in sentenceStarters) e = e.substringAfter(' ', "")
                }
                if (e.length < 3) continue
                val norm = TextNormalizer.comparable(e).replace(Regex("'s$"), "")
                if (norm in stop || norm.length < 3) continue
                out += norm
            }
        }
        // Also include all-caps acronyms (NASA, RCMP, NATO).
        Regex("\\b[A-Z]{3,6}\\b").findAll(text).forEach { out += it.value.lowercase() }
        return out
    }

    fun signature(title: String, lead: String, places: Set<String>, time: Long): StorySignature {
        val body = tokens("$title $lead").groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(60).associate { it.key to it.value }
        return StorySignature(
            titleTokens = tokens(title).toSet(),
            bodyTerms = body,
            entities = (entities(title) + entities(lead)).take(40).toSet(),
            places = places,
            time = time,
        )
    }

    fun similarity(a: StorySignature, b: StorySignature): Double {
        if (abs(a.time - b.time) > TIME_WINDOW_MS) return 0.0
        val titleSim = jaccard(a.titleTokens, b.titleTokens)
        val bodySim = cosine(a.bodyTerms, b.bodyTerms)
        val sharedEntities = a.entities.intersect(b.entities)
        val entitySim = if (a.entities.isEmpty() || b.entities.isEmpty()) 0.0
            else sharedEntities.size.toDouble() / minOf(a.entities.size, b.entities.size)
        // Guard against merging on generic keyword overlap: require specific shared evidence.
        val gated = sharedEntities.size >= 2 || (sharedEntities.isNotEmpty() && titleSim >= 0.25) || titleSim >= 0.55
        if (!gated) return 0.0
        var score = 0.40 * titleSim + 0.30 * bodySim + 0.30 * entitySim
        if (a.places.isNotEmpty() && b.places.isNotEmpty() && a.places.intersect(b.places).isEmpty()) score *= 0.6
        // Decay slightly with time distance.
        val hours = abs(a.time - b.time) / 3_600_000.0
        score *= 1.0 - minOf(hours / 96.0, 1.0) * 0.25
        return score
    }

    /**
     * Picks the best existing cluster for a new article. Each candidate is a cluster id with
     * the signatures of (a sample of) its members. Returns null when nothing is similar enough.
     */
    fun assign(sig: StorySignature, candidates: Map<Long, List<StorySignature>>): ClusterMatch? {
        var best: ClusterMatch? = null
        for ((id, members) in candidates) {
            if (members.isEmpty()) continue
            val sims = members.map { similarity(sig, it) }.sortedDescending()
            // Strongest link, tempered by agreement with a second member when available.
            val score = if (sims.size >= 2) sims[0] * 0.75 + sims[1] * 0.25 else sims[0]
            if (sims[0] >= THRESHOLD && score >= THRESHOLD * 0.85 && (best == null || score > best.score)) best = ClusterMatch(id, score)
        }
        return best
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return a.intersect(b).size.toDouble() / a.union(b).size
    }

    private fun cosine(a: Map<String, Int>, b: Map<String, Int>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var dot = 0.0
        for ((k, v) in a) b[k]?.let { dot += v * it }
        val na = sqrt(a.values.sumOf { it * it }.toDouble()); val nb = sqrt(b.values.sumOf { it * it }.toDouble())
        return dot / (na * nb)
    }
}
