package app.ytdl.core

import java.net.HttpURLConnection
import java.net.URL

const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
const val FEED_URL = "https://www.youtube.com/feeds/videos.xml?channel_id="
val CHANNEL_ID_RE = Regex("UC[0-9A-Za-z_-]{22}")

/** Simple GET returning the body as text (throws on HTTP errors). */
fun httpGet(url: String, timeoutMs: Int = 20_000): String {
    val c = URL(url).openConnection() as HttpURLConnection
    try {
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        c.setRequestProperty("Cookie", "CONSENT=YES+1; SOCS=CAI")
        val code = c.responseCode
        if (code !in 200..299) throw RuntimeException("HTTP $code from ${URL(url).host}")
        return c.inputStream.bufferedReader().use { it.readText() }
    } finally {
        c.disconnect()
    }
}

fun httpBytes(url: String, timeoutMs: Int = 15_000): ByteArray {
    val c = URL(url).openConnection() as HttpURLConnection
    try {
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.setRequestProperty("User-Agent", UA)
        return c.inputStream.use { it.readBytes() }
    } finally {
        c.disconnect()
    }
}
