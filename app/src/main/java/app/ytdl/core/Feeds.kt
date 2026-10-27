package app.ytdl.core

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.time.OffsetDateTime

/** One video from a channel's RSS feed. */
data class FeedEntry(val videoId: String, val title: String, val published: Long)

data class Feed(val title: String, val entries: List<FeedEntry>)

/**
 * Reads a channel's YouTube RSS feed (the same feed FlexGet used on the PC).
 * It lists the ~15 most recent uploads with their publish times.
 */
object Feeds {
    fun fetch(channelId: String): Feed = parse(httpGet(FEED_URL + channelId))

    fun parse(xml: String): Feed {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(xml.reader())
        var title = ""
        val entries = mutableListOf<FeedEntry>()
        var inEntry = false
        var vid = ""; var etitle = ""; var pub = 0L
        var tag = ""
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    tag = p.name
                    if (tag == "entry") { inEntry = true; vid = ""; etitle = ""; pub = 0 }
                }
                XmlPullParser.TEXT -> {
                    val t = p.text?.trim().orEmpty()
                    if (t.isEmpty()) continue
                    if (inEntry) when (tag) {
                        "yt:videoId" -> vid = t
                        "title" -> etitle = t
                        "published" -> pub = parseTime(t)
                    } else if (tag == "title" && title.isEmpty()) title = t
                }
                XmlPullParser.END_TAG -> {
                    if (p.name == "entry") {
                        if (vid.isNotEmpty()) entries += FeedEntry(vid, etitle, pub)
                        inEntry = false
                    }
                    tag = ""
                }
            }
        }
        return Feed(title, entries)
    }

    private fun parseTime(s: String): Long = try {
        OffsetDateTime.parse(s).toEpochSecond()
    } catch (_: Exception) { 0L }
}
