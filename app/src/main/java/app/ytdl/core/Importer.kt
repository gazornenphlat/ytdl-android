package app.ytdl.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Imports channels from a FlexGet config.yml (the PC setup) or from this app's own
 * backup file. Videos that already exist when a channel is imported are marked as
 * seen on the first check, so only new uploads get downloaded.
 */
object Importer {
    data class Result(val added: Int, val skipped: Int)

    private val taskRe = Regex("""^  ([^\s#][^:]*):\s*$""")
    private val cidRe = Regex("""channel_id=([0-9A-Za-z_-]+)""")
    private val execRe = Regex(""">>\s*"([^"]+)"""")
    private val echoRe = Regex("""\{\{\s*url\s*\}\}[ \t]+([A-Za-z0-9]+)[ \t]*>>""")

    /** Parse FlexGet tasks: name, channel id, list (from the exec path) and WAV flag. */
    fun parseFlexget(text: String): List<Channel> {
        val lines = text.lines()
        val out = mutableListOf<Channel>()
        var inTasks = false
        val starts = mutableListOf<Pair<Int, String?>>()
        lines.forEachIndexed { i, line ->
            if (Regex("""^tasks:\s*$""").matches(line)) { inTasks = true; return@forEachIndexed }
            if (inTasks && line.isNotEmpty() && !line.startsWith(" ") && !line.startsWith("#")) {
                starts += i to null; inTasks = false; return@forEachIndexed
            }
            if (inTasks) taskRe.find(line)?.let { starts += i to it.groupValues[1].trim() }
        }
        starts += lines.size to null
        for ((a, b) in starts.zipWithNext()) {
            val name = a.second ?: continue
            val block = lines.subList(a.first, b.first).joinToString("\n")
            val cid = cidRe.find(block)?.groupValues?.get(1) ?: continue
            if (!CHANNEL_ID_RE.matches(cid)) continue
            val path = execRe.find(block)?.groupValues?.get(1) ?: ""
            val list = path.substringAfterLast('/').substringAfterLast('\\').removeSuffix(".txt")
                .ifEmpty { "list" }
            val wav = echoRe.find(block)?.groupValues?.get(1)?.equals("wav", true) == true
            out += Channel(name.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.ifEmpty { "Channel" },
                cid, list, wav)
        }
        return out
    }

    fun importChannels(found: List<Channel>): Result {
        var added = 0; var skipped = 0
        // Merge lists that differ only in letter case (e.g. music / MUSIC)
        val listNames = LinkedHashMap<String, String>()
        Store.lists().forEach { listNames[it.lowercase()] = it }
        for (c in found) {
            val list = listNames.getOrPut(c.list.lowercase()) { c.list }
            if (Store.channels.any { it.id == c.id && it.list.equals(list, true) } ||
                Store.channels.any { it.name.equals(c.name, true) }) { skipped++; continue }
            Store.channels += c.copy(list = list)
            Store.state(c.name).primed = false
            added++
        }
        Store.save()
        Store.log("Imported $added channel(s), skipped $skipped already present")
        return Result(added, skipped)
    }

    /** This app's own backup: channels + list of downloaded ids. */
    fun exportBackup(): String = JSONObject()
        .put("app", "ytdl-android").put("version", 1)
        .put("channels", JSONArray().apply {
            Store.channels.forEach {
                put(JSONObject().put("name", it.name).put("id", it.id).put("list", it.list).put("wav", it.wav))
            }
        })
        .put("downloaded", JSONArray(Store.downloaded.toList()))
        .toString(1)

    fun parseBackup(text: String): Pair<List<Channel>, List<String>> {
        val o = JSONObject(text)
        val ch = o.getJSONArray("channels")
        val list = (0 until ch.length()).map { i ->
            val c = ch.getJSONObject(i)
            Channel(c.getString("name"), c.getString("id"), c.getString("list"), c.optBoolean("wav"))
        }
        val dl = o.optJSONArray("downloaded")
        val ids = if (dl == null) emptyList() else (0 until dl.length()).map { dl.getString(it) }
        return list to ids
    }
}
