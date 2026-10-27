package com.karaba.power

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Per-day information for the calendar: notes, quick checks and monitor hours. */
object DayData {
    private val keyFmt get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun key(timeMs: Long): String = keyFmt.format(Date(timeMs))

    fun key(cal: Calendar): String = keyFmt.format(cal.time)

    // ---- Notes (kept on the phone only) ----

    fun note(ctx: Context, day: String): String = Prefs.sp(ctx).getString("note_$day", "") ?: ""

    fun setNote(ctx: Context, day: String, text: String) {
        val e = Prefs.sp(ctx).edit()
        if (text.isBlank()) e.remove("note_$day") else e.putString("note_$day", text)
        e.apply()
    }

    fun daysWithNotes(ctx: Context): Set<String> =
        Prefs.sp(ctx).all.keys.filter { it.startsWith("note_") }.map { it.removePrefix("note_") }.toSet()

    // ---- Quick checks ----

    fun checksByDay(ctx: Context): Map<String, List<CheckHistory.Entry>> =
        CheckHistory.read(ctx).filter { !it.fromMonitor }.groupBy { key(it.timeMs) }

    // ---- Always-on monitor ----

    /** Milliseconds of kahraba per day, from the monitor's switch log. */
    fun kahrabaByDay(ctx: Context, nowMs: Long = System.currentTimeMillis()): Map<String, Long> {
        val entries = EventLog.read(ctx).sortedBy { it.timeMs }
        val out = HashMap<String, Long>()
        for (i in entries.indices) {
            if (entries[i].source != Source.KAHRABA) continue
            var start = entries[i].timeMs
            val end = minOf(
                if (i + 1 < entries.size) entries[i + 1].timeMs else if (MonitorState.running) nowMs else start,
                nowMs,
            )
            while (start < end) {
                val c = Calendar.getInstance().apply {
                    timeInMillis = start
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    add(Calendar.DAY_OF_YEAR, 1)
                }
                val chunkEnd = minOf(end, c.timeInMillis)
                val k = key(start)
                out[k] = (out[k] ?: 0L) + (chunkEnd - start)
                start = chunkEnd
            }
        }
        return out
    }

    /** Monitor log entries that happened on a given day. */
    fun monitorEvents(ctx: Context, day: String): List<EventLog.Entry> =
        EventLog.read(ctx).filter { key(it.timeMs) == day }.sortedBy { it.timeMs }

    /** "5h 12m" style. */
    fun hours(ms: Long): String {
        val m = ms / 60_000
        return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    }

    /** Short form for a calendar cell: "5h", "40m". */
    fun shortHours(ms: Long): String {
        val m = ms / 60_000
        return if (m >= 60) "${(m + 30) / 60}h" else "${m}m"
    }
}
