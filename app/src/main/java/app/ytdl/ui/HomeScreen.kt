package app.ytdl.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ytdl.core.Store
import app.ytdl.core.now
import app.ytdl.work.CheckWorker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Standard page: big title, subtitle, scrolling content with room for soft shadows. */
@Composable
fun Page(title: String, sub: String?, header: (@Composable () -> Unit)? = null, content: LazyListScope.() -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column {
                Text(title, style = H1)
                if (sub != null) Text(sub, style = Small, modifier = Modifier.padding(top = 2.dp))
                if (header != null) { Spacer(Modifier.height(16.dp)); header() }
            }
        }
        content()
    }
}

fun ago(ts: Long): String {
    if (ts <= 0) return "never"
    val s = now() - ts
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86400 -> "${s / 3600} h ago"
        else -> "${s / 86400} d ago"
    }
}

fun clockTime(ts: Long): String = SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(ts * 1000))

@Composable
fun HomeScreen(ui: Ui, openLog: () -> Unit, goTo: (Screen) -> Unit) {
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val v = ui.version
    val paused = Store.S.paused
    val warn = Store.S.cookieProblem
    val busy = Store.busy
    val nextRun = Store.S.nextRun
    val lastCheck = Store.S.lastCheck
    val left = (nextRun - ui.clock).coerceAtLeast(0)
    val span = (nextRun - (if (lastCheck > 0) lastCheck else nextRun - 3600)).coerceAtLeast(60)
    val progress = if (nextRun > 0 && !paused) 1f - left.toFloat() / span else 0f

    Page("ytdl", if (lastCheck > 0) "Last check ${ago(lastCheck)}" else "Waiting for the first check") {
        item {
            NeuCard(Modifier.fillMaxWidth(), padding = 22.dp) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Dial(progress, busy, paused, warn) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val big = when {
                                busy -> "•••"
                                paused -> "II"
                                nextRun == 0L -> "--:--"
                                left >= 3600 -> "%d:%02d".format(left / 3600, (left % 3600) / 60)
                                else -> "%d:%02d".format(left / 60, left % 60)
                            }
                            Text(big, style = H1.copy(fontSize = 34.sp))
                            Text(
                                when {
                                    busy -> "WORKING"
                                    paused -> "PAUSED"
                                    left >= 3600 -> "H:MIN · ${Store.S.nextCount} CH"
                                    else -> "${Store.S.nextCount} CHANNEL${if (Store.S.nextCount == 1) "" else "S"}"
                                }, style = LabelStyle)
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    val statusColor = when { warn -> Warn; busy -> Ok; paused -> InkSoft; else -> Accent }
                    Text(
                        when {
                            warn -> "YouTube asked for a sign-in - retrying soon"
                            busy && Store.progress.isNotEmpty() -> Store.progress
                            else -> Store.status
                        },
                        style = Body.copy(color = statusColor, fontWeight = FontWeight.SemiBold),
                        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    if (!busy && nextRun > 0 && !paused)
                        Text("Next check around ${clockTime(nextRun)} (Android may run it a little later)",
                            style = Small, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        NeuButton("Run now", { CheckWorker.runNow(ctx) }, accent = true, enabled = !busy)
                        NeuToggle(paused, { Store.S.paused = it; Store.status = if (it) "Paused" else "Idle" },
                            "Paused", modifier = Modifier.width(130.dp))
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Stat("Waiting", "${Store.queue.size}", "to download", Modifier.weight(1f), accent = true) { goTo(Screen.FAILED) }
                Stat("Last run", "${Store.S.lastRunOk}",
                    if (Store.S.lastRunAt > 0) "downloaded · ${ago(Store.S.lastRunAt)}" else "downloaded", Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Stat("Gave up", "${Store.failed.size}", "in queue tab", Modifier.weight(1f), bad = Store.failed.isNotEmpty()) { goTo(Screen.FAILED) }
                Stat("Channels", "${Store.channels.size}", if (Store.S.smart) "smart schedule" else "every ${Store.S.intervalMin} min",
                    Modifier.weight(1f)) { goTo(Screen.CHANNELS) }
            }
        }
        val counts = Store.queue.groupingBy { it.list }.eachCount()
        val lists = Store.lists()
        if (lists.isNotEmpty()) item {
            NeuCard(Modifier.fillMaxWidth()) {
                Column {
                    Label("Lists")
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        lists.forEach { l ->
                            val n = counts[l] ?: 0
                            Pill("$l  $n", accent = n > 0, flat = n == 0)
                        }
                    }
                }
            }
        }
        item {
            NeuCard(Modifier.fillMaxWidth()) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Label("Recent activity", Modifier.weight(1f))
                        Text("Full log →", style = Small.copy(color = Accent),
                            modifier = Modifier.padding(4.dp).noRippleClick(openLog))
                    }
                    Spacer(Modifier.height(10.dp))
                    NeuWell(Modifier.fillMaxWidth()) {
                        val lines = Store.logTail(8).map { it.drop(6) }
                        Text(if (lines.isEmpty()) "Nothing yet" else lines.joinToString("\n"),
                            style = Small.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, sub: String, modifier: Modifier, accent: Boolean = false,
                 bad: Boolean = false, onClick: (() -> Unit)? = null) {
    NeuCard(modifier.then(if (onClick != null) Modifier.noRippleClick(onClick) else Modifier)) {
        Column {
            Label(label)
            Text(value, style = H1.copy(fontSize = 30.sp, color = when { bad -> Bad; accent -> Accent; else -> Ink }))
            Text(sub, style = Small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun Modifier.noRippleClick(onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    return this.clickable(interactionSource = src, indication = null, onClick = onClick)
}
