package ca.newsarchive.core.model

/**
 * Stores article blocks as text, one block per line: `T|text` (T = P/H/Q/L/I), then optionally
 * `⟨SPANS⟩span⟨SP⟩span…` for inline formatting, then optionally `⟨ATTR⟩key=value⟨KV⟩…` for display
 * layout (heading level, list numbering, photo address and size, captions). Archives written before
 * either segment existed decode unchanged.
 */
object BlockCodec {
    private const val SPANS = '\u0001'  // text ⟨SPANS⟩ span ⟨SP⟩ span …
    private const val SP = '\u0002'     // between spans
    private const val FS = '\u0003'     // between a span's fields: start FS end FS style FS href
    private const val ATTR = '\u0004'   // … ⟨ATTR⟩ key=value ⟨KV⟩ key=value
    private const val KV = '\u0005'
    private val controls = charArrayOf('\n', '\r', SPANS, SP, FS, ATTR, KV)

    private fun safe(s: String): String {
        var out = s
        for (c in controls) out = out.replace(c, ' ')
        return out
    }

    fun encode(blocks: List<Block>): String = blocks.joinToString("\n") { b ->
        val t = when (b.type) {
            BlockType.HEADING -> 'H'; BlockType.QUOTE -> 'Q'; BlockType.LIST_ITEM -> 'L'; BlockType.IMAGE -> 'I'; else -> 'P'
        }
        val sb = StringBuilder().append(t).append('|').append(safe(b.text))
        if (b.spans.isNotEmpty()) {
            sb.append(SPANS).append(b.spans.joinToString(SP.toString()) { sp ->
                val style = when (sp.style) { InlineStyle.BOLD -> 'B'; InlineStyle.ITALIC -> 'I'; InlineStyle.CODE -> 'C'; InlineStyle.LINK -> 'L' }
                "${sp.start}$FS${sp.end}$FS$style$FS${sp.href?.let(::safe) ?: ""}"
            })
        }
        val attrs = buildList {
            if (b.level != 0) add("lv=${b.level}")
            if (b.ordinal != 0) add("n=${b.ordinal}")
            if (b.width > 0) add("w=${b.width}")
            if (b.height > 0) add("h=${b.height}")
            if (b.isCaption) add("cap=1")
            b.caption?.let { add("c=${safe(it)}") }
            b.imageUrl?.let { add("src=${safe(it)}") }
        }
        if (attrs.isNotEmpty()) sb.append(ATTR).append(attrs.joinToString(KV.toString()))
        sb.toString()
    }

    fun decode(s: String): List<Block> = s.lines().filter { it.length > 2 && it[1] == '|' }.mapNotNull { line ->
        val type = when (line[0]) {
            'H' -> BlockType.HEADING; 'Q' -> BlockType.QUOTE; 'L' -> BlockType.LIST_ITEM; 'I' -> BlockType.IMAGE; else -> BlockType.PARAGRAPH
        }
        val main = line.substringBefore(ATTR)
        val attrs = line.substringAfter(ATTR, "").takeIf { it.isNotEmpty() }?.split(KV)
            ?.associate { it.substringBefore('=') to it.substringAfter('=', "") }.orEmpty()
        val text = main.substringBefore(SPANS).substring(2)
        val spans = main.substringAfter(SPANS, "").takeIf { it.isNotEmpty() }?.split(SP)?.mapNotNull { enc ->
            val f = enc.split(FS)
            if (f.size < 3) return@mapNotNull null
            val start = f[0].toIntOrNull() ?: return@mapNotNull null
            val end = f[1].toIntOrNull() ?: return@mapNotNull null
            val style = when (f[2]) { "B" -> InlineStyle.BOLD; "I" -> InlineStyle.ITALIC; "C" -> InlineStyle.CODE; "L" -> InlineStyle.LINK; else -> return@mapNotNull null }
            // Clamp to the text so an offset can never index out of bounds at render time.
            val a = start.coerceIn(0, text.length); val b = end.coerceIn(a, text.length)
            if (a == b) null else Span(a, b, style, f.getOrNull(3)?.ifEmpty { null })
        }.orEmpty()
        val src = attrs["src"]?.ifBlank { null }
        if (type == BlockType.IMAGE && src == null) return@mapNotNull null
        Block(
            type, text, spans,
            level = attrs["lv"]?.toIntOrNull() ?: 0,
            ordinal = attrs["n"]?.toIntOrNull() ?: 0,
            imageUrl = src,
            caption = attrs["c"]?.ifBlank { null },
            width = attrs["w"]?.toIntOrNull() ?: 0,
            height = attrs["h"]?.toIntOrNull() ?: 0,
            isCaption = attrs["cap"] == "1",
        )
    }
}
