package app.ytdl.core

import android.text.Html

data class ChannelInfo(
    val id: String,
    val name: String,
    val handle: String,
    val avatar: String,
    val videos: List<String>,
    val feedOk: Boolean,
)

/** Turns a channel link, @handle, video link or UC... id into channel details. */
object Lookup {
    private val patterns = listOf(
        Regex("""<meta itemprop="identifier" content="(UC[0-9A-Za-z_-]{22})""""),
        Regex(""""externalId":"(UC[0-9A-Za-z_-]{22})""""),
        Regex("""<link rel="canonical" href="https://www\.youtube\.com/channel/(UC[0-9A-Za-z_-]{22})""""),
        Regex(""""browseId":"(UC[0-9A-Za-z_-]{22})""""),
        Regex(""""channelId":"(UC[0-9A-Za-z_-]{22})""""),
    )

    fun normalize(input: String): String {
        var t = input.trim()
        // pull a URL out of shared text like "Check this out https://youtube.com/..."
        Regex("""https?://\S+""").find(t)?.let { t = it.value }
        if (CHANNEL_ID_RE.matches(t)) return "https://www.youtube.com/channel/$t"
        if (t.startsWith("@")) return "https://www.youtube.com/$t"
        if (!t.startsWith("http")) t = "https://$t"
        return t.replace("://m.youtube.com", "://www.youtube.com")
    }

    fun lookup(input: String): ChannelInfo {
        val url = normalize(input)
        var cid = Regex("""/channel/(UC[0-9A-Za-z_-]{22})""").find(url)?.groupValues?.get(1)
        var page = ""
        try {
            page = httpGet(url)
        } catch (e: Exception) {
            if (cid == null) throw RuntimeException("Couldn't open that page: ${e.message}")
        }
        if (cid == null) {
            for (re in patterns) {
                cid = re.find(page)?.groupValues?.get(1)
                if (cid != null) break
            }
        }
        if (cid == null) throw RuntimeException("Couldn't find a channel on that page.")

        val isVideo = "/watch" in url || "youtu.be/" in url || "/shorts/" in url
        fun meta(prop: String) = Regex("""<meta property="$prop" content="([^"]*)"""")
            .find(page)?.groupValues?.get(1)?.let { unescape(it) } ?: ""
        var name = if (isVideo) "" else meta("og:title")
        val avatar = if (isVideo) "" else meta("og:image")
        val handle = Regex(""""canonicalBaseUrl":"/(@[^"]+)"""").find(page)?.groupValues?.get(1) ?: ""

        var videos = emptyList<String>()
        var feedOk = false
        try {
            val feed = Feeds.fetch(cid)
            if (name.isEmpty()) name = feed.title
            videos = feed.entries.take(5).map { it.title }
            feedOk = true
        } catch (_: Exception) { }
        return ChannelInfo(cid, name, handle, avatar, videos, feedOk)
    }

    fun suggestName(info: ChannelInfo): String {
        val base = (info.name.ifEmpty { info.handle.removePrefix("@") })
            .replace(" ", "").filter { it.isLetterOrDigit() || it == '_' }.ifEmpty { "Channel" }
        var cand = base; var n = 2
        while (Store.channels.any { it.name.equals(cand, true) }) { cand = "$base$n"; n++ }
        return cand
    }

    private fun unescape(s: String): String =
        Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString()
}

/** Checks names typed by the user. Returns an error message or null. */
fun validateChannel(name: String, list: String, id: String): String? = when {
    name.isBlank() -> "Enter a name."
    !Regex("""[\p{L}\p{N}_\-]+""").matches(name) -> "Names can only contain letters, numbers, - and _."
    list.isBlank() -> "Choose or type a list name."
    Regex("""[\\/:*?"<>|]""").containsMatchIn(list) -> "List names can't contain \\ / : * ? \" < > |"
    !CHANNEL_ID_RE.matches(id) -> "The channel ID should start with UC and be 24 characters long."
    else -> null
}
