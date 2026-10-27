package app.ytdl.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ytdl.core.QueueItem
import app.ytdl.core.Scheduler
import app.ytdl.core.Store
import app.ytdl.core.now
import app.ytdl.work.CheckWorker

// ================================================================== Schedule
@Composable
fun ScheduleScreen(ui: Ui) {
    @Suppress("UNUSED_VARIABLE") val v = ui.version
    var query by remember { mutableStateOf("") }
    var onlyOpen by remember { mutableStateOf(false) }
    val ts = ui.clock - ui.clock % 60   // recompute once a minute
    val rows = remember(ui.version, ts) {
        Store.channels.toList().sortedBy { it.name.lowercase() }.map { c ->
            val due = Scheduler.isDue(c, ts)
            Triple(c, Scheduler.inWindow(c.name, ts), due)
        }
    }
    val open = rows.count { it.second }
    val shown = rows.filter { (!onlyOpen || it.second) && (query.isBlank() || it.first.name.contains(query, true)) }

    Page("Schedule",
        "${rows.size} channels · $open in an upload window now" +
            if (Store.S.nextRun > 0) " · next check ~${clockTime(Store.S.nextRun)}" else "",
        header = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (Store.S.smart) "ytdl learns when each channel usually uploads and checks it around those times. " +
                    "Every channel is still checked at least once a day."
                    else "Smart schedule is off: every channel is checked every ${Store.S.intervalMin} minutes.", style = Small)
                NeuField(query, { query = it }, "Search…", Modifier.fillMaxWidth())
                NeuToggle(onlyOpen, { onlyOpen = it }, "Upload window open now")
            }
        }) {
        items(shown, key = { it.first.name }) { (c, inWin, due) ->
            val st = Store.state(c.name)
            Box(Modifier.fillMaxWidth().neuRaised(16.dp, 5.dp).padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name, style = Body.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(Scheduler.describe(c), style = Small)
                        Text("${st.uploads.size} uploads seen · checked ${ago(st.lastChecked)}", style = Small.copy(fontSize = 12.sp))
                    }
                    Spacer(Modifier.width(8.dp))
                    when {
                        inWin -> Pill("● window open", color = Ok)
                        due.first -> Pill("● due", accent = true)
                        else -> Pill("waiting", flat = true)
                    }
                }
            }
        }
    }
}

// ================================================================== Queue (waiting + failed)
@Composable
fun FailedScreen(ui: Ui) {
    @Suppress("UNUSED_VARIABLE") val v = ui.version
    val ctx = LocalContext.current
    val sel = remember { mutableStateListOf<String>() }
    val block = remember(ui.version, ui.clock / 10) { CheckWorker.whyNotDownload(ctx) }

    Page("Queue", "${Store.queue.size} waiting · ${Store.failed.size} gave up") {
        // ---- waiting
        item {
            NeuCard(Modifier.fillMaxWidth()) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Label("Waiting to download", Modifier.weight(1f))
                        if (Store.queue.isNotEmpty())
                            NeuButton("Download now",
                                {
                                    if (block != null) Toast.makeText(ctx, "$block - change this in Settings", Toast.LENGTH_LONG).show()
                                    else CheckWorker.runNow(ctx, all = false, downloadOnly = true)
                                }, accent = true, enabled = !Store.busy)
                    }
                    if (block != null && Store.queue.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("$block. You can change this in Settings.", style = Small.copy(color = Warn))
                    }
                    Spacer(Modifier.height(10.dp))
                    if (Store.queue.isEmpty()) Text("Nothing waiting.", style = Small)
                    Store.queue.toList().take(100).forEach { q -> QueueRow(q) }
                    if (Store.queue.size > 100) Text("…and ${Store.queue.size - 100} more", style = Small)
                }
            }
        }
        // ---- failed
        item {
            Column(Modifier.padding(top = 8.dp)) {
                Label("Gave up after ${Store.S.maxFails} tries")
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    NeuToggle(Store.failed.isNotEmpty() && sel.size == Store.failed.size, {
                        sel.clear(); if (it) sel.addAll(Store.failed.map { f -> f.videoId })
                    }, "Select all", modifier = Modifier.weight(1f))
                    NeuButton("Remove", {
                        Store.failed.removeAll { it.videoId in sel }; sel.clear(); Store.save()
                    }, danger = true, enabled = sel.isNotEmpty())
                    NeuButton("Retry", {
                        val back = Store.failed.filter { it.videoId in sel }
                        back.forEach { f -> Store.queue += QueueItem(f.videoId, f.title, f.channel, f.list, f.wav, 0) }
                        Store.failed.removeAll { it.videoId in sel }; sel.clear(); Store.save()
                        Toast.makeText(ctx, "Put ${back.size} back in the queue", Toast.LENGTH_SHORT).show()
                    }, accent = true, enabled = sel.isNotEmpty())
                }
            }
        }
        if (Store.failed.isEmpty()) item {
            NeuCard(Modifier.fillMaxWidth()) { Text("Nothing has failed. 🎉", style = Small) }
        }
        items(Store.failed.toList().sortedByDescending { it.failedAt }, key = { "f" + it.videoId }) { f ->
            val s = f.videoId in sel
            Box(
                Modifier.fillMaxWidth()
                    .then(if (s) Modifier.neuInset(16.dp, 4.dp) else Modifier.neuRaised(16.dp, 5.dp))
                    .noRippleClick { if (s) sel.remove(f.videoId) else sel.add(f.videoId) }
                    .padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    NeuCheck(s)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.title.ifEmpty { f.videoId }, style = Body.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${f.channel} · ${f.list} · ${ago(f.failedAt)}", style = Small)
                        Text(f.error, style = Small.copy(color = Bad, fontSize = 12.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text("Open on YouTube", style = Small.copy(color = Accent), modifier = Modifier.padding(top = 4.dp).noRippleClick {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(f.url)))
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueRow(q: QueueItem) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(q.title.ifEmpty { q.videoId }, style = Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${q.channel} · ${q.list}" + (if (q.fails > 0) " · failed ${q.fails}×" else ""), style = Small, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        if (q.wav) Pill("WAV", accent = true) else Pill("Video", flat = true)
    }
}

// ================================================================== Log
@Composable
fun LogScreen(ui: Ui, onBack: () -> Unit) {
    var filter by remember { mutableStateOf("") }
    val lines = remember(ui.version, ui.clock / 3) { Store.logTail(600) }
    val shown = if (filter.isBlank()) lines else lines.filter { it.contains(filter, true) }
    val state = rememberLazyListState()
    LaunchedEffect(shown.size) { if (shown.isNotEmpty()) state.scrollToItem(shown.size - 1) }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack)
            Spacer(Modifier.width(14.dp))
            Text("Log", style = H1)
        }
        Spacer(Modifier.height(14.dp))
        NeuField(filter, { filter = it }, "Filter…", Modifier.fillMaxWidth())
        Spacer(Modifier.height(14.dp))
        Box(Modifier.weight(1f).fillMaxWidth().neuInset(16.dp).padding(12.dp)) {
            LazyColumn(state = state) {
                items(shown) { l ->
                    val color = when {
                        Regex("error|failed|gave up|couldn't|sign-in", RegexOption.IGNORE_CASE).containsMatchIn(l) -> Bad
                        Regex("downloaded|added|found", RegexOption.IGNORE_CASE).containsMatchIn(l) -> Accent
                        else -> InkSoft
                    }
                    Text(l, style = Small.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp, color = color))
                }
            }
        }
    }
}
