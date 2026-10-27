package app.ytdl.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.ytdl.core.Store

/**
 * Opens YouTube's normal sign-in page. When you tap "Done", the YouTube cookies are
 * saved in the Netscape format yt-dlp reads (--cookies). They never leave the phone.
 */
class LoginActivity : ComponentActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Store.init(this)
        CookieManager.getInstance().setAcceptCookie(true)
        setContent {
            YtdlTheme {
                Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Sign in to YouTube", style = H2)
                            Text("Sign in, then tap Done", style = Small)
                        }
                        NeuButton("Cancel", { finish() })
                        Spacer(Modifier.width(10.dp))
                        NeuButton("Done", { save() }, accent = true)
                    }
                    AndroidView(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        factory = { c ->
                            WebView(c).apply {
                                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                webViewClient = WebViewClient()
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fm.youtube.com%2F")
                            }
                        },
                    )
                }
            }
        }
    }

    private fun save() {
        val cm = CookieManager.getInstance()
        cm.flush()
        val lines = mutableListOf("# Netscape HTTP Cookie File", "# Saved by ytdl")
        val expiry = System.currentTimeMillis() / 1000 + 180L * 24 * 3600
        for ((url, domain) in listOf("https://www.youtube.com" to ".youtube.com", "https://accounts.google.com" to ".google.com")) {
            cm.getCookie(url)?.split(";")?.map { it.trim() }?.filter { '=' in it }?.forEach { kv ->
                val name = kv.substringBefore('=')
                val value = kv.substringAfter('=')
                lines += listOf(domain, "TRUE", "/", "TRUE", expiry.toString(), name, value).joinToString("\t")
            }
        }
        if (lines.size <= 2 || lines.none { it.contains("\tSID\t") || it.contains("LOGIN_INFO") || it.contains("SAPISID") }) {
            toast(this, "You don't seem to be signed in yet")
            return
        }
        Store.cookiesFile.writeText(lines.joinToString("\n") + "\n")
        Store.S.cookieProblem = false
        Store.log("YouTube sign-in saved")
        toast(this, "Signed in - downloads will use this")
        finish()
    }
}
