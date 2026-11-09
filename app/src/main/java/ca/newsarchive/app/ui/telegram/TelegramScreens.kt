@file:OptIn(ExperimentalMaterial3Api::class)

package ca.newsarchive.app.ui.telegram

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ca.newsarchive.app.LocalContainer
import ca.newsarchive.app.data.AddResult
import ca.newsarchive.app.data.SourceStatus
import ca.newsarchive.app.data.TelegramChannelEntity
import ca.newsarchive.app.data.TelegramPostEntity
import ca.newsarchive.app.ui.DetailBar
import ca.newsarchive.app.ui.Fmt
import ca.newsarchive.app.ui.components.EmptyState
import ca.newsarchive.app.ui.components.Nav
import ca.newsarchive.app.ui.components.RemoteImage
import ca.newsarchive.app.ui.components.SectionHeader
import ca.newsarchive.app.ui.components.openUrl
import ca.newsarchive.app.ui.components.toast
import ca.newsarchive.core.telegram.TelegramDirectory
import kotlinx.coroutines.launch

@Composable
fun TelegramScreen(nav: Nav) {
    val c = LocalContainer.current
    val settings by c.settings.flow.collectAsState()
    val channels by remember { c.db.telegram().observeChannels() }.collectAsState(emptyList())
    var channel by remember { mutableStateOf<String?>(null) }
    var category by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var limit by remember { mutableIntStateOf(100) }
    val posts by remember(channel, category, query, limit) {
        c.db.telegram().observePosts(channel, category, query.trim().ifBlank { null }, limit)
    }.collectAsState(emptyList())
    val followed = channels.filter { it.followed }

    if (channels.isEmpty()) {
        EmptyState(
            "No Telegram channels yet",
            "Follow public news channels to read their posts here, separate from conventional publishers. " +
                "This uses Telegram's public web preview (t.me/s/…), which needs no account but only works for public channels with previews enabled.",
            Modifier.fillMaxSize(),
        ) { Button(onClick = { nav.navigate("telegram/channels") }) { Text("Add channels") } }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("Search followed channels") }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(channel == null && category == null, { channel = null; category = null }, label = { Text("All") })
                    followed.map { it.category }.distinct().forEach { cat ->
                        FilterChip(category == cat, { category = if (category == cat) null else cat; channel = null }, label = { Text(cat) })
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    followed.forEach { ch ->
                        FilterChip(channel == ch.username, { channel = if (channel == ch.username) null else ch.username }, label = { Text(ch.title ?: "@${ch.username}") })
                    }
                    TextButton(onClick = { nav.navigate("telegram/channels") }) { Text("Manage") }
                }
            }
        }
        if (posts.isEmpty()) item { EmptyState("No posts", "Posts appear after the next collection. Use refresh at the top to collect now.") }
        items(posts, key = { it.channel + "/" + it.postId }) { p ->
            PostCard(p, channels.firstOrNull { it.username.equals(p.channel, true) }, settings.showImages)
        }
        if (posts.size >= limit) item { TextButton(onClick = { limit += 100 }, modifier = Modifier.fillMaxWidth()) { Text("Load more") } }
    }
}

@Composable
private fun PostCard(p: TelegramPostEntity, ch: TelegramChannelEntity?, showImages: Boolean) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ch?.title ?: "@${p.channel}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(p.postedAt?.let(Fmt::relative) ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        if (p.text.isNotBlank()) SelectionContainer { Text(p.text, style = MaterialTheme.typography.bodyMedium) }
        else Text("(media post without text)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (showImages && p.imageUrl != null) RemoteImage(p.imageUrl, Modifier.padding(top = 6.dp).fillMaxWidth().aspectRatio(16f / 9f))
        Row {
            TextButton(onClick = { openUrl(ctx, p.url) }) { Text("Open in Telegram") }
            p.links.lines().firstOrNull { it.isNotBlank() }?.let { l -> TextButton(onClick = { openUrl(ctx, l) }) { Text("Linked article") } }
            p.views?.let { Text("$it views", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.CenterVertically)) }
        }
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
fun TelegramChannelsScreen(nav: Nav) {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val channels by remember { c.db.telegram().observeChannels() }.collectAsState(emptyList())
    var input by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("General") }
    var editing by remember { mutableStateOf<TelegramChannelEntity?>(null) }

    fun add(u: String, cat: String) = scope.launch {
        when (val r = c.repo.addTelegramChannel(u, cat)) {
            is AddResult.Invalid -> toast(ctx, r.message)
            else -> {
                val norm = ca.newsarchive.core.telegram.TelegramPreview.normalizeUsername(u)
                if (norm != null) runCatching { c.telegram.collect(norm) }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        DetailBar("Telegram channels", nav)
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "How this works: the app reads Telegram's public web preview for each channel. No Telegram account or credentials are used or stored. " +
                            "Private channels, groups and channels with previews disabled cannot be read this way and are marked unavailable. " +
                            "Full access via Telegram's client API (TDLib) would need your own API ID and phone login and is not included.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(input, { input = it }, singleLine = true, label = { Text("@username or t.me link") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(category, { category = it }, singleLine = true, label = { Text("Category") }, modifier = Modifier.fillMaxWidth())
                    Button(enabled = input.isNotBlank(), onClick = { val u = input; input = ""; add(u, category) }, modifier = Modifier.padding(top = 6.dp)) { Text("Add channel") }
                }
            }
            item { SectionHeader("Your channels") }
            if (channels.isEmpty()) item { Text("None yet.", modifier = Modifier.padding(16.dp)) }
            items(channels, key = { it.username }) { ch ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(ch.title ?: "@${ch.username}", fontWeight = FontWeight.SemiBold)
                        Text("@${ch.username} · ${ch.category}", style = MaterialTheme.typography.labelSmall)
                        val status = when (ch.status) {
                            SourceStatus.OK -> "Working" + (ch.lastSuccessAt?.let { " · updated ${Fmt.relative(it)}" } ?: "")
                            SourceStatus.UNAVAILABLE -> "Unavailable"
                            SourceStatus.ERROR -> "Error"
                            else -> "Not checked yet"
                        }
                        Text(status + (ch.statusNote?.let { " – $it" } ?: ""), style = MaterialTheme.typography.labelSmall,
                            color = if (ch.status == SourceStatus.OK) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                        Row {
                            TextButton(onClick = { scope.launch { c.telegram.collect(ch.username) } }) { Text("Check now") }
                            TextButton(onClick = { editing = ch }) { Text("Edit") }
                        }
                    }
                    Switch(ch.followed, { v -> scope.launch { c.repo.setTelegramFollowed(ch.username, v) } })
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
            item { SectionHeader("Suggested public channels", Modifier.padding(top = 12.dp)) }
            item {
                Text("Suggestions only; availability is checked when added. Descriptions identify the publisher, not a judgement of it.",
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp))
            }
            items(TelegramDirectory.all, key = { "dir-" + it.username }) { d ->
                val added = channels.any { it.username.equals(d.username, true) }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.title, fontWeight = FontWeight.SemiBold)
                        Text("@${d.username} · ${d.note}", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(enabled = !added, onClick = { add(d.username, d.category) }) { Text(if (added) "Added" else "Add") }
                }
            }
        }
    }

    editing?.let { ch ->
        var cat by remember(ch.username) { mutableStateOf(ch.category) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(ch.title ?: "@${ch.username}") },
            text = { OutlinedTextField(cat, { cat = it }, label = { Text("Category") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { scope.launch { c.repo.setTelegramCategory(ch.username, cat.ifBlank { "General" }) }; editing = null }) { Text("Save") } },
            dismissButton = {
                TextButton(onClick = { scope.launch { c.repo.removeTelegramChannel(ch.username) }; editing = null }) {
                    Text("Remove channel and its posts", color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }
}
