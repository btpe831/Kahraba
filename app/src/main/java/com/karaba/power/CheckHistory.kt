package com.karaba.power

import android.content.Context

/**
 * Quick check results, newest first, kept on the phone. Each line: "timeMs;VERDICT;corrected;lux".
 * The time is used as the entry's id for changing or deleting it.
 */
object CheckHistory {
    private const val KEY = "check_history"
    private const val KEEP = 50

    data class Entry(
        val timeMs: Long,
        val verdict: QuickCheck.Verdict,
        /** True when the user changed the result after the check. */
        val corrected: Boolean = false,
        val lux: Double? = null,
    )

    fun read(ctx: Context): List<Entry> =
        Prefs.sp(ctx).getString(KEY, "")!!.lines().mapNotNull { line ->
            val p = line.split(";")
            val t = p.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val v = runCatching { QuickCheck.Verdict.valueOf(p[1]) }.getOrNull() ?: return@mapNotNull null
            Entry(t, v, p.getOrNull(2) == "1", p.getOrNull(3)?.toDoubleOrNull())
        }

    private fun write(ctx: Context, entries: List<Entry>) {
        val text = entries.take(KEEP).joinToString("\n") {
            "${it.timeMs};${it.verdict.name};${if (it.corrected) 1 else 0};${it.lux?.let { l -> "%.1f".format(java.util.Locale.US, l) } ?: ""}"
        }
        Prefs.sp(ctx).edit().putString(KEY, text).apply()
    }

    fun add(ctx: Context, verdict: QuickCheck.Verdict, lux: Double?, timeMs: Long = System.currentTimeMillis()) {
        write(ctx, listOf(Entry(timeMs, verdict, lux = lux)) + read(ctx))
    }

    /** The user says the check was wrong: store what it really was. */
    fun correct(ctx: Context, timeMs: Long, verdict: QuickCheck.Verdict) {
        write(ctx, read(ctx).map { if (it.timeMs == timeMs) it.copy(verdict = verdict, corrected = true) else it })
    }

    fun delete(ctx: Context, timeMs: Long) {
        write(ctx, read(ctx).filter { it.timeMs != timeMs })
    }

    fun label(v: QuickCheck.Verdict) = when (v) {
        QuickCheck.Verdict.KAHRABA -> "Kahraba"
        QuickCheck.Verdict.MOTEUR -> "Moteur"
        QuickCheck.Verdict.NO_FLICKER -> "No flicker"
        QuickCheck.Verdict.CANT_TELL -> "Can't tell"
    }
}
