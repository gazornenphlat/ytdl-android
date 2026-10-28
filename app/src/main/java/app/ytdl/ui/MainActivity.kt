package app.ytdl.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.ytdl.core.Store
import app.ytdl.core.now
import kotlinx.coroutines.delay

enum class Screen(val title: String, val icon: ImageVector) {
    HOME("Home", Icons.Outlined.Home),
    CHANNELS("Channels", Icons.Outlined.Subscriptions),
    SCHEDULE("Schedule", Icons.Outlined.Schedule),
    FAILED("Queue", Icons.Outlined.Download),
    SETTINGS("Settings", Icons.Outlined.Settings),
}

class MainActivity : ComponentActivity() {
    private val sharedText = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Store.init(this)
        handleShare(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent { YtdlTheme { App(sharedText.value) { sharedText.value = null } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Someone shared a YouTube link to the app: open "Add channel" with it. */
    private fun handleShare(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) i.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedText.value = it }
    }
}

/** Lets every screen re-read Store when anything changes, plus a 1-second clock. */
class Ui(val version: Long, val clock: Long)

@Composable
fun App(shared: String?, consumeShared: () -> Unit) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var showLog by rememberSaveable { mutableStateOf(false) }
    var setup by rememberSaveable { mutableStateOf(!Store.S.setupDone) }
    val version by Store.changes.collectAsState()
    var clock by remember { mutableLongStateOf(now()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); clock = now() } }
    // Only the Home dial needs the 1-second clock; other screens refresh when data changes
    val ui = Ui(version, if (!setup && !showLog && screen == Screen.HOME) clock else 0L)

    LaunchedEffect(shared) { if (shared != null) screen = Screen.CHANNELS }
    BackHandler(enabled = showLog || screen != Screen.HOME) {
        if (showLog) showLog = false else screen = Screen.HOME
    }

    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                setup -> SetupScreen { setup = false; Store.S.setupDone = true }
                showLog -> LogScreen(ui) { showLog = false }
                else -> when (screen) {
                    Screen.HOME -> HomeScreen(ui, openLog = { showLog = true }, goTo = { screen = it })
                    Screen.CHANNELS -> ChannelsScreen(ui, shared, consumeShared)
                    Screen.SCHEDULE -> ScheduleScreen(ui)
                    Screen.FAILED -> FailedScreen(ui)
                    Screen.SETTINGS -> SettingsScreen(ui, openLog = { showLog = true })
                }
            }
        }
        if (!setup) BottomBar(screen, Store.failed.size) { screen = it; showLog = false }
    }
}

@Composable
private fun BottomBar(current: Screen, failed: Int, onPick: (Screen) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Screen.entries.forEach { s ->
            val active = s == current
            Column(
                Modifier
                    .then(if (active) Modifier.neuInset(14.dp, 3.dp) else Modifier)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onPick(s) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box {
                    Icon(s.icon, s.title, tint = if (active) Accent else InkSoft)
                    if (s == Screen.FAILED && failed > 0)
                        Text("$failed", style = Small.copy(fontSize = 9.sp, color = Bad, fontWeight = FontWeight.Bold),
                            modifier = Modifier.align(Alignment.TopEnd).padding(start = 18.dp))
                }
                Text(s.title, style = Small.copy(fontSize = 11.sp, color = if (active) Accent else InkSoft,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal))
            }
        }
    }
}
