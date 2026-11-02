package com.kahraba.power

/**
 * When kahraba comes and how long it stays, learned from Always-on mode's switch log: typical
 * length, how long until it comes back, and an estimate of the next time. Pure Kotlin, so it can
 * be unit tested.
 *
 * Only switches the app saw itself count: a kahraba start must be the app's own call (not "Set by
 * you", where the real start is unknown), an end must be a power cut, and a gap between two starts
 * only counts when Always-on mode ran the whole time. A kahraba that the owner then corrected to
 * moteur is left out.
 */
object KahrabaPattern {
    const val MANUAL = "Set by you"
    /** Times needed before showing a typical length or the next estimate. */
    const val MIN_SAMPLES = 3
    /** Gaps further apart than this are shown as a range, not "around". */
    const val SPREAD_MS = 2 * 3_600_000L

    data class Span(val start: Long, val end: Long, val source: Source)

    data class Summary(
        /** Kahraba on now (Always-on mode running and its last call was kahraba): since when. */
        val onSinceMs: Long?,
        val typicalMs: Long?,
        val lengths: Int,
        val gapMs: Long?,
        val gaps: Int,
        /** The next likely kahraba, as a window; null when there isn't enough data or it's overdue. */
        val nextFromMs: Long?,
        val nextToMs: Long?,
    )

    private fun isManual(e: EventLog.Entry) = e.reason.startsWith(MANUAL)
    private fun isStop(e: EventLog.Entry) = e.source == Source.UNKNOWN && !isManual(e)

    /** Kahraba the owner corrected straight after (the next entry is "Set by you" to something else). */
    private fun corrected(sorted: List<EventLog.Entry>, i: Int): Boolean {
        val next = sorted.getOrNull(i + 1) ?: return false
        return isManual(next) && next.source != Source.KAHRABA
    }

    /** What Always-on mode recorded, as spans, with corrected kahraba shown as what the owner said. */
    fun spans(entries: List<EventLog.Entry>, nowMs: Long, running: Boolean): List<Span> {
        val sorted = entries.sortedBy { it.timeMs }
        val out = ArrayList<Span>()
        for (i in sorted.indices) {
            val e = sorted[i]
            val end = minOf(sorted.getOrNull(i + 1)?.timeMs ?: if (running) nowMs else e.timeMs, nowMs)
            if (end <= e.timeMs) continue
            val source = if (e.source == Source.KAHRABA && corrected(sorted, i)) sorted[i + 1].source else e.source
            out += Span(e.timeMs, end, source)
        }
        return out
    }

    fun summary(entries: List<EventLog.Entry>, nowMs: Long, running: Boolean): Summary {
        val sorted = entries.sortedBy { it.timeMs }
        val starts = ArrayList<Int>() // indices of the app's own kahraba calls, not corrected
        val lengths = ArrayList<Long>()
        for (i in sorted.indices) {
            val e = sorted[i]
            if (e.source != Source.KAHRABA || isManual(e) || corrected(sorted, i)) continue
            starts += i
            val next = sorted.getOrNull(i + 1) ?: continue
            if (!isManual(next) && (next.source == Source.MOTEUR || next.source == Source.NO_POWER)) {
                lengths += next.timeMs - e.timeMs
            }
        }
        val gaps = ArrayList<Long>()
        for (k in 1 until starts.size) {
            val a = starts[k - 1]
            val b = starts[k]
            if ((a + 1 until b).none { isStop(sorted[it]) }) gaps += sorted[b].timeMs - sorted[a].timeMs
        }

        val last = sorted.lastOrNull()
        val onSince = if (running && last != null && last.source == Source.KAHRABA) last.timeMs else null
        val typical = if (lengths.size >= MIN_SAMPLES) PowerDetector.median(lengths.map { it.toDouble() })?.toLong() else null
        val gap = if (gaps.size >= MIN_SAMPLES) PowerDetector.median(gaps.map { it.toDouble() })?.toLong() else null

        var nextFrom: Long? = null
        var nextTo: Long? = null
        val lastStart = starts.lastOrNull()?.let { sorted[it].timeMs }
        if (gap != null && lastStart != null && onSince == null) {
            val lo = gaps.min()
            val hi = gaps.max()
            if (hi - lo <= SPREAD_MS) {
                nextFrom = lastStart + gap
                nextTo = nextFrom
            } else {
                nextFrom = lastStart + lo
                nextTo = lastStart + hi
            }
            // Overdue: the estimate has passed, so it says nothing useful now.
            if (nextTo < nowMs) {
                nextFrom = null
                nextTo = null
            }
        }
        return Summary(onSince, typical, lengths.size, gap, gaps.size, nextFrom, nextTo)
    }
}
