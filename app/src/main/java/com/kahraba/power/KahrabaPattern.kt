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

    /**
     * What Always-on mode recorded, as spans, with corrected kahraba shown as what the owner said.
     * Time after "Always-on mode stopped" isn't a span: nothing was recorded then.
     */
    fun spans(entries: List<EventLog.Entry>, nowMs: Long, running: Boolean): List<Span> {
        val sorted = entries.sortedBy { it.timeMs }
        val out = ArrayList<Span>()
        for (i in sorted.indices) {
            val e = sorted[i]
            if (isStop(e)) continue
            val end = minOf(sorted.getOrNull(i + 1)?.timeMs ?: if (running) nowMs else e.timeMs, nowMs)
            if (end <= e.timeMs) continue
            val source = if (e.source == Source.KAHRABA && corrected(sorted, i)) sorted[i + 1].source else e.source
            out += Span(e.timeMs, end, source)
        }
        return out
    }

    // ---- Written overview: today and the last 7 days ----

    /** "4h 5m" / "40m". */
    fun hours(ms: Long): String {
        val m = ms / 60_000
        return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    }

    private fun clock(ms: Long) = PowerDetector.formatClock(ms)

    /** Kahraba periods (touching spans joined) overlapping [from, to), clipped to it. */
    private fun kahrabaBlocks(spans: List<Span>, from: Long, to: Long): List<Span> {
        val out = ArrayList<Span>()
        for (sp in spans.filter { it.source == Source.KAHRABA }.sortedBy { it.start }) {
            val a = maxOf(sp.start, from)
            val b = minOf(sp.end, to)
            if (b <= a) continue
            val last = out.lastOrNull()
            if (last != null && a <= last.end + 60_000) out[out.size - 1] = last.copy(end = maxOf(last.end, b))
            else out += Span(a, b, Source.KAHRABA)
        }
        return out
    }

    /** Kahraba periods in [from, to) (touching ones joined), for a day's details (1.5.04). */
    fun kahrabaPeriods(entries: List<EventLog.Entry>, from: Long, to: Long, nowMs: Long, running: Boolean): List<Span> =
        kahrabaBlocks(spans(entries, nowMs, running), from, minOf(to, nowMs))

    /**
     * Whole kahraba periods that touch [from, to), not cut at its edges: one from 11:30 PM to 1:31 AM
     * shows in full on both days (1.5.05).
     */
    fun kahrabaPeriodsTouching(entries: List<EventLog.Entry>, from: Long, to: Long, nowMs: Long, running: Boolean): List<Span> =
        kahrabaBlocks(spans(entries, nowMs, running), 0L, nowMs).filter { it.end > from && it.start < minOf(to, nowMs) }

    /** How long Always-on mode recorded in [from, to). */
    fun recordedMs(entries: List<EventLog.Entry>, from: Long, to: Long, nowMs: Long, running: Boolean): Long =
        recorded(spans(entries, nowMs, running), from, minOf(to, nowMs))

    private fun recorded(spans: List<Span>, from: Long, to: Long) =
        spans.sumOf { maxOf(0L, minOf(it.end, to) - maxOf(it.start, from)) }

    /** Power cuts and outages that began in [from, to): a cut the moteur took over from, or power off. */
    private fun cuts(entries: List<EventLog.Entry>, from: Long, to: Long) = entries.count {
        it.timeMs in from until to && !isManual(it) &&
            (it.reason.startsWith("Power cut") || it.source == Source.NO_POWER)
    }

    private fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

    /**
     * Today, in plain words: kahraba time and when, power cuts, and how long Always-on mode ran.
     * [dayStart] is midnight today.
     */
    fun today(entries: List<EventLog.Entry>, nowMs: Long, running: Boolean, dayStart: Long): String {
        val spans = spans(entries, nowMs, running)
        val ran = recorded(spans, dayStart, nowMs)
        if (ran < 60_000) return "Nothing recorded today: Always-on mode wasn't running."
        val blocks = kahrabaBlocks(spans, dayStart, nowMs)
        val onNow = running && blocks.lastOrNull()?.end == nowMs
        val sb = StringBuilder()
        if (blocks.isEmpty()) {
            sb.append("No kahraba seen today.")
        } else {
            val total = blocks.sumOf { it.end - it.start }
            // Kahraba that began before midnight says so ("11:18 PM yesterday to 1:18 AM"), not "12:00 AM":
            // the total still counts only today's part (1.5.08).
            val whole = kahrabaBlocks(spans, 0L, nowMs)
            fun began(b: Span): String {
                val f = whole.firstOrNull { it.start < dayStart && it.end > dayStart && b.start <= dayStart }
                return if (f != null) "${clock(f.start)} yesterday" else clock(b.start)
            }
            val fromYesterday = blocks.any { b -> whole.any { it.start < dayStart && it.end > dayStart } && b.start <= dayStart }
            val times = blocks.mapIndexed { i, b ->
                if (onNow && i == blocks.lastIndex) "since ${began(b)}" else "${if (i == 0 && fromYesterday) "from " else ""}${began(b)} to ${clock(b.end)}"
            }
            sb.append(if (fromYesterday) "Kahraba for ${hours(total)} today: " else "Kahraba for ${hours(total)}: ")
            sb.append(if (times.size == 1) times[0] else times.dropLast(1).joinToString(", ") + " and " + times.last())
            sb.append(".")
        }
        val c = cuts(entries, dayStart, nowMs)
        sb.append(if (c == 0) " No power cuts." else " ${plural(c, "power cut")}.")
        if (nowMs - dayStart - ran > 30 * 60_000) sb.append(" Always-on mode ran for ${hours(ran)} of it.")
        return sb.toString()
    }

    /** The last 7 days (today and the 6 before), in plain words. [dayStarts]: midnight of each, today first. */
    fun week(entries: List<EventLog.Entry>, nowMs: Long, running: Boolean, dayStarts: List<Long>): String {
        val spans = spans(entries, nowMs, running)
        val from = dayStarts.last()
        val ran = recorded(spans, from, nowMs)
        if (ran < 60_000) return "Nothing recorded this week yet."
        val blocks = kahrabaBlocks(spans, from, nowMs)
        val total = blocks.sumOf { it.end - it.start }
        // Days Always-on mode ran for at least 6 hours, for a fair daily average.
        val ends = listOf(nowMs) + dayStarts.dropLast(1)
        val perDay = dayStarts.indices.map { i ->
            val a = dayStarts[i]
            val b = minOf(ends[i], nowMs)
            Triple(a, recorded(spans, a, b), kahrabaBlocks(spans, a, b).sumOf { it.end - it.start })
        }
        val fullDays = perDay.filter { it.second >= 6 * 3_600_000L }
        val sb = StringBuilder()
        if (blocks.isEmpty()) {
            sb.append("No kahraba seen this week.")
        } else {
            sb.append("Kahraba for ${hours(total)}, ${plural(blocks.size, "time")}")
            if (fullDays.isNotEmpty()) sb.append(", about ${hours(fullDays.sumOf { it.third } / fullDays.size)} a day")
            sb.append(".")
            val best = perDay.maxByOrNull { it.third }
            if (best != null && best.third > 0 && perDay.count { it.third > 0 } > 1) {
                sb.append(" Most on ${java.text.SimpleDateFormat("EEEE", java.util.Locale.US).format(java.util.Date(best.first))} (${hours(best.third)}).")
            }
        }
        val c = cuts(entries, from, nowMs)
        sb.append(if (c == 0) " No power cuts." else " ${plural(c, "power cut")}.")
        sb.append(" Always-on mode ran for ${hours(ran)} in all.")
        return sb.toString()
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
