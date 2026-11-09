@file:OptIn(ExperimentalMaterial3Api::class)

package ca.newsarchive.app.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import ca.newsarchive.app.LocalContainer
import ca.newsarchive.app.data.ArticleVersionEntity
import ca.newsarchive.app.ui.DetailBar
import ca.newsarchive.app.ui.Fmt
import ca.newsarchive.app.ui.components.EmptyState
import ca.newsarchive.app.ui.components.Nav
import ca.newsarchive.app.ui.theme.DiffColors
import ca.newsarchive.app.ui.theme.Serif
import ca.newsarchive.core.diff.ChangeType
import ca.newsarchive.core.diff.DiffBlock
import ca.newsarchive.core.diff.VersionDiff
import ca.newsarchive.core.diff.VersionSnapshot

@Composable
fun HistoryScreen(articleId: Long, nav: Nav) {
    val c = LocalContainer.current
    val versions by remember(articleId) { c.db.versions().observeFor(articleId) }.collectAsState(null)
    val article by remember(articleId) { c.db.articles().observe(articleId) }.collectAsState(null)
    var older by remember { mutableIntStateOf(-1) }
    var newer by remember { mutableIntStateOf(-1) }
    var mode by remember { mutableIntStateOf(0) } // 0 = changes, 1 = read older, 2 = read newer
    var hideUnchanged by remember { mutableStateOf(true) }

    val vs = versions
    LaunchedEffect(vs?.size) {
        if (vs != null && vs.isNotEmpty() && (older < 0 || newer < 0)) {
            newer = vs.last().versionNo
            older = if (vs.size >= 2) vs[vs.size - 2].versionNo else vs.last().versionNo
        }
    }

    Column(Modifier.fillMaxSize()) {
        DetailBar("Article history", nav)
        if (vs == null || article == null) return@Column
        if (vs.isEmpty()) { EmptyState("No snapshots", "This article has no archived versions."); return@Column }
        val a = article!!
        val vOld = vs.firstOrNull { it.versionNo == older } ?: vs.first()
        val vNew = vs.firstOrNull { it.versionNo == newer } ?: vs.last()
        val (lo, hi) = if (vOld.versionNo <= vNew.versionNo) vOld to vNew else vNew to vOld
        val cmp = remember(lo.id, hi.id) {
            VersionDiff.compare(
                VersionSnapshot(lo.title, lo.author, lo.publisherModifiedAt, lo.paragraphs()),
                VersionSnapshot(hi.title, hi.author, hi.publisherModifiedAt, hi.paragraphs()),
            )
        }

        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(Modifier.padding(16.dp)) {
                    Text(a.title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Times below are when this app captured each snapshot. The publisher may have changed the article earlier; " +
                            "the app only knows when it noticed." + (a.publisherModifiedAt?.let { " Publisher-reported last update: ${Fmt.full(it)}." } ?: ""),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (a.availability == "GONE") Text("No longer available from the publisher. All archived versions are preserved.",
                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                }
            }
            item { SectionLabel("Versions (tap to choose two to compare)") }
            items(vs, key = { it.id }) { v -> VersionRow(v, isFirst = v.versionNo == 1, isCurrent = v.versionNo == vs.last().versionNo, older, newer,
                onPickOld = { older = v.versionNo }, onPickNew = { newer = v.versionNo }) }
            if (vs.size == 1) item {
                Text("Only one version has been captured so far. The app re-checks accessible articles periodically and records a new version when the text meaningfully changes.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
            }
            if (vs.size > 1) {
                item {
                    HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.onSurface)
                    Text("Comparing version ${lo.versionNo} (${Fmt.full(lo.capturedAt)}) → version ${hi.versionNo} (${Fmt.full(hi.capturedAt)})",
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(16.dp, 8.dp))
                    Text(cmp.summary(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.padding(16.dp).fillMaxWidth()) {
                        listOf("Changes", "v${lo.versionNo} text", "v${hi.versionNo} text").forEachIndexed { i, l ->
                            SegmentedButton(selected = mode == i, onClick = { mode = i }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(l) }
                        }
                    }
                    if (mode == 0) Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(hideUnchanged, { hideUnchanged = !hideUnchanged }, label = { Text("Hide unchanged paragraphs") })
                    }
                    if (mode == 0) Legend()
                }
                if (mode == 0) {
                    cmp.fieldChanges.forEach { f ->
                        item {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                                Text(f.field + " changed", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                                val (o, n) = if (f.field == "Publisher modified date")
                                    (f.old?.toLongOrNull()?.let(Fmt::full) ?: "—") to (f.new?.toLongOrNull()?.let(Fmt::full) ?: "—")
                                else (f.old ?: "—") to (f.new ?: "—")
                                Text(o, modifier = Modifier.fillMaxWidth().background(DiffColors.removed()).padding(8.dp), color = DiffColors.removedText(), textDecoration = TextDecoration.LineThrough)
                                Text(n, modifier = Modifier.fillMaxWidth().background(DiffColors.added()).padding(8.dp), color = DiffColors.addedText())
                            }
                        }
                    }
                    val blocks = if (hideUnchanged) cmp.blocks.filter { it.type != ChangeType.UNCHANGED } else cmp.blocks
                    if (blocks.isEmpty()) item { Text("Body text is identical.", modifier = Modifier.padding(16.dp)) }
                    items(blocks.size) { i -> DiffParagraph(blocks[i]) }
                } else {
                    val v = if (mode == 1) lo else hi
                    item { Text(v.title, fontFamily = Serif, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp)) }
                    items(v.paragraphs()) { p -> Text(p, fontFamily = Serif, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun SectionLabel(t: String) {
    Text(t.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp, 8.dp))
}

@Composable
private fun VersionRow(v: ArticleVersionEntity, isFirst: Boolean, isCurrent: Boolean, older: Int, newer: Int, onPickOld: () -> Unit, onPickNew: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "Version ${v.versionNo}" + when { isFirst && isCurrent -> " · original & current"; isFirst -> " · original capture"; isCurrent -> " · current"; else -> "" },
                fontWeight = FontWeight.SemiBold,
            )
            Text("Captured ${Fmt.full(v.capturedAt)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            v.publisherModifiedAt?.let { Text("Publisher-reported update: ${Fmt.full(it)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            v.changeSummary?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        PickBox("A", v.versionNo == older, onPickOld)
        Spacer(Modifier.width(6.dp))
        PickBox("B", v.versionNo == newer, onPickNew)
    }
}

@Composable
private fun PickBox(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp).background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold) }
}

@Composable
private fun Legend() {
    Row(Modifier.padding(16.dp, 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LegendItem("Added", DiffColors.added(), DiffColors.addedText())
        LegendItem("Removed", DiffColors.removed(), DiffColors.removedText())
        LegendItem("Modified", DiffColors.modified(), MaterialTheme.colorScheme.onSurface)
        LegendItem("Unchanged", MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LegendItem(t: String, bg: Color, fg: Color) {
    Text(t, color = fg, style = MaterialTheme.typography.labelSmall, modifier = Modifier.background(bg).padding(horizontal = 6.dp, vertical = 2.dp))
}

@Composable
private fun DiffParagraph(b: DiffBlock) {
    val mod = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)
    when (b.type) {
        ChangeType.UNCHANGED -> Text(b.newText.orEmpty(), fontFamily = Serif, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = mod.padding(4.dp))
        ChangeType.ADDED -> Text("+ " + b.newText.orEmpty(), fontFamily = Serif, color = DiffColors.addedText(), modifier = mod.background(DiffColors.added()).padding(8.dp))
        ChangeType.REMOVED -> Text("− " + b.oldText.orEmpty(), fontFamily = Serif, color = DiffColors.removedText(), textDecoration = TextDecoration.LineThrough,
            modifier = mod.background(DiffColors.removed()).padding(8.dp))
        ChangeType.MODIFIED -> {
            val addedBg = DiffColors.added(); val addedFg = DiffColors.addedText(); val remBg = DiffColors.removed(); val remFg = DiffColors.removedText()
            val text = buildAnnotatedString {
                b.spans.forEachIndexed { i, s ->
                    if (i > 0) append(' ')
                    when (s.type) {
                        ChangeType.ADDED -> withStyle(SpanStyle(background = addedBg, color = addedFg, fontWeight = FontWeight.SemiBold)) { append(s.text) }
                        ChangeType.REMOVED -> withStyle(SpanStyle(background = remBg, color = remFg, textDecoration = TextDecoration.LineThrough)) { append(s.text) }
                        else -> append(s.text)
                    }
                }
            }
            Text(text, fontFamily = Serif, modifier = mod.background(DiffColors.modified()).padding(8.dp))
        }
    }
}
