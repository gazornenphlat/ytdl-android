package app.ytdl.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.ytdl.R
import app.ytdl.core.Downloader
import app.ytdl.core.Feeds
import app.ytdl.core.QueueItem
import app.ytdl.core.FailedItem
import app.ytdl.core.Scheduler
import app.ytdl.core.Store
import app.ytdl.core.now
import app.ytdl.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * The background job. Android wakes it about every 15 minutes (it can be later when the
 * phone is saving battery). Each run:
 *   1. updates yt-dlp once a day
 *   2. reads the RSS feeds of channels that are due (smart schedule) and queues new videos
 *   3. downloads the queue - only if the network/charging rules in Settings allow it
 */
class CheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Store.init(applicationContext)
        if (!running.tryLock()) return@withContext Result.success()   // another run is active
        try {
            runCycle(inputData.getBoolean(KEY_ALL, false), inputData.getBoolean(KEY_DOWNLOAD_ONLY, false))
        } catch (e: Exception) {
            Store.log("Run failed: ${e.message}")
        } finally {
            Store.busy = false
            Store.progress = ""
            updateNext()
            running.unlock()
        }
        Result.success()
    }

    private suspend fun runCycle(checkAll: Boolean, downloadOnly: Boolean) {
        val ctx = applicationContext
        if (Store.S.paused && !checkAll) { Store.status = "Paused"; return }
        if (checkAll && Store.S.paused) Store.S.paused = false
        Store.busy = true

        if (!downloadOnly) {
            Store.status = "Checking channels"
            checkFeeds(checkAll)
        }

        if (Store.queue.isEmpty()) { Store.status = "Idle"; return }
        val block = whyNotDownload(ctx)
        if (block != null) {
            Store.status = block
            Store.log("${Store.queue.size} video(s) waiting - $block")
            return
        }
        if (Store.S.cookieProblem && !checkAll) {
            Store.status = "Paused - YouTube sign-in needed"
            return
        }
        Store.S.cookieProblem = false
        Downloader.dailyUpdate(ctx)
        downloadQueue()
    }

    // ---------------------------------------------------------------- feeds
    private suspend fun checkFeeds(all: Boolean) = coroutineScope {
        val ts = now()
        val due = if (all) Store.channels.toList() else Scheduler.due(ts)
        if (due.isEmpty()) return@coroutineScope
        Store.log("Checking ${due.size} channel(s)${if (all) " (all)" else ""}")
        val gate = Semaphore(6)
        var added = 0
        due.map { c ->
            async {
                gate.withPermit {
                    try {
                        val feed = Feeds.fetch(c.id)
                        val st = Store.state(c.name)
                        synchronized(st) {
                            if (feed.title.isNotEmpty()) st.title = feed.title
                            feed.entries.forEach { e -> if (e.published > 0 && e.published !in st.uploads) st.uploads += e.published }
                            st.uploads.sort()
                            val fresh = feed.entries.filter { it.videoId !in st.seen && it.videoId !in Store.downloaded }
                            if (!st.primed && !Store.S.backlogOnAdd) {
                                // first look at this channel: existing videos are history, not new
                                st.seen += feed.entries.map { it.videoId }
                            } else {
                                synchronized(Store.queue) {
                                    fresh.sortedBy { it.published }.forEach { e ->
                                        if (Store.queue.none { it.videoId == e.videoId }) {
                                            Store.queue += QueueItem(e.videoId, e.title, c.name, c.list, c.wav, e.published)
                                            added++
                                        }
                                    }
                                }
                                st.seen += fresh.map { it.videoId }
                            }
                            st.primed = true
                            st.lastChecked = ts
                        }
                    } catch (e: Exception) {
                        Store.state(c.name).lastChecked = ts   // don't hammer a broken feed
                        Store.log("Feed error for ${c.name}: ${e.message}")
                    }
                }
            }
        }.awaitAll()
        Store.S.lastCheck = ts
        Store.save()
        if (added > 0) Store.log("Found $added new video(s)")
    }

    // ---------------------------------------------------------------- downloads
    private suspend fun downloadQueue() = coroutineScope {
        val ctx = applicationContext
        setForeground(foregroundInfo("Starting downloads"))
        Store.status = "Downloading"
        val items = synchronized(Store.queue) { Store.queue.toList() }
        val gate = Semaphore(Store.S.parallel.coerceIn(1, 4))
        var ok = 0; var failed = 0; var gaveUp = 0
        var stop = false

        items.map { item ->
            async {
                gate.withPermit {
                    if (stop || whyNotDownload(ctx) != null) return@withPermit
                    Store.progress = item.title
                    var lastUpdate = 0L
                    val r = Downloader.download(ctx, item) { p ->
                        Store.progress = "${item.title} · $p"
                        val t = System.currentTimeMillis()
                        if (t - lastUpdate > 2000) {        // don't flood the notification
                            lastUpdate = t
                            Store.bump()
                            setForegroundAsync(foregroundInfo("${item.title.take(40)} · $p"))
                        }
                    }
                    synchronized(Store.queue) {
                        when (r.outcome) {
                            Downloader.Outcome.OK -> {
                                Store.queue.removeAll { it.videoId == item.videoId }
                                Store.downloaded += item.videoId
                                ok++
                                Store.log("Downloaded ${item.title} -> ${r.savedAs}")
                            }
                            Downloader.Outcome.LIVE ->
                                Store.log("Live/upcoming, will retry later: ${item.title}")
                            Downloader.Outcome.COOKIES -> {
                                stop = true
                                Store.S.cookieProblem = true
                                Store.log("YouTube wants a sign-in - downloads paused")
                            }
                            Downloader.Outcome.FAILED -> {
                                val q = Store.queue.firstOrNull { it.videoId == item.videoId }
                                if (q != null) {
                                    q.fails++; q.lastError = r.message
                                    if (q.fails >= Store.S.maxFails) {
                                        Store.queue.remove(q)
                                        Store.failed += FailedItem(q.videoId, q.title, q.channel, q.list, q.wav, now(), r.message)
                                        gaveUp++
                                        Store.log("Gave up on ${q.title}: ${r.message}")
                                    } else {
                                        failed++
                                        Store.log("Failed (${q.fails}/${Store.S.maxFails}) ${q.title}: ${r.message}")
                                    }
                                }
                            }
                        }
                    }
                    Store.save()
                }
            }
        }.awaitAll()

        Store.S.lastRunOk = ok
        Store.S.lastRunAt = now()
        Store.S.totalDownloaded = Store.S.totalDownloaded + ok
        Store.status = "Idle"
        when {
            Store.S.cookieProblem -> notify(ctx, "YouTube sign-in needed",
                "Open ytdl → Settings → Sign in to YouTube, then tap Run now.")
            ok + failed + gaveUp > 0 -> notify(ctx, "Downloads finished", buildString {
                append("$ok downloaded")
                if (failed > 0) append(", $failed failed (will retry)")
                if (gaveUp > 0) append(", $gaveUp gave up")
            })
        }
    }

    private fun foregroundInfo(text: String): ForegroundInfo {
        val ctx = applicationContext
        ensureChannels(ctx)
        val n = NotificationCompat.Builder(ctx, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("ytdl · downloading")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx))
            .build()
        return if (Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(NOTIF_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIF_PROGRESS, n)
    }

    companion object {
        private val running = Mutex()
        const val KEY_ALL = "all"
        const val KEY_DOWNLOAD_ONLY = "downloadOnly"
        private const val PERIODIC = "ytdl-periodic"
        private const val NOW = "ytdl-now"
        private const val CH_PROGRESS = "progress"
        private const val CH_RESULTS = "results"
        private const val NOTIF_PROGRESS = 1
        private const val NOTIF_RESULT = 2

        /** Keeps the 15-minute background check scheduled. */
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<CheckWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /** "Run now": check every channel, then download. */
        fun runNow(ctx: Context, all: Boolean = true, downloadOnly: Boolean = false) {
            val req = OneTimeWorkRequestBuilder<CheckWorker>()
                .setInputData(workDataOf(KEY_ALL to all, KEY_DOWNLOAD_ONLY to downloadOnly))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, req)
        }

        fun cancelAll(ctx: Context) {
            Store.queue.forEach { Downloader.cancel(it.videoId) }
        }

        /** Returns a reason downloads can't start right now, or null if they can. */
        fun whyNotDownload(ctx: Context): String? {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "Waiting for a connection"
            val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            val cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            if (!Store.S.allowMobile && (cellular || metered)) return "Waiting for Wi-Fi"
            if (Store.S.chargingOnly && !isCharging(ctx)) return "Waiting for the charger"
            return null
        }

        private fun isCharging(ctx: Context): Boolean =
            ctx.getSystemService(BatteryManager::class.java)?.isCharging == true

        fun updateNext() {
            val (t, n) = Scheduler.nextDue()
            // Android runs us on 15-minute ticks, so round up to the next likely wake
            Store.S.nextRun = t
            Store.S.nextCount = n
        }

        fun ensureChannels(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "Download progress", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_RESULTS, "Download results", NotificationManager.IMPORTANCE_DEFAULT))
        }

        private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        fun notify(ctx: Context, title: String, text: String) {
            ensureChannels(ctx)
            val n = NotificationCompat.Builder(ctx, CH_RESULTS)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openApp(ctx))
                .build()
            try { NotificationManagerCompat.from(ctx).notify(NOTIF_RESULT, n) } catch (_: SecurityException) { }
        }
    }
}
