package app.ytdl.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import app.ytdl.core.Downloader
import app.ytdl.core.Importer
import app.ytdl.core.Store
import app.ytdl.work.CheckWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(ui: Ui, openLog: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val v = ui.version
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pickFolder = rememberFolderPicker()
    val importFlexget = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFrom(ctx, uri, flexget = true)
    }
    val importBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFrom(ctx, uri, flexget = false)
    }
    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) try {
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(Importer.exportBackup().toByteArray()) }
            toast(ctx, "Saved ${Store.channels.size} channels")
        } catch (e: Exception) { toast(ctx, "Couldn't save: ${e.message}") }
    }
    var ytdlpVersion by remember { mutableStateOf("…") }
    LaunchedEffect(Unit) { ytdlpVersion = withContext(Dispatchers.IO) { Downloader.version(ctx) } }

    Page("Settings", null) {
        // ---------------------------------------------------------- downloads
        item {
            Section("Downloads") {
                SettingRow("Download folder", folderName(ctx) ?: "Not chosen - files stay in the app's own folder") {
                    NeuButton("Choose", { pickFolder.launch(null) }, accent = true)
                }
                Divider()
                NeuToggle(Store.S.allowMobile, { Store.S.allowMobile = it; if (it) CheckWorker.runNow(ctx, false, true) },
                    "Download over mobile data",
                    if (Store.S.allowMobile) "On - downloads use mobile data when there's no Wi-Fi"
                    else "Off - videos wait in the queue until you're on Wi-Fi")
                NeuToggle(Store.S.chargingOnly, { Store.S.chargingOnly = it }, "Only while charging",
                    "Saves battery - downloads wait for the charger")
                Divider()
                Choice("Simultaneous downloads", listOf(1, 2, 3, 4), Store.S.parallel, { "$it" }) { Store.S.parallel = it }
                Choice("Video quality", listOf(480, 720, 1080, 0), Store.S.maxHeight, { if (it == 0) "Best" else "${it}p" }) {
                    Store.S.maxHeight = it
                }
                NeuToggle(Store.S.compatVideo, { Store.S.compatVideo = it }, "Most compatible video",
                    "Prefer H.264 so videos play on any device (sometimes lower max quality)")
                NeuToggle(Store.S.skipLive, { Store.S.skipLive = it }, "Skip live streams",
                    "Wait until a stream has ended, then download it")
                NeuToggle(Store.S.backlogOnAdd, { Store.S.backlogOnAdd = it }, "Download recent videos when adding a channel",
                    "Off = only videos uploaded after you add it")
                Choice("Give up after", listOf(1, 3, 5, 10), Store.S.maxFails, { "$it tries" }) { Store.S.maxFails = it }
            }
        }
        // ---------------------------------------------------------- checking
        item {
            Section("Checking for new videos") {
                NeuToggle(Store.S.smart, { Store.S.smart = it; CheckWorker.updateNext() }, "Smart schedule",
                    "Learn when each channel uploads and check around those times")
                Choice(if (Store.S.smart) "Channels with no pattern: every" else "Check every",
                    listOf(30, 60, 120, 240), Store.S.intervalMin, { if (it < 60) "$it min" else "${it / 60} h" }) {
                    Store.S.intervalMin = it; CheckWorker.updateNext()
                }
                Text("Android wakes the app about every 15 minutes at most, and may delay it when saving battery.", style = Small)
            }
        }
        // ---------------------------------------------------------- background
        item {
            Section("Running in the background") {
                val exempt = isBatteryExempt(ctx)
                SettingRow("Battery optimisation",
                    if (exempt) "ytdl is allowed to run in the background ✓"
                    else "Recommended: let ytdl run in the background so checks aren't skipped") {
                    if (!exempt) NeuButton("Allow", { requestBatteryExempt(ctx) }, accent = true)
                }
                Text("Some phones (Samsung, Xiaomi, OnePlus) also need ytdl set to “Unrestricted” in the app's battery settings.",
                    style = Small)
            }
        }
        // ---------------------------------------------------------- youtube
        item {
            Section("YouTube sign-in") {
                val signedIn = Store.cookiesFile.exists()
                SettingRow(if (signedIn) "Signed in" else "Not signed in",
                    if (signedIn) "Used only if YouTube asks to confirm you're not a bot"
                    else "Only needed if downloads start failing with “sign in to confirm”") {
                    NeuButton(if (signedIn) "Sign in again" else "Sign in", {
                        ctx.startActivity(Intent(ctx, LoginActivity::class.java))
                    }, accent = true)
                }
                if (signedIn) NeuButton("Sign out", {
                    Store.cookiesFile.delete(); android.webkit.CookieManager.getInstance().removeAllCookies(null)
                    Store.bump(); toast(ctx, "Signed out")
                }, danger = true)
            }
        }
        // ---------------------------------------------------------- channels
        item {
            Section("Channels") {
                SettingRow("Import from the PC", "Pick your FlexGet config.yml - channels, lists and WAV settings come across") {
                    NeuButton("Import", { importFlexget.launch(arrayOf("*/*")) }, accent = true)
                }
                Divider()
                SettingRow("Back up channels", "Save your channel list to a file") {
                    NeuButton("Save", { exportBackup.launch("ytdl-channels.json") })
                }
                SettingRow("Restore a backup", "Load a file saved with “Back up channels”") {
                    NeuButton("Load", { importBackup.launch(arrayOf("*/*")) })
                }
            }
        }
        // ---------------------------------------------------------- about
        item {
            Section("About") {
                SettingRow("yt-dlp", "Version $ytdlpVersion · updates itself daily") {
                    NeuButton("Update now", {
                        Store.S.lastUpdateDay = ""
                        scope.launch {
                            toast(ctx, "Updating yt-dlp…")
                            withContext(Dispatchers.IO) { Downloader.dailyUpdate(ctx) }
                            ytdlpVersion = withContext(Dispatchers.IO) { Downloader.version(ctx) }
                            toast(ctx, "yt-dlp $ytdlpVersion")
                        }
                    })
                }
                SettingRow("Log", "Everything the app has done") { NeuButton("Open", openLog) }
                Text("${Store.S.totalDownloaded} videos downloaded so far · app ${appVersion(ctx)}", style = Small)
            }
        }
    }
}

// ------------------------------------------------------------------ first-run setup
@Composable
fun SetupScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val pickFolder = rememberFolderPicker()
    val importFlexget = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFrom(ctx, uri, flexget = true)
    }
    val version by Store.changes.collectAsState()
    var tick by remember { mutableStateOf(0) }   // re-check battery status after returning from settings
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1500); tick++ } }
    @Suppress("UNUSED_VARIABLE") val refresh = version + tick

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Welcome to ytdl", style = H1)
        Text("Downloads new videos from your YouTube channels automatically - right on this phone.", style = Small)
        run {
            Section("1 · Where should videos go?") {
                Text(folderName(ctx)?.let { "Saving to: $it" } ?: "Pick a folder, e.g. Movies or a folder on your SD card. Each list gets its own subfolder.",
                    style = Small)
                NeuButton(if (Store.S.treeUri == null) "Choose folder" else "Change folder", { pickFolder.launch(null) }, accent = true)
            }
            Section("2 · Mobile data") {
                NeuToggle(Store.S.allowMobile, { Store.S.allowMobile = it }, "Download over mobile data",
                    if (Store.S.allowMobile) "On - downloads use mobile data when there's no Wi-Fi"
                    else "Off - downloads wait for Wi-Fi")
            }
            Section("3 · Bring your channels over (optional)") {
                Text("Copy the FlexGet config.yml from your PC to this phone, then pick it here. You can also add channels one by one later.",
                    style = Small)
                NeuButton("Import config.yml", { importFlexget.launch(arrayOf("*/*")) })
                if (Store.channels.isNotEmpty()) Text("${Store.channels.size} channels ready ✓", style = Small.copy(color = Ok))
            }
            Section("4 · Let it run in the background") {
                Text("So Android doesn't stop the checks to save battery.", style = Small)
                if (isBatteryExempt(ctx)) Text("Allowed ✓", style = Small.copy(color = Ok))
                else NeuButton("Allow", { requestBatteryExempt(ctx) })
            }
        }
        NeuButton("Start", {
            onDone()
            if (Store.channels.isNotEmpty()) CheckWorker.runNow(ctx)
        }, Modifier.fillMaxWidth(), accent = true)
    }
}

// ------------------------------------------------------------------ pieces
@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    NeuCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Label(title)
            content()
        }
    }
}

@Composable
private fun SettingRow(title: String, sub: String, action: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = Body)
            Text(sub, style = Small)
        }
        action()
    }
}

@Composable
private fun <T> Choice(title: String, options: List<T>, value: T, label: (T) -> String, onPick: (T) -> Unit) {
    Column {
        Text(title, style = Body)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            options.forEach { o ->
                Box(Modifier.noRippleClick { onPick(o) }) { Pill(label(o), accent = o == value, flat = o != value) }
            }
        }
    }
}

@Composable private fun Divider() = Spacer(Modifier.height(2.dp))

@Composable
fun rememberFolderPicker(): ManagedActivityResultLauncher<Uri?, Uri?> {
    val ctx = LocalContext.current
    return rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            Store.S.treeUri = uri.toString()
            toast(ctx, "Videos will be saved to ${folderName(ctx)}")
        }
    }
}

fun folderName(ctx: Context): String? = Store.S.treeUri?.let { u ->
    try { DocumentFile.fromTreeUri(ctx, Uri.parse(u))?.name ?: Uri.parse(u).lastPathSegment } catch (_: Exception) { null }
}

private fun importFrom(ctx: Context, uri: Uri, flexget: Boolean) {
    try {
        val text = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        val r = if (flexget) {
            val found = Importer.parseFlexget(text)
            if (found.isEmpty()) { toast(ctx, "No YouTube channels found in that file"); return }
            Importer.importChannels(found)
        } else {
            val (found, ids) = Importer.parseBackup(text)
            Store.downloaded.addAll(ids)
            Importer.importChannels(found)
        }
        toast(ctx, "Added ${r.added} channels" + if (r.skipped > 0) " (${r.skipped} were already there)" else "")
    } catch (e: Exception) {
        toast(ctx, "Couldn't import: ${e.message}")
    }
}

fun isBatteryExempt(ctx: Context): Boolean =
    ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

@SuppressLint("BatteryLife")
fun requestBatteryExempt(ctx: Context) {
    try {
        ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
    } catch (_: Exception) {
        ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

fun appVersion(ctx: Context): String = try {
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
} catch (_: Exception) { "?" }

fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

