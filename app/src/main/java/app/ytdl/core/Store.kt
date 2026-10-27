package app.ytdl.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A YouTube channel the app watches. */
data class Channel(
    val name: String,          // display / task name
    val id: String,            // UC... channel id
    val list: String,          // list = output folder name
    val wav: Boolean = false,  // download as WAV instead of video
    val addedAt: Long = now(),
)

/** What the app has learned about a channel (upload times, seen videos, last check). */
class ChannelState(
    val uploads: MutableList<Long> = mutableListOf(),     // publish times, epoch seconds
    val seen: MutableList<String> = mutableListOf(),      // video ids already handled
    var lastChecked: Long = 0,
    var primed: Boolean = false,                          // first check done (backlog marked seen)
    var title: String = "",
)

/** A video waiting to be downloaded. */
data class QueueItem(
    val videoId: String,
    val title: String,
    val channel: String,
    val list: String,
    val wav: Boolean,
    val published: Long,
    var fails: Int = 0,
    var lastError: String = "",
) {
    val url get() = "https://www.youtube.com/watch?v=$videoId"
}

/** A video that failed too many times. */
data class FailedItem(
    val videoId: String,
    val title: String,
    val channel: String,
    val list: String,
    val wav: Boolean,
    val failedAt: Long,
    val error: String,
) {
    val url get() = "https://www.youtube.com/watch?v=$videoId"
}

fun now(): Long = System.currentTimeMillis() / 1000

/**
 * All app data. Small JSON files in the app's private storage, settings in
 * SharedPreferences. Everything goes through this object so screens can watch [changes].
 */
object Store {
    private lateinit var dir: File
    private lateinit var prefs: SharedPreferences
    private val lock = Any()

    val channels = mutableListOf<Channel>()
    val states = mutableMapOf<String, ChannelState>()   // key: channel name
    val queue = mutableListOf<QueueItem>()
    val failed = mutableListOf<FailedItem>()
    val downloaded = LinkedHashSet<String>()            // video ids done (archive)

    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes

    /** Live status shown on the home screen. */
    @Volatile var status: String = "Idle"
    @Volatile var busy: Boolean = false
    @Volatile var progress: String = ""

    fun init(ctx: Context) {
        if (::dir.isInitialized) return
        dir = ctx.filesDir
        prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        load()
    }

    fun bump() { _changes.value = _changes.value + 1 }

    val cookiesFile: File get() = File(dir, "cookies.txt")
    val logFile: File get() = File(dir, "log.txt")

    // ------------------------------------------------------------- settings
    object S {
        var treeUri: String? get() = prefs.getString("treeUri", null); set(v) = put("treeUri", v)
        var allowMobile: Boolean get() = prefs.getBoolean("allowMobile", false); set(v) = put("allowMobile", v)
        var chargingOnly: Boolean get() = prefs.getBoolean("chargingOnly", false); set(v) = put("chargingOnly", v)
        var parallel: Int get() = prefs.getInt("parallel", 2); set(v) = put("parallel", v)
        var maxHeight: Int get() = prefs.getInt("maxHeight", 1080); set(v) = put("maxHeight", v)
        var compatVideo: Boolean get() = prefs.getBoolean("compatVideo", true); set(v) = put("compatVideo", v)
        var skipLive: Boolean get() = prefs.getBoolean("skipLive", true); set(v) = put("skipLive", v)
        var smart: Boolean get() = prefs.getBoolean("smart", true); set(v) = put("smart", v)
        var intervalMin: Int get() = prefs.getInt("intervalMin", 120); set(v) = put("intervalMin", v)
        var maxFails: Int get() = prefs.getInt("maxFails", 3); set(v) = put("maxFails", v)
        var backlogOnAdd: Boolean get() = prefs.getBoolean("backlogOnAdd", false); set(v) = put("backlogOnAdd", v)
        var paused: Boolean get() = prefs.getBoolean("paused", false); set(v) = put("paused", v)
        var cookieProblem: Boolean get() = prefs.getBoolean("cookieProblem", false); set(v) = put("cookieProblem", v)
        var lastUpdateDay: String get() = prefs.getString("lastUpdateDay", "") ?: ""; set(v) = put("lastUpdateDay", v)
        var lastCheck: Long get() = prefs.getLong("lastCheck", 0); set(v) = put("lastCheck", v)
        var nextRun: Long get() = prefs.getLong("nextRun", 0); set(v) = put("nextRun", v)
        var nextCount: Int get() = prefs.getInt("nextCount", 0); set(v) = put("nextCount", v)
        var lastRunOk: Int get() = prefs.getInt("lastRunOk", 0); set(v) = put("lastRunOk", v)
        var lastRunAt: Long get() = prefs.getLong("lastRunAt", 0); set(v) = put("lastRunAt", v)
        var totalDownloaded: Int get() = prefs.getInt("totalDownloaded", 0); set(v) = put("totalDownloaded", v)
        var setupDone: Boolean get() = prefs.getBoolean("setupDone", false); set(v) = put("setupDone", v)

        private fun put(k: String, v: Any?) {
            prefs.edit().apply {
                when (v) {
                    null -> remove(k)
                    is String -> putString(k, v)
                    is Boolean -> putBoolean(k, v)
                    is Int -> putInt(k, v)
                    is Long -> putLong(k, v)
                }
            }.apply()
            bump()
        }
    }

    // ------------------------------------------------------------- persistence
    private fun read(name: String): String? =
        File(dir, name).takeIf { it.exists() }?.readText()

    private fun write(name: String, text: String) {
        val f = File(dir, name)
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    private fun load() = synchronized(lock) {
        channels.clear(); states.clear(); queue.clear(); failed.clear(); downloaded.clear()
        read("channels.json")?.let { JSONArray(it) }?.forEachObj {
            channels += Channel(it.getString("name"), it.getString("id"), it.getString("list"),
                it.optBoolean("wav"), it.optLong("addedAt", now()))
        }
        read("state.json")?.let { JSONObject(it) }?.let { o ->
            o.keys().forEach { k ->
                val s = o.getJSONObject(k)
                states[k] = ChannelState(
                    s.optJSONArray("uploads").longs(), s.optJSONArray("seen").strings(),
                    s.optLong("lastChecked"), s.optBoolean("primed"), s.optString("title"))
            }
        }
        read("queue.json")?.let { JSONArray(it) }?.forEachObj {
            queue += QueueItem(it.getString("videoId"), it.optString("title"), it.optString("channel"),
                it.getString("list"), it.optBoolean("wav"), it.optLong("published"),
                it.optInt("fails"), it.optString("lastError"))
        }
        read("failed.json")?.let { JSONArray(it) }?.forEachObj {
            failed += FailedItem(it.getString("videoId"), it.optString("title"), it.optString("channel"),
                it.getString("list"), it.optBoolean("wav"), it.optLong("failedAt"), it.optString("error"))
        }
        read("downloaded.json")?.let { JSONArray(it) }?.strings()?.let { downloaded.addAll(it) }
    }

    fun save() = synchronized(lock) {
        write("channels.json", JSONArray().apply {
            channels.forEach { put(JSONObject().put("name", it.name).put("id", it.id)
                .put("list", it.list).put("wav", it.wav).put("addedAt", it.addedAt)) }
        }.toString(1))
        write("state.json", JSONObject().apply {
            states.forEach { (k, s) ->
                put(k, JSONObject().put("uploads", JSONArray(s.uploads.takeLast(60)))
                    .put("seen", JSONArray(s.seen.takeLast(300)))
                    .put("lastChecked", s.lastChecked).put("primed", s.primed).put("title", s.title))
            }
        }.toString())
        write("queue.json", JSONArray().apply {
            queue.forEach { put(JSONObject().put("videoId", it.videoId).put("title", it.title)
                .put("channel", it.channel).put("list", it.list).put("wav", it.wav)
                .put("published", it.published).put("fails", it.fails).put("lastError", it.lastError)) }
        }.toString(1))
        write("failed.json", JSONArray().apply {
            failed.forEach { put(JSONObject().put("videoId", it.videoId).put("title", it.title)
                .put("channel", it.channel).put("list", it.list).put("wav", it.wav)
                .put("failedAt", it.failedAt).put("error", it.error)) }
        }.toString(1))
        write("downloaded.json", JSONArray(downloaded.toList().takeLast(20000)).toString())
        bump()
    }

    fun state(name: String): ChannelState = synchronized(lock) { states.getOrPut(name) { ChannelState() } }

    // ------------------------------------------------------------- channel edits
    fun lists(): List<String> = synchronized(lock) {
        val seen = LinkedHashMap<String, String>()
        channels.forEach { seen.putIfAbsent(it.list.lowercase(), it.list) }
        queue.forEach { seen.putIfAbsent(it.list.lowercase(), it.list) }
        seen.values.sortedBy { it.lowercase() }
    }

    fun addChannel(c: Channel) {
        synchronized(lock) {
            require(channels.none { it.name.equals(c.name, true) }) { "A channel named '${c.name}' already exists." }
            channels += c
            // Unless the user wants the backlog, the first check only marks existing videos as seen.
            state(c.name).primed = false
        }
        save()
        log("Added channel ${c.name} -> ${c.list}${if (c.wav) " (WAV)" else ""}")
    }

    fun updateChannel(old: String, c: Channel) {
        synchronized(lock) {
            val i = channels.indexOfFirst { it.name == old }
            require(i >= 0) { "'$old' no longer exists." }
            if (!old.equals(c.name, true))
                require(channels.none { it.name.equals(c.name, true) }) { "A channel named '${c.name}' already exists." }
            channels[i] = c.copy(addedAt = channels[i].addedAt)
            if (old != c.name) states.remove(old)?.let { states[c.name] = it }
        }
        save()
        log("Edited channel $old")
    }

    fun deleteChannels(names: Set<String>) {
        synchronized(lock) {
            channels.removeAll { it.name in names }
            names.forEach { states.remove(it) }
        }
        save()
        log("Deleted ${names.size} channel(s)")
    }

    // ------------------------------------------------------------- log
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun log(msg: String) {
        try {
            val f = logFile
            if (f.exists() && f.length() > 1_000_000) {
                val keep = f.readText().takeLast(400_000)
                f.writeText(keep.substringAfter('\n'))
            }
            f.appendText("${fmt.format(Date())}  $msg\n")
        } catch (_: Exception) { }
        bump()
    }

    fun logTail(lines: Int): List<String> = try {
        logFile.readLines().takeLast(lines)
    } catch (_: Exception) { emptyList() }
}

// ------------------------------------------------------------- JSON helpers
private inline fun JSONArray.forEachObj(f: (JSONObject) -> Unit) {
    for (i in 0 until length()) f(getJSONObject(i))
}
private fun JSONArray?.longs(): MutableList<Long> =
    if (this == null) mutableListOf() else MutableList(length()) { getLong(it) }
private fun JSONArray?.strings(): MutableList<String> =
    if (this == null) mutableListOf() else MutableList(length()) { getString(it) }
