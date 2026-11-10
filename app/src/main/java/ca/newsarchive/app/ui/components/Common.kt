@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package ca.newsarchive.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ca.newsarchive.app.LocalContainer
import ca.newsarchive.app.data.ArticleListItem
import ca.newsarchive.app.data.Availability
import ca.newsarchive.app.ui.Fmt
import ca.newsarchive.app.ui.theme.SectionLabel
import kotlinx.coroutines.launch

/** Ids of the list the reader was opened from, for previous/next navigation. */
object ReaderQueue {
    var ids: List<Long> = emptyList()
}

data class Nav(
    val openArticle: (Long, List<Long>) -> Unit,
    val openStory: (Long) -> Unit,
    val openCompare: (Long) -> Unit,
    val openSource: (Long) -> Unit,
    val navigate: (String) -> Unit,
    val back: () -> Unit,
)

@Composable
fun ArticleRow(item: ArticleListItem, onOpen: () -> Unit, showImage: Boolean, onOpenStory: ((Long) -> Unit)? = null) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var pickCollection by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = { menu = true }).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                item.title, style = MaterialTheme.typography.titleMedium,
                color = if (item.isRead) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(item.sourceName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(
                    if (item.publishedAt != null) Fmt.relative(item.publishedAt) else "found ${Fmt.relative(item.discoveredAt)}",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
            }
            val badges = buildList {
                Fmt.articleType(item.articleType)?.let { add(it) }
                if (item.publisherCount >= 2) add("${item.publisherCount} outlets")
                if (item.versionCount > 1) add("Updated · ${item.versionCount} versions")
                if (item.availability == Availability.GONE) add("Removed by publisher")
                if (item.extractionStatus == "FEED_ONLY") add("Summary only")
                if (item.extractionStatus == "PAYWALLED") add("Subscriber-only")
            }
            if (badges.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    badges.take(3).forEach { b ->
                        val clickable = b.endsWith("outlets") && item.storyId != null && onOpenStory != null
                        Badge(b, onClick = if (clickable) ({ onOpenStory!!(item.storyId!!) }) else null)
                    }
                }
            }
        }
        if (showImage && item.imageUrl != null) {
            Spacer(Modifier.width(12.dp))
            RemoteImage(item.imageUrl, Modifier.size(76.dp).clip(RoundedCornerShape(4.dp)), maxWidth = 300)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = { scope.launch { c.repo.setBookmarked(item.id, !item.isBookmarked) } }, modifier = Modifier.size(36.dp)) {
                Icon(if (item.isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, if (item.isBookmarked) "Remove bookmark" else "Bookmark", Modifier.size(20.dp))
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.MoreVert, "More", Modifier.size(20.dp)) }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem({ Text("Share") }, onClick = { menu = false; share(ctx, item.title, item.url) })
                    DropdownMenuItem({ Text("Copy original link") }, onClick = { menu = false; copyLink(ctx, item.url) })
                    DropdownMenuItem({ Text(if (item.isSaved) "Remove from Read later" else "Read later") }, onClick = {
                        menu = false; scope.launch { c.repo.setSaved(item.id, !item.isSaved) }
                    })
                    DropdownMenuItem({ Text("Add to collection…") }, onClick = { menu = false; pickCollection = true })
                    DropdownMenuItem({ Text(if (item.isRead) "Mark as unread" else "Mark as read") }, onClick = {
                        menu = false; scope.launch { c.repo.markRead(item.id, !item.isRead) }
                    })
                    DropdownMenuItem({ Text("Open original") }, onClick = { menu = false; openUrl(ctx, item.url) })
                }
            }
        }
    }
    if (pickCollection) CollectionPickerDialog(item.id) { pickCollection = false }
}

@Composable
fun Badge(text: String, onClick: (() -> Unit)? = null) {
    Surface(
        shape = RoundedCornerShape(3.dp),
        color = if (onClick != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        onClick = onClick ?: {}, enabled = onClick != null,
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth()) {
        HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.onSurface)
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title.uppercase(), style = SectionLabel, modifier = Modifier.weight(1f))
            action?.invoke()
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.let { Spacer(Modifier.height(16.dp)); it() }
    }
}

/** A scrolling list of articles with an optional header and "load more". */
@Composable
fun ArticleList(
    items: List<ArticleListItem>,
    nav: Nav,
    modifier: Modifier = Modifier,
    canLoadMore: Boolean = false,
    onLoadMore: () -> Unit = {},
    empty: @Composable () -> Unit = { EmptyState("Nothing here yet", "Articles will appear after the next collection.") },
    header: LazyListScope.() -> Unit = {},
) {
    val settings by LocalContainer.current.settings.flow.collectAsState()
    LazyColumn(modifier.fillMaxSize()) {
        header()
        if (items.isEmpty()) item { empty() }
        items(items, key = { it.id }) { a ->
            ArticleRow(a, onOpen = { nav.openArticle(a.id, items.map { it.id }) }, showImage = settings.showImages, onOpenStory = { sid -> if (a.publisherCount >= 3) nav.openCompare(sid) else nav.openStory(sid) })
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
        if (canLoadMore && items.isNotEmpty()) item {
            TextButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth().padding(8.dp)) { Text("Load more") }
        }
    }
}

@Composable
fun CollectionPickerDialog(articleId: Long, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val collections by remember { c.db.library().observeCollections() }.collectAsState(emptyList())
    val member by remember(articleId) { c.db.library().observeCollectionsFor(articleId) }.collectAsState(emptyList())
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Collections") },
        text = {
            Column {
                if (collections.isEmpty()) Text("No collections yet. Create one below.", style = MaterialTheme.typography.bodyMedium)
                collections.forEach { col ->
                    val checked = col.id in member
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Checkbox(checked, onCheckedChange = {
                            scope.launch { if (checked) c.repo.removeFromCollection(col.id, articleId) else c.repo.addToCollection(col.id, articleId) }
                        })
                        Text(col.name)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newName, { newName = it }, label = { Text("New collection") }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(enabled = newName.isNotBlank(), onClick = {
                        val n = newName; newName = ""
                        scope.launch { val id = c.repo.createCollection(n); c.repo.addToCollection(id, articleId) }
                    }) { Text("Add") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

fun share(ctx: Context, title: String, url: String) {
    val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, title); putExtra(Intent.EXTRA_TEXT, "$title\n$url") }
    ctx.startActivity(Intent.createChooser(i, "Share article"))
}

fun copyLink(ctx: Context, url: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Article link", url))
    Toast.makeText(ctx, "Link copied", Toast.LENGTH_SHORT).show()
}

fun openUrl(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toast.makeText(ctx, "No app can open this link", Toast.LENGTH_SHORT).show() }
}

fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
