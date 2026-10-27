package app.ytdl.core

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.time.LocalDate

/** Runs yt-dlp (bundled in the app) for one video and moves the result to the chosen folder. */
object Downloader {
    enum class Outcome { OK, FAILED, LIVE, COOKIES }
    data class Result(val outcome: Outcome, val message: String = "", val savedAs: String = "")

    @Volatile private var ready = false
    private val COOKIE_ERRORS = listOf("sign in to confirm", "cookies are no longer valid",
        "confirm you're not a bot", "confirm you’re not a bot")

    /** Unpacks Python, yt-dlp and ffmpeg on first use (takes a few seconds the first time). */
    @Synchronized
    fun init(ctx: Context): Boolean {
        if (ready) return true
        return try {
            YoutubeDL.getInstance().init(ctx.applicationContext)
            FFmpeg.getInstance().init(ctx.applicationContext)
            ready = true
            true
        } catch (e: Exception) {
            Store.log("Couldn't start yt-dlp: ${e.message}")
            false
        }
    }

    /** Updates yt-dlp at most once a day. */
    fun dailyUpdate(ctx: Context) {
        val today = LocalDate.now().toString()
        if (Store.S.lastUpdateDay == today || !init(ctx)) return
        try {
            val status = YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext, YoutubeDL.UpdateChannel._STABLE)
            Store.log("yt-dlp update: $status (version ${YoutubeDL.getInstance().version(ctx.applicationContext)})")
        } catch (e: Exception) {
            Store.log("yt-dlp update failed: ${e.message}")
        }
        Store.S.lastUpdateDay = today
    }

    fun version(ctx: Context): String = try {
        if (init(ctx)) YoutubeDL.getInstance().version(ctx.applicationContext) ?: "?" else "not ready"
    } catch (_: Exception) { "?" }

    fun download(ctx: Context, item: QueueItem, onProgress: (String) -> Unit): Result {
        if (!init(ctx)) return Result(Outcome.FAILED, "yt-dlp could not start")
        val work = File(ctx.cacheDir, "dl/${item.videoId}").apply { deleteRecursively(); mkdirs() }
        val req = YoutubeDLRequest(item.url).apply {
            if (item.wav) {
                addOption("-f", "bestaudio/best")
                addOption("-x")
                addOption("--audio-format", "wav")
            } else {
                val h = Store.S.maxHeight
                val cap = if (h > 0) "[height<=$h]" else ""
                addOption("-f", "bv*$cap+ba/b$cap/b")
                addOption("--merge-output-format", "mp4")
                if (Store.S.compatVideo) addOption("-S", "vcodec:h264,res,acodec:m4a")
            }
            addOption("-o", File(work, "%(channel)s - %(title)s.%(ext)s").absolutePath)
            addOption("--windows-filenames")      // safe names for SD cards
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("--newline")
            addOption("--extractor-retries", "5")
            addOption("--retry-sleep", "extractor:3")
            if (Store.S.skipLive)
                addOption("--match-filter", "!is_live & live_status !=? is_upcoming & live_status !=? post_live")
            if (Store.cookiesFile.exists()) addOption("--cookies", Store.cookiesFile.absolutePath)
        }

        val output = StringBuilder()
        val ok = try {
            val resp = YoutubeDL.getInstance().execute(req, item.videoId) { progress, eta, line ->
                if (progress > 0) onProgress("${progress.toInt()}%" + if (eta > 0) " · ${eta}s left" else "")
                if (line.isNotBlank()) output.appendLine(line)
            }
            output.appendLine(resp.out).appendLine(resp.err)
            resp.exitCode == 0
        } catch (e: Exception) {
            output.appendLine(e.message ?: e.toString())
            false
        }
        val text = output.toString()
        val lower = text.lowercase()

        try {
            if ("does not pass filter" in lower) {
                return Result(Outcome.LIVE, "live or upcoming stream")
            }
            if (!ok) {
                if (COOKIE_ERRORS.any { it in lower }) return Result(Outcome.COOKIES, "YouTube wants a sign-in")
                val err = text.lines().lastOrNull { it.contains("ERROR", true) } ?: text.lines().lastOrNull { it.isNotBlank() } ?: "unknown error"
                return Result(Outcome.FAILED, err.take(300))
            }
            val files = work.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                .orEmpty()
            if (files.isEmpty()) return Result(Outcome.FAILED, "yt-dlp finished but no file was produced")
            val main = files.maxByOrNull { it.length() }!!
            val saved = save(ctx, main, item.list)
            return Result(Outcome.OK, savedAs = saved)
        } finally {
            work.deleteRecursively()
        }
    }

    fun cancel(videoId: String) = try { YoutubeDL.getInstance().destroyProcessById(videoId) } catch (_: Exception) { false }

    /** Copies the finished file into <chosen folder>/<list>/ (or the app's own folder). */
    private fun save(ctx: Context, file: File, list: String): String {
        val tree = Store.S.treeUri
        if (tree != null) {
            val root = DocumentFile.fromTreeUri(ctx, Uri.parse(tree))
                ?: throw RuntimeException("Download folder is not available - pick it again in Settings")
            if (!root.canWrite()) throw RuntimeException("Can't write to the download folder - pick it again in Settings")
            val folder = root.listFiles().firstOrNull { it.isDirectory && it.name.equals(list, true) }
                ?: root.createDirectory(list) ?: throw RuntimeException("Couldn't create folder $list")
            val ext = file.extension.lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            folder.findFile(file.name)?.delete()
            val out = folder.createFile(mime, file.nameWithoutExtension)
                ?: throw RuntimeException("Couldn't create ${file.name}")
            ctx.contentResolver.openOutputStream(out.uri)!!.use { o -> file.inputStream().use { it.copyTo(o, 1 shl 16) } }
            return "$list/${out.name ?: file.name}"
        }
        val dir = File(ctx.getExternalFilesDir(null), "downloads/$list").apply { mkdirs() }
        val dest = File(dir, file.name)
        file.copyTo(dest, overwrite = true)
        return dest.absolutePath
    }
}
