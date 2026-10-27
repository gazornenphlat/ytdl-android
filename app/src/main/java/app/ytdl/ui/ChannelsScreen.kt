package app.ytdl.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.ytdl.core.Channel
import app.ytdl.core.ChannelInfo
import app.ytdl.core.Lookup
import app.ytdl.core.Scheduler
import app.ytdl.core.Store
import app.ytdl.core.validateChannel
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ChannelsScreen(ui: Ui, shared: String?, consumeShared: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val v = ui.version
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>() }
    var adding by remember { mutableStateOf<String?>(null) }   // non-null = add dialog open (with prefill)
    var editing by remember { mutableStateOf<Channel?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(shared) { if (shared != null) { adding = shared; consumeShared() } }

    val lists = Store.lists()
    val rows = Store.channels.toList()
        .filter { filter.isEmpty() || it.list.equals(filter, true) }
        .filter { query.isBlank() || "${it.name} ${it.list} ${it.id}".contains(query.trim(), true) }
        .sortedBy { it.name.lowercase() }

    Page("Channels", "${Store.channels.size} channels · ${lists.size} lists", header = {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            NeuField(query, { query = it }, "Search channels, lists or IDs…", Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp, horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                (listOf("") + lists).forEach { l ->
                    Box(Modifier.noRippleClick { filter = l }) {
                        Pill(l.ifEmpty { "All" }, accent = filter == l, flat = filter != l)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NeuButton("+ Add channel", { adding = "" }, Modifier.weight(1f), accent = true)
                NeuButton(if (selected.isEmpty()) "Delete" else "Delete (${selected.size})",
                    { confirmDelete = true }, danger = true, enabled = selected.isNotEmpty())
            }
        }
    }) {
        if (rows.isEmpty()) item {
            NeuCard(Modifier.fillMaxWidth()) {
                Text(if (Store.channels.isEmpty()) "No channels yet. Tap “Add channel”, share a YouTube link to ytdl, or import your PC's FlexGet config in Settings."
                     else "No channels match.", style = Small)
            }
        }
        items(rows, key = { it.name }) { c ->
            val sel = c.name in selected
            Box(
                Modifier.fillMaxWidth()
                    .then(if (sel) Modifier.neuInset(16.dp, 4.dp) else Modifier.neuRaised(16.dp, 5.dp))
                    .noRippleClick { if (sel) selected.remove(c.name) else selected.add(c.name) }
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NeuCheck(sel)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name, style = Body.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(Scheduler.describe(c), style = Small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Pill(c.list)
                            if (c.wav) Pill("WAV", accent = true) else Pill("Video", flat = true)
                            if (Store.channels.count { it.id == c.id } > 1) Pill("duplicate", color = Warn)
                        }
                    }
                    IconBtn(Icons.Outlined.OpenInNew, "Open on YouTube") {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/channel/${c.id}")))
                    }
                    Spacer(Modifier.width(8.dp))
                    IconBtn(Icons.Outlined.Edit, "Edit") { editing = c }
                }
            }
        }
    }

    adding?.let { pre -> AddChannelDialog(pre, lists) { adding = null } }
    editing?.let { c -> EditChannelDialog(c, lists) { editing = null } }
    if (confirmDelete) ConfirmDialog(
        "Delete ${selected.size} channel${if (selected.size == 1) "" else "s"}?",
        "They'll stop being checked. Videos you've already downloaded stay where they are.\n\n" +
            selected.take(10).joinToString("\n") + if (selected.size > 10) "\n…and ${selected.size - 10} more" else "",
        "Delete",
        onConfirm = {
            Store.deleteChannels(selected.toSet())
            Toast.makeText(ctx, "Deleted ${selected.size} channel(s)", Toast.LENGTH_SHORT).show()
            selected.clear(); confirmDelete = false
        },
        onDismiss = { confirmDelete = false },
    )
}

@Composable
fun IconBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).neuRaised(12.dp, 4.dp).noRippleClick(onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = Accent, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun NeuDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.padding(18.dp).fillMaxWidth().heightIn(max = 680.dp).neuRaised(20.dp, 8.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(22.dp)) { content() }
        }
    }
}

@Composable
fun ConfirmDialog(title: String, body: String, okText: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    NeuDialog(onDismiss) {
        Text(title, style = H2)
        Spacer(Modifier.height(12.dp))
        Text(body, style = Small)
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            NeuButton("Cancel", onDismiss)
            NeuButton(okText, onConfirm, danger = true)
        }
    }
}

@Composable
private fun StepTitle(n: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
        Box(Modifier.size(26.dp).neuRaised(13.dp, 3.dp), contentAlignment = Alignment.Center) {
            Text("$n", style = Small.copy(color = Accent, fontWeight = FontWeight.Bold))
        }
        Spacer(Modifier.width(10.dp))
        Label(text)
    }
}

@Composable
fun ListPicker(value: String, onChange: (String) -> Unit, lists: List<String>) {
    Column {
        NeuField(value, onChange, "List name, e.g. MUSIC", Modifier.fillMaxWidth())
        if (lists.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                lists.forEach { l ->
                    Box(Modifier.noRippleClick { onChange(l) }) {
                        Pill(l, accent = value.equals(l, true), flat = !value.equals(l, true))
                    }
                }
            }
            Text("Tap a list, or type a new name to create one", style = Small, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
fun AddChannelDialog(prefill: String, lists: List<String>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf(prefill) }
    var loading by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<ChannelInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var list by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var wav by remember { mutableStateOf(false) }

    fun lookup() {
        if (q.isBlank() || loading) return
        loading = true; error = null; info = null
        scope.launch {
            try {
                val i = withContext(Dispatchers.IO) { Lookup.lookup(q) }
                info = i
                if (name.isBlank()) name = Lookup.suggestName(i)
            } catch (e: Exception) {
                error = e.message ?: "Lookup failed"
            } finally { loading = false }
        }
    }
    LaunchedEffect(Unit) { if (prefill.isNotBlank()) lookup() }

    NeuDialog(onClose) {
        Text("Add a channel", style = H2)
        Spacer(Modifier.height(18.dp))
        StepTitle(1, "Channel link")
        NeuField(q, { q = it }, "youtube.com/@handle, a video link or UC… ID", Modifier.fillMaxWidth(), onDone = { lookup() })
        Spacer(Modifier.height(12.dp))
        NeuButton(if (loading) "Looking up…" else "Look up", { lookup() }, Modifier.fillMaxWidth(), accent = true, enabled = !loading)

        info?.let { i ->
            Spacer(Modifier.height(22.dp))
            StepTitle(2, "Is this the right channel?")
            NeuWell(Modifier.fillMaxWidth()) {
                Row {
                    if (i.avatar.isNotEmpty()) {
                        Box(Modifier.size(64.dp).neuRaised(32.dp, 3.dp).padding(4.dp)) {
                            AsyncImage(i.avatar, null, contentScale = ContentScale.Crop,
                                modifier = Modifier.size(56.dp).clip(CircleShape))
                        }
                        Spacer(Modifier.width(14.dp))
                    }
                    Column {
                        Text(i.name.ifEmpty { "(name unknown)" }, style = H2.copy(fontSize = 18.sp))
                        Text(listOf(i.handle, i.id).filter { it.isNotEmpty() }.joinToString(" · "), style = Small)
                        i.videos.forEach { Text("• $it", style = Small, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
            val dupes = Store.channels.filter { it.id == i.id }
            val warns = buildList {
                if (dupes.isNotEmpty()) add("Already added as " + dupes.joinToString { "${it.name} (${it.list})" })
                if (!i.feedOk) add("Couldn't read this channel's feed - new videos may not be found.")
            }
            if (warns.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                NeuWell(Modifier.fillMaxWidth(), padding = 12.dp) { Text(warns.joinToString("\n"), style = Small.copy(color = Warn)) }
            }

            Spacer(Modifier.height(22.dp))
            StepTitle(3, "Where should it go?")
            ListPicker(list, { list = it }, lists)
            Spacer(Modifier.height(14.dp))
            Text("Name", style = Small)
            Spacer(Modifier.height(6.dp))
            NeuField(name, { name = it }, "Name", Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            NeuToggle(wav, { wav = it }, "Download as WAV", "Audio only. Off = video")
        }
        error?.let {
            Spacer(Modifier.height(12.dp))
            NeuWell(Modifier.fillMaxWidth(), padding = 12.dp) { Text(it, style = Small.copy(color = Bad)) }
        }
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            NeuButton("Cancel", onClose)
            NeuButton("Add channel", {
                val i = info ?: return@NeuButton
                val err = validateChannel(name.trim(), list.trim(), i.id)
                if (err != null) { error = err; return@NeuButton }
                try {
                    // reuse the existing spelling of a list ("music" vs "MUSIC")
                    val l = lists.firstOrNull { it.equals(list.trim(), true) } ?: list.trim()
                    Store.addChannel(Channel(name.trim(), i.id, l, wav))
                    Toast.makeText(ctx, "Added ${i.name.ifEmpty { name }} to $l as ${if (wav) "WAV" else "video"}", Toast.LENGTH_SHORT).show()
                    onClose()
                } catch (e: Exception) { error = e.message }
            }, accent = true, enabled = info != null)
        }
    }
}

@Composable
fun EditChannelDialog(c: Channel, lists: List<String>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(c.name) }
    var list by remember { mutableStateOf(c.list) }
    var id by remember { mutableStateOf(c.id) }
    var wav by remember { mutableStateOf(c.wav) }
    var error by remember { mutableStateOf<String?>(null) }

    NeuDialog(onClose) {
        Text("Edit ${c.name}", style = H2)
        Spacer(Modifier.height(18.dp))
        Text("Name", style = Small); Spacer(Modifier.height(6.dp))
        NeuField(name, { name = it }, "Name", Modifier.fillMaxWidth())
        Spacer(Modifier.height(14.dp))
        Text("List", style = Small); Spacer(Modifier.height(6.dp))
        ListPicker(list, { list = it }, lists)
        Spacer(Modifier.height(14.dp))
        Text("Channel ID", style = Small); Spacer(Modifier.height(6.dp))
        NeuField(id, { id = it }, "UC…", Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        NeuToggle(wav, { wav = it }, "Download as WAV", "Audio only. Off = video")
        error?.let {
            Spacer(Modifier.height(12.dp))
            NeuWell(Modifier.fillMaxWidth(), padding = 12.dp) { Text(it, style = Small.copy(color = Bad)) }
        }
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            NeuButton("Cancel", onClose)
            NeuButton("Save", {
                val err = validateChannel(name.trim(), list.trim(), id.trim())
                if (err != null) { error = err; return@NeuButton }
                try {
                    val l = lists.firstOrNull { it.equals(list.trim(), true) } ?: list.trim()
                    Store.updateChannel(c.name, Channel(name.trim(), id.trim(), l, wav))
                    Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show()
                    onClose()
                } catch (e: Exception) { error = e.message }
            }, accent = true)
        }
    }
}

