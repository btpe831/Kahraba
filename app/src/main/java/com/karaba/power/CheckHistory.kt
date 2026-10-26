package com.karaba.power

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The last few quick check results, newest first. */
object CheckHistory {
    private const val KEY = "check_history"
    private const val KEEP = 10

    data class Entry(val timeMs: Long, val verdict: QuickCheck.Verdict)

    fun read(ctx: Context): List<Entry> =
        Prefs.sp(ctx).getString(KEY, "")!!.lines().mapNotNull { line ->
            val p = line.split(";")
            val t = p.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val v = runCatching { QuickCheck.Verdict.valueOf(p[1]) }.getOrNull() ?: return@mapNotNull null
            Entry(t, v)
        }

    fun add(ctx: Context, verdict: QuickCheck.Verdict, timeMs: Long = System.currentTimeMillis()) {
        val all = listOf(Entry(timeMs, verdict)) + read(ctx)
        Prefs.sp(ctx).edit()
            .putString(KEY, all.take(KEEP).joinToString("\n") { "${it.timeMs};${it.verdict.name}" })
            .apply()
    }

    fun label(v: QuickCheck.Verdict) = when (v) {
        QuickCheck.Verdict.KAHRABA -> "KAHRABA"
        QuickCheck.Verdict.MOTEUR -> "MOTEUR"
        QuickCheck.Verdict.CANT_TELL -> "Can't tell"
    }

    fun formatted(ctx: Context): String {
        val entries = read(ctx)
        if (entries.isEmpty()) return "None yet."
        val fmt = SimpleDateFormat("EEE HH:mm", Locale.US)
        return entries.joinToString("\n") { "${fmt.format(Date(it.timeMs))}  ${label(it.verdict)}" }
    }
}
