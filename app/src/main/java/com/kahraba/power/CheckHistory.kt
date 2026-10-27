package com.kahraba.power

import android.content.Context

/**
 * Quick check results, newest first, kept on the phone.
 * Each line: "timeMs;VERDICT;verified;lux;spot;byLevel;fromMonitor;SAID" (older lines have fewer fields).
 * A "spot" is what the app calls a room.
 * The time is used as the entry's id for changing or deleting it.
 *
 * Checks done at a named spot (e.g. "Kitchen") also teach the app how bright that spot is on
 * each source. Every spot learns separately.
 */
object CheckHistory {
    private const val KEY = "check_history"
    /** Enough for several months of checks in the calendar. */
    private const val KEEP = 1000
    /** How many recent results per source are used to learn the spot's brightness. */
    private const val LEARN_FROM = 5

    data class Entry(
        val timeMs: Long,
        val verdict: QuickCheck.Verdict,
        /** True when the user confirmed or changed the result. */
        val corrected: Boolean = false,
        val lux: Double? = null,
        /** The named spot the check was done at, or "" for none. */
        val spot: String = "",
        /** Decided from the learned brightness (not from wobble), so it doesn't teach anything. */
        val byLevel: Boolean = false,
        /** Taught from the always-on monitor ("It's kahraba / moteur"); not shown as a check. */
        val fromMonitor: Boolean = false,
        /**
         * What the app said before the user confirmed or fixed it, for the accuracy score.
         * Null if never confirmed (or confirmed before version 1.4, when this wasn't kept).
         */
        val said: QuickCheck.Verdict? = null,
    )

    fun read(ctx: Context): List<Entry> =
        Prefs.sp(ctx).getString(KEY, "")!!.lines().mapNotNull { line ->
            val p = line.split(";")
            val t = p.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val v = runCatching { QuickCheck.Verdict.valueOf(p[1]) }.getOrNull() ?: return@mapNotNull null
            // An early test version saved "1" for "at my spot".
            val spot = when (val raw = p.getOrNull(4) ?: "") { "0" -> ""; "1" -> "My spot"; else -> raw }
            val said = p.getOrNull(7)?.let { s -> runCatching { QuickCheck.Verdict.valueOf(s) }.getOrNull() }
            Entry(t, v, p.getOrNull(2) == "1", p.getOrNull(3)?.toDoubleOrNull(), spot, p.getOrNull(5) == "1", p.getOrNull(6) == "1", said)
        }

    private fun write(ctx: Context, entries: List<Entry>) {
        fun b(x: Boolean) = if (x) 1 else 0
        val text = entries.take(KEEP).joinToString("\n") {
            "${it.timeMs};${it.verdict.name};${b(it.corrected)};" +
                "${it.lux?.let { l -> "%.1f".format(java.util.Locale.US, l) } ?: ""};${it.spot};${b(it.byLevel)};${b(it.fromMonitor)};${it.said?.name ?: ""}"
        }
        Prefs.sp(ctx).edit().putString(KEY, text).apply()
    }

    fun add(
        ctx: Context,
        verdict: QuickCheck.Verdict,
        lux: Double?,
        spot: String = "",
        byLevel: Boolean = false,
        verified: Boolean = false,
        fromMonitor: Boolean = false,
        timeMs: Long = System.currentTimeMillis(),
    ) {
        val e = Entry(timeMs, verdict, verified, lux, spot, byLevel, fromMonitor)
        write(ctx, listOf(e) + read(ctx))
    }

    /** The user says what it really was (confirming or fixing the result). */
    fun correct(ctx: Context, timeMs: Long, verdict: QuickCheck.Verdict) {
        write(ctx, read(ctx).map { if (it.timeMs == timeMs) corrected(it, verdict) else it })
    }

    /** Keeps what the app first said, so fixing a fix still scores the app's own answer. */
    fun corrected(e: Entry, verdict: QuickCheck.Verdict) =
        e.copy(verdict = verdict, corrected = true, said = e.said ?: if (e.corrected) null else e.verdict)

    /** How often one kind of result was right, among checks the user confirmed. */
    data class Score(val said: QuickCheck.Verdict, val right: Int, val total: Int)

    /**
     * The accuracy score: for each result the app gives (moteur, kahraba, no flicker), how many
     * confirmed checks were right. "No flicker" counts as right when it was kahraba.
     */
    fun scores(entries: List<Entry>): List<Score> {
        val judged = entries.filter {
            !it.fromMonitor && it.said != null &&
                (it.verdict == QuickCheck.Verdict.KAHRABA || it.verdict == QuickCheck.Verdict.MOTEUR)
        }
        return listOf(QuickCheck.Verdict.MOTEUR, QuickCheck.Verdict.KAHRABA, QuickCheck.Verdict.NO_FLICKER).map { v ->
            val said = judged.filter { it.said == v }
            val truth = if (v == QuickCheck.Verdict.MOTEUR) QuickCheck.Verdict.MOTEUR else QuickCheck.Verdict.KAHRABA
            Score(v, said.count { it.verdict == truth }, said.size)
        }
    }

    fun delete(ctx: Context, timeMs: Long) {
        write(ctx, read(ctx).filter { it.timeMs != timeMs })
    }

    /**
     * What the spot's brightness looks like on each source, learned from checks at the spot:
     * kahraba from checks the user confirmed; moteur from confirmed checks and from checks that
     * saw a wobble (a wobble is proof of moteur).
     */
    fun spotLevels(ctx: Context, spotName: String): Pair<Double?, Double?> {
        if (spotName.isEmpty()) return null to null
        val spot = read(ctx).filter { it.spot == spotName && it.lux != null }
        val kahraba = spot.filter { it.verdict == QuickCheck.Verdict.KAHRABA && it.corrected }
            .take(LEARN_FROM).map { it.lux!! }
        val moteur = spot.filter { it.verdict == QuickCheck.Verdict.MOTEUR && (it.corrected || !it.byLevel) }
            .take(LEARN_FROM).map { it.lux!! }
        return PowerDetector.median(kahraba) to PowerDetector.median(moteur)
    }

    /** Forget what was learned at a spot (deleting it). Results stay in the list. */
    fun forgetSpot(ctx: Context, spotName: String) {
        write(ctx, read(ctx).map { if (it.spot == spotName) it.copy(spot = "") else it })
    }

    fun renameSpot(ctx: Context, from: String, to: String) {
        write(ctx, read(ctx).map { if (it.spot == from) it.copy(spot = to) else it })
    }

    fun label(v: QuickCheck.Verdict) = when (v) {
        QuickCheck.Verdict.KAHRABA -> "Kahraba"
        QuickCheck.Verdict.MOTEUR -> "Moteur"
        QuickCheck.Verdict.NO_FLICKER -> "No flicker"
        QuickCheck.Verdict.CANT_TELL -> "Can't tell"
    }
}
