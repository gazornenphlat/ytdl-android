package app.ytdl.core

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Smart schedule (ported from the PC version's ytdl_schedule.py).
 *
 * A channel is due when:
 *  - it hasn't been checked for MAX_GAP (safety net), or
 *  - it has too little history / uploads very often -> every intervalMin, or
 *  - we're inside a window from LEAD before to WINDOW after one of its usual
 *    upload times of day (only on its usual weekdays, if consistent) -> every ACTIVE.
 */
object Scheduler {
    private const val ACTIVE = 30 * 60L
    private const val WINDOW = 3 * 3600L
    private const val LEAD = 15 * 60L
    private const val MAX_GAP = 24 * 3600L
    private const val MIN_UPLOADS = 4
    private const val BUSY_GAP = 8 * 3600L
    private val DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    enum class Kind { UNKNOWN, BUSY, PATTERN }
    data class Pattern(val kind: Kind, val minutes: List<Int>, val weekdays: Set<Int>?)

    private val cache = HashMap<String, Pair<Int, Pattern>>()
    private val zone get() = ZoneId.systemDefault()

    fun pattern(name: String): Pattern {
        val ups = Store.state(name).uploads.filter { it > 0 }.sorted()
        val key = ups.size * 31 + (ups.lastOrNull()?.hashCode() ?: 0)
        cache[name]?.let { if (it.first == key) return it.second }
        val p = compute(ups)
        cache[name] = key to p
        return p
    }

    private fun compute(ups: List<Long>): Pattern {
        if (ups.size < MIN_UPLOADS) return Pattern(Kind.UNKNOWN, emptyList(), null)
        val gaps = ups.zipWithNext { a, b -> b - a }.sorted()
        if (gaps.isNotEmpty() && gaps[gaps.size / 2] < BUSY_GAP) return Pattern(Kind.BUSY, emptyList(), null)
        val recent = ups.takeLast(30).map { ZonedDateTime.ofInstant(Instant.ofEpochSecond(it), zone) }
        val minutes = recent.map { it.hour * 60 + it.minute }.distinct().sorted()
        val days = recent.map { it.dayOfWeek.value - 1 }.toSet()
        val weekdays = if (recent.size >= 8 && days.size <= 4) days else null
        return Pattern(Kind.PATTERN, minutes, weekdays)
    }

    fun inWindow(name: String, ts: Long): Boolean {
        val p = pattern(name)
        if (p.kind != Kind.PATTERN) return false
        val t = ZonedDateTime.ofInstant(Instant.ofEpochSecond(ts), zone)
        val nowMin = t.hour * 60 + t.minute
        val lead = (LEAD / 60).toInt(); val win = (WINDOW / 60).toInt()
        for (m in p.minutes) {
            val delta = Math.floorMod(nowMin - (m - lead), 1440)
            if (delta <= lead + win) {
                val startDay = t.minusMinutes(delta.toLong()).dayOfWeek.value - 1
                if (p.weekdays == null || startDay in p.weekdays) return true
            }
        }
        return false
    }

    /** Returns (due, reason). */
    fun isDue(c: Channel, ts: Long): Pair<Boolean, String> {
        val since = ts - Store.state(c.name).lastChecked
        val interval = Store.S.intervalMin * 60L
        if (since >= MAX_GAP) return true to "safety check"
        if (!Store.S.smart) return (since >= interval) to "every ${Store.S.intervalMin} min"
        return when (pattern(c.name).kind) {
            Kind.UNKNOWN -> (since >= interval) to "learning"
            Kind.BUSY -> (since >= interval) to "uploads often"
            Kind.PATTERN -> if (inWindow(c.name, ts)) (since >= ACTIVE) to "upload window"
                            else false to "outside window"
        }
    }

    fun due(ts: Long = now()): List<Channel> = Store.channels.toList().filter { isDue(it, ts).first }

    /** Earliest time any channel becomes due, and how many. */
    fun nextDue(from: Long = now()): Pair<Long, Int> {
        val list = Store.channels.toList()
        if (list.isEmpty()) return (from + 24 * 3600) to 0
        var t = from + 5 * 60
        val end = from + 24 * 3600
        while (t <= end) {
            val n = list.count { isDue(it, t).first }
            if (n > 0) return t to n
            t += 5 * 60
        }
        return end to 0
    }

    fun describe(c: Channel): String {
        val p = pattern(c.name)
        return when (p.kind) {
            Kind.PATTERN -> {
                val times = cluster(p.minutes).joinToString(", ") { "%02d:%02d".format(it / 60, it % 60) }
                val days = p.weekdays?.sorted()?.joinToString(" ") { DAYS[it] } ?: "Any day"
                if (Store.S.smart) "$days · around $times" else "Every ${Store.S.intervalMin} min"
            }
            Kind.BUSY -> "Uploads very often · every ${Store.S.intervalMin} min"
            Kind.UNKNOWN -> "Learning · every ${Store.S.intervalMin} min"
        }
    }

    private fun cluster(minutes: List<Int>, gap: Int = 45): List<Int> {
        val out = mutableListOf<MutableList<Int>>()
        for (m in minutes.sorted()) {
            if (out.isEmpty() || m - out.last().last() > gap) out += mutableListOf(m) else out.last() += m
        }
        return out.map { it.first() }
    }
}
