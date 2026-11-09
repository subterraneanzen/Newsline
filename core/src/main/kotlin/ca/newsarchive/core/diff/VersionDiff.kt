package ca.newsarchive.core.diff

import ca.newsarchive.core.text.TextNormalizer

enum class ChangeType { UNCHANGED, ADDED, REMOVED, MODIFIED }

/** A run of words inside a modified paragraph. */
data class WordSpan(val text: String, val type: ChangeType)

data class DiffBlock(
    val type: ChangeType,
    /** Paragraph in the older version (null for ADDED). */
    val oldText: String?,
    /** Paragraph in the newer version (null for REMOVED). */
    val newText: String?,
    /** Word-level detail, only for MODIFIED blocks. */
    val spans: List<WordSpan> = emptyList(),
)

data class FieldChange(val field: String, val old: String?, val new: String?)

data class VersionComparison(
    val blocks: List<DiffBlock>,
    val fieldChanges: List<FieldChange>,
) {
    val added get() = blocks.count { it.type == ChangeType.ADDED }
    val removed get() = blocks.count { it.type == ChangeType.REMOVED }
    val modified get() = blocks.count { it.type == ChangeType.MODIFIED }
    val headlineChanged get() = fieldChanges.any { it.field == "Headline" }
    val hasChanges get() = added + removed + modified > 0 || fieldChanges.isNotEmpty()

    /** Compact description, e.g. "Headline changed · 2 added · 1 modified". */
    fun summary(): String {
        val parts = mutableListOf<String>()
        if (headlineChanged) parts += "Headline changed"
        fieldChanges.filter { it.field != "Headline" }.forEach { parts += "${it.field} changed" }
        if (added > 0) parts += "$added paragraph${if (added > 1) "s" else ""} added"
        if (removed > 0) parts += "$removed removed"
        if (modified > 0) parts += "$modified modified"
        return if (parts.isEmpty()) "No meaningful changes" else parts.joinToString(" · ")
    }
}

data class VersionSnapshot(
    val title: String?,
    val author: String?,
    val publisherModifiedAt: Long?,
    val paragraphs: List<String>,
)

/**
 * Paragraph-level diff (LCS over normalized paragraphs) followed by pairing of nearby
 * removed/added paragraphs into MODIFIED blocks with a word-level diff.
 */
object VersionDiff {

    fun compare(old: VersionSnapshot, new: VersionSnapshot): VersionComparison {
        val fields = buildList {
            if (norm(old.title) != norm(new.title)) add(FieldChange("Headline", old.title, new.title))
            if (norm(old.author) != norm(new.author)) add(FieldChange("Author", old.author, new.author))
            if (old.publisherModifiedAt != new.publisherModifiedAt && new.publisherModifiedAt != null)
                add(FieldChange("Publisher modified date", old.publisherModifiedAt?.toString(), new.publisherModifiedAt.toString()))
        }
        return VersionComparison(diffParagraphs(old.paragraphs, new.paragraphs), fields)
    }

    /** True when two bodies differ in a way that should produce a new version. */
    fun isMeaningfulChange(old: VersionSnapshot, new: VersionSnapshot): Boolean {
        if (norm(old.title) != norm(new.title)) return true
        return TextNormalizer.bodyHash(old.paragraphs) != TextNormalizer.bodyHash(new.paragraphs)
    }

    private fun norm(s: String?) = TextNormalizer.comparable(s ?: "")

    fun diffParagraphs(a: List<String>, b: List<String>): List<DiffBlock> {
        val na = a.map(TextNormalizer::comparable)
        val nb = b.map(TextNormalizer::comparable)
        val ops = lcsOps(na, nb)
        // Convert edit script into blocks, pairing removed+added runs.
        val out = mutableListOf<DiffBlock>()
        var i = 0
        while (i < ops.size) {
            val op = ops[i]
            if (op.type == ChangeType.UNCHANGED) {
                out += DiffBlock(ChangeType.UNCHANGED, a[op.ai], b[op.bi]); i++; continue
            }
            // Gather a run of non-equal ops.
            val removed = mutableListOf<Int>()
            val added = mutableListOf<Int>()
            while (i < ops.size && ops[i].type != ChangeType.UNCHANGED) {
                if (ops[i].type == ChangeType.REMOVED) removed += ops[i].ai else added += ops[i].bi
                i++
            }
            out += pairRun(a, b, removed, added)
        }
        return out
    }

    private fun pairRun(a: List<String>, b: List<String>, removed: List<Int>, added: List<Int>): List<DiffBlock> {
        val usedAdded = BooleanArray(added.size)
        val result = mutableListOf<DiffBlock>()
        val pairedFor = HashMap<Int, Int>() // added index -> removed index
        for (r in removed) {
            var best = -1; var bestSim = 0.0
            for ((k, ad) in added.withIndex()) {
                if (usedAdded[k]) continue
                val s = similarity(a[r], b[ad])
                if (s > bestSim) { bestSim = s; best = k }
            }
            if (best >= 0 && bestSim >= 0.45) {
                usedAdded[best] = true
                pairedFor[best] = r
            }
        }
        val pairedRemoved = pairedFor.values.toSet()
        // Removed paragraphs that were not paired come first, then new-order paragraphs.
        for (r in removed) if (r !in pairedRemoved) result += DiffBlock(ChangeType.REMOVED, a[r], null)
        for ((k, ad) in added.withIndex()) {
            val r = pairedFor[k]
            result += if (r != null) DiffBlock(ChangeType.MODIFIED, a[r], b[ad], wordDiff(a[r], b[ad]))
            else DiffBlock(ChangeType.ADDED, null, b[ad])
        }
        return result
    }

    private data class Op(val type: ChangeType, val ai: Int, val bi: Int)

    private fun lcsOps(a: List<String>, b: List<String>): List<Op> {
        val n = a.size; val m = b.size
        // Trim common prefix/suffix to keep the table small for long articles.
        var pre = 0
        while (pre < n && pre < m && a[pre] == b[pre]) pre++
        var suf = 0
        while (suf < n - pre && suf < m - pre && a[n - 1 - suf] == b[m - 1 - suf]) suf++
        val ops = mutableListOf<Op>()
        for (k in 0 until pre) ops += Op(ChangeType.UNCHANGED, k, k)
        val a2 = a.subList(pre, n - suf); val b2 = b.subList(pre, m - suf)
        val dp = Array(a2.size + 1) { IntArray(b2.size + 1) }
        for (x in a2.indices.reversed()) for (y in b2.indices.reversed()) {
            dp[x][y] = if (a2[x] == b2[y]) dp[x + 1][y + 1] + 1 else maxOf(dp[x + 1][y], dp[x][y + 1])
        }
        var x = 0; var y = 0
        while (x < a2.size && y < b2.size) {
            when {
                a2[x] == b2[y] -> { ops += Op(ChangeType.UNCHANGED, pre + x, pre + y); x++; y++ }
                dp[x + 1][y] >= dp[x][y + 1] -> { ops += Op(ChangeType.REMOVED, pre + x, -1); x++ }
                else -> { ops += Op(ChangeType.ADDED, -1, pre + y); y++ }
            }
        }
        while (x < a2.size) { ops += Op(ChangeType.REMOVED, pre + x, -1); x++ }
        while (y < b2.size) { ops += Op(ChangeType.ADDED, -1, pre + y); y++ }
        for (k in 0 until suf) ops += Op(ChangeType.UNCHANGED, n - suf + k, m - suf + k)
        return ops
    }

    /** Blend of unigram and bigram Dice coefficients, ignoring punctuation. */
    fun similarity(x: String, y: String): Double {
        val wx = words(TextNormalizer.comparable(x)); val wy = words(TextNormalizer.comparable(y))
        if (wx.isEmpty() || wy.isEmpty()) return 0.0
        fun dice(p: List<String>, q: List<String>): Double {
            val bx = p.groupingBy { it }.eachCount(); val by = q.groupingBy { it }.eachCount()
            val inter = bx.entries.sumOf { (k, v) -> minOf(v, by[k] ?: 0) }
            return 2.0 * inter / (p.size + q.size)
        }
        val uni = dice(wx, wy)
        val bi = dice(wx.zipWithNext { p, q -> "$p $q" }.ifEmpty { wx }, wy.zipWithNext { p, q -> "$p $q" }.ifEmpty { wy })
        return uni * 0.6 + bi * 0.4
    }

    private fun words(s: String) = s.split(' ').map { it.trim('.', ',', ';', ':', '!', '?', '"', '\'', '(', ')') }.filter { it.isNotEmpty() }

    /** Word-level LCS diff producing merged spans. */
    fun wordDiff(old: String, new: String): List<WordSpan> {
        val a = old.split(' ').filter { it.isNotEmpty() }
        val b = new.split(' ').filter { it.isNotEmpty() }
        val ka = a.map(TextNormalizer::comparable); val kb = b.map(TextNormalizer::comparable)
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (x in a.indices.reversed()) for (y in b.indices.reversed()) {
            dp[x][y] = if (ka[x] == kb[y]) dp[x + 1][y + 1] + 1 else maxOf(dp[x + 1][y], dp[x][y + 1])
        }
        val raw = mutableListOf<WordSpan>()
        var x = 0; var y = 0
        while (x < a.size && y < b.size) {
            when {
                ka[x] == kb[y] -> { raw += WordSpan(b[y], ChangeType.UNCHANGED); x++; y++ }
                dp[x + 1][y] >= dp[x][y + 1] -> { raw += WordSpan(a[x], ChangeType.REMOVED); x++ }
                else -> { raw += WordSpan(b[y], ChangeType.ADDED); y++ }
            }
        }
        while (x < a.size) raw += WordSpan(a[x++], ChangeType.REMOVED)
        while (y < b.size) raw += WordSpan(b[y++], ChangeType.ADDED)
        // Merge consecutive spans of the same type.
        val merged = mutableListOf<WordSpan>()
        for (s in raw) {
            val last = merged.lastOrNull()
            if (last != null && last.type == s.type) merged[merged.size - 1] = WordSpan(last.text + " " + s.text, s.type)
            else merged += s
        }
        return merged
    }
}
