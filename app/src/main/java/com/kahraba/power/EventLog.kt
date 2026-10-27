package com.kahraba.power

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The switch-over log: one line per change, "timeMs;SOURCE;reason". */
object EventLog {
    data class Entry(val timeMs: Long, val source: Source, val reason: String)

    fun file(ctx: Context) = File(ctx.filesDir, "switch-log.csv")

    @Synchronized
    fun append(ctx: Context, t: Transition) {
        val reason = t.reason.replace(";", ",").replace("\n", " ")
        file(ctx).appendText("${t.timeMs};${t.to.name};$reason\n")
    }

    @Synchronized
    fun read(ctx: Context): List<Entry> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { line ->
            val p = line.split(";", limit = 3)
            if (p.size < 3) return@mapNotNull null
            val t = p[0].toLongOrNull() ?: return@mapNotNull null
            val s = runCatching { Source.valueOf(p[1]) }.getOrNull() ?: return@mapNotNull null
            Entry(t, s, p[2])
        }
    }

    @Synchronized
    fun clear(ctx: Context) {
        file(ctx).delete()
    }

    /** Newest first, with how long each state lasted. */
    fun formatted(ctx: Context): String {
        val entries = read(ctx)
        if (entries.isEmpty()) return "No switches recorded yet."
        val day = SimpleDateFormat("EEE d MMM", Locale.US)
        val clock = SimpleDateFormat("HH:mm", Locale.US)
        val sb = StringBuilder()
        var lastDay = ""
        for (i in entries.indices.reversed()) {
            val e = entries[i]
            val d = day.format(Date(e.timeMs))
            if (d != lastDay) {
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append("— $d —\n")
                lastDay = d
            }
            val end = if (i + 1 < entries.size) entries[i + 1].timeMs else System.currentTimeMillis()
            val lasted = PowerDetector.formatDuration(end - e.timeMs)
            val now = if (i + 1 < entries.size) "" else " so far"
            sb.append("${clock.format(Date(e.timeMs))}  ${label(e.source)}  ($lasted$now)\n")
            sb.append("        ${e.reason}\n")
        }
        return sb.toString()
    }

    fun label(s: Source) = when (s) {
        Source.KAHRABA -> "KAHRABA"
        Source.MOTEUR -> "MOTEUR"
        Source.NO_POWER -> "NO POWER"
        Source.UNKNOWN -> "UNKNOWN"
    }
}
