package app.ytdl

import android.app.Application
import app.ytdl.core.Downloader
import app.ytdl.core.Store
import app.ytdl.work.CheckWorker
import kotlin.concurrent.thread

class YtdlApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        CheckWorker.ensureChannels(this)
        CheckWorker.schedule(this)
        // Unpack yt-dlp/Python/ffmpeg in the background so the first download starts quickly
        thread(name = "ytdl-init") { Downloader.init(this) }
    }
}
