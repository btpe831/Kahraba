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

    /** One line of the log, as saved here and sent to other phones. */
    fun line(t: Transition) = "${t.timeMs};${t.to.name};${t.reason.replace(";", ",").replace("\n", " ")}"

    fun line(e: Entry) = "${e.timeMs};${e.source.name};${e.reason}"

    @Synchronized
    fun append(ctx: Context, t: Transition) {
        file(ctx).appendText(line(t) + "\n")
    }

    @Synchronized
    fun read(ctx: Context): List<Entry> = readFile(file(ctx))

    private fun parse(line: String): Entry? {
        val p = line.split(";", limit = 3)
        if (p.size < 3) return null
        val t = p[0].toLongOrNull() ?: return null
        val s = runCatching { Source.valueOf(p[1]) }.getOrNull() ?: return null
        return Entry(t, s, p[2])
    }

    private fun readFile(f: File): List<Entry> = if (f.exists()) f.readLines().mapNotNull(::parse) else emptyList()

    // ---- The other phone's log (Alerts on other phones, 1.5.0) ----

    fun remoteFile(ctx: Context) = File(ctx.filesDir, "remote-switch-log.csv")

    /** Adds lines from the other phone's log (repeats are ignored), keeping 30 days, in time order. */
    @Synchronized
    fun mergeRemote(ctx: Context, lines: List<String>) {
        val f = remoteFile(ctx)
        val cutoff = System.currentTimeMillis() - 30 * 86_400_000L
        val all = (if (f.exists()) f.readLines() else emptyList()) + lines
        val kept = all.mapNotNull { l -> parse(l)?.let { l to it } }
            .distinctBy { it.first }
            .filter { it.second.timeMs >= cutoff }
            .sortedBy { it.second.timeMs }
        f.writeText(kept.joinToString("") { it.first + "\n" })
    }

    @Synchronized
    fun clearRemote(ctx: Context) {
        remoteFile(ctx).delete()
    }

    /**
     * Whether the screens show the other phone's log: this phone gets alerts from a phone running
     * Always-on mode, and doesn't run it itself.
     */
    fun showsRemote(ctx: Context): Boolean =
        !MonitorState.running && RemoteAlerts.getPairing(ctx) != null && remoteFile(ctx).let { it.exists() && it.length() > 0 }

    /** The log the screens show: this phone's, or the other phone's ([showsRemote]). */
    @Synchronized
    fun shown(ctx: Context): List<Entry> = if (showsRemote(ctx)) readFile(remoteFile(ctx)) else read(ctx)

    /** Whether the shown log is still being written, so its last state runs up to now. */
    fun shownLive(ctx: Context): Boolean =
        MonitorState.running || (showsRemote(ctx) && RemoteAlerts.current(ctx) != null)

    @Synchronized
    fun clear(ctx: Context) {
        file(ctx).delete()
    }

    /** Newest first, with how long each state lasted. */
    fun formatted(ctx: Context): String {
        val entries = shown(ctx)
        if (entries.isEmpty()) return "No switches recorded yet."
        val from = if (showsRemote(ctx)) "From ${RemoteAlerts.heard(ctx)?.device ?: "your other phone"} (Alerts on other phones)\n\n" else ""
        val day = SimpleDateFormat("EEE d MMM", Locale.US)
        val clock = SimpleDateFormat("h:mm a", Locale.US)
        val sb = StringBuilder(from)
        var lastDay = ""
        for (i in entries.indices.reversed()) {
            val e = entries[i]
            val d = day.format(Date(e.timeMs))
            if (d != lastDay) {
                if (lastDay.isNotEmpty()) sb.append("\n")
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
        Source.POWER_ON -> "POWER ON"
    }
}
