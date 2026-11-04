package com.kahraba.power

/**
 * The light graph on the Always-on screen: what Always-on mode saved (raw light readings and, once
 * a second, what it called and whether the charger was on), cut into equal slices of time for
 * drawing. Pure Kotlin, so it can be unit tested.
 */
object LightHistory {
    /** A raw light reading (raw-YYYY-MM-DD.csv: "time_ms,lux"; saved only when it changes). */
    class Reading(val timeMs: Long, val lux: Double)

    /** What the app called that second, and whether the charger was off (light-YYYY-MM-DD.csv). */
    class Status(val timeMs: Long, val source: Source, val chargerOff: Boolean)

    /**
     * One slice of the graph. [shade]: KAHRABA, MOTEUR or NO_POWER (charger off), else null.
     * [recorded] is false when Always-on mode wasn't running: drawn blank. The light values are
     * null when there's nothing to draw (not running, power off, or no reading yet).
     */
    class Slice(
        val recorded: Boolean,
        val shade: Source?,
        val minLux: Double?,
        val maxLux: Double?,
        val meanLux: Double?,
    )

    fun parseReading(line: String): Reading? {
        val comma = line.indexOf(',')
        if (comma <= 0) return null
        val t = line.substring(0, comma).toLongOrNull() ?: return null
        val v = line.substring(comma + 1).trim().toDoubleOrNull() ?: return null
        return Reading(t, v)
    }

    /**
     * A line of light-YYYY-MM-DD.csv ("2026-11-02 10:21:00,3604.00,0.000,on,KAHRABA,\"…\"").
     * [timeOf] turns the "yyyy-MM-dd HH:mm:ss" text into a time.
     */
    fun parseStatus(line: String, timeOf: (String) -> Long?): Status? {
        if (line.length < 21 || line[19] != ',') return null
        val t = timeOf(line.substring(0, 19)) ?: return null
        val parts = line.substring(20).split(',', limit = 5)
        if (parts.size < 4) return null
        val source = Source.values().firstOrNull { it.name == parts[3] } ?: return null
        return Status(t, source, parts[2] == "off")
    }

    /** Status rows further apart than this mean Always-on mode wasn't running in between. */
    const val GAP_MS = 5_000L

    /**
     * Cuts [from, to) into [n] slices. Both lists sorted by time. A slice's light is sample-and-hold:
     * the reading in force at its start plus every reading inside it, time-weighted for the mean.
     */
    fun slices(readings: List<Reading>, statuses: List<Status>, from: Long, to: Long, n: Int): List<Slice> {
        val out = ArrayList<Slice>(n)
        if (n <= 0 || to <= from) return out
        val len = (to - from).toDouble() / n
        var ri = 0 // first reading at or after the slice start
        var si = 0 // first status at or after the slice start
        for (k in 0 until n) {
            val a = from + (k * len).toLong()
            val b = from + ((k + 1) * len).toLong()
            while (ri < readings.size && readings[ri].timeMs < a) ri++
            while (si < statuses.size && statuses[si].timeMs < a) si++

            // What the app called: statuses inside the slice, or the one just before it.
            var sj = si
            var kahraba = 0
            var moteur = 0
            var off = false
            var any = false
            while (sj < statuses.size && statuses[sj].timeMs < b) {
                val st = statuses[sj]
                any = true
                if (st.chargerOff || st.source == Source.NO_POWER) off = true
                if (st.source == Source.KAHRABA) kahraba++
                if (st.source == Source.MOTEUR) moteur++
                sj++
            }
            if (!any) {
                val prev = statuses.getOrNull(si - 1)
                val next = statuses.getOrNull(si)
                // Inside a run of once-a-second rows (slices shorter than a second), or just after the
                // latest row (the live edge): use the one before.
                if (prev != null && (next?.timeMs ?: a) - prev.timeMs <= GAP_MS) {
                    any = true
                    if (prev.chargerOff || prev.source == Source.NO_POWER) off = true
                    if (prev.source == Source.KAHRABA) kahraba++
                    if (prev.source == Source.MOTEUR) moteur++
                }
            }
            if (!any) {
                out += Slice(false, null, null, null, null)
                continue
            }
            val shade = when {
                off -> Source.NO_POWER
                kahraba == 0 && moteur == 0 -> null
                kahraba >= moteur -> Source.KAHRABA
                else -> Source.MOTEUR
            }
            if (off) {
                out += Slice(true, shade, null, null, null)
                continue
            }

            // Light: the reading in force at the slice start, then each one inside it.
            val held = readings.getOrNull(ri - 1)
            var lo = Double.MAX_VALUE
            var hi = -Double.MAX_VALUE
            var weighted = 0.0
            var covered = 0L
            var cur = held?.lux
            var curFrom = a
            var rj = ri
            while (rj < readings.size && readings[rj].timeMs < b) {
                val r = readings[rj]
                cur?.let { v ->
                    lo = minOf(lo, v); hi = maxOf(hi, v)
                    weighted += v * (r.timeMs - curFrom); covered += r.timeMs - curFrom
                }
                cur = r.lux
                curFrom = r.timeMs
                rj++
            }
            cur?.let { v ->
                lo = minOf(lo, v); hi = maxOf(hi, v)
                weighted += v * (b - curFrom); covered += b - curFrom
            }
            if (lo == Double.MAX_VALUE) {
                out += Slice(true, shade, null, null, null)
            } else {
                val mean = if (covered > 0) weighted / covered else (lo + hi) / 2
                out += Slice(true, shade, lo, hi, mean)
            }
        }
        return out
    }

    /**
     * What the app called, corrected by its own switch log: a kahraba is announced a few minutes
     * after it came on, but the log dates it from when it came on, so the graph shows it from there.
     * [switches] are (time, source) pairs sorted by time.
     */
    fun relabel(statuses: List<Status>, switches: List<Pair<Long, Source>>): List<Status> {
        // A stop (UNKNOWN) ends what came before: after it the saved rows speak for themselves.
        val known = switches.filter { it.second != Source.POWER_ON }
        if (known.isEmpty()) return statuses
        var j = -1
        return statuses.map { st ->
            while (j + 1 < known.size && known[j + 1].first <= st.timeMs) j++
            val s = known.getOrNull(j)?.second
            if (s == null || s == Source.UNKNOWN || s == st.source || st.source == Source.UNKNOWN || st.source == Source.POWER_ON) st
            else Status(st.timeMs, s, st.chargerOff)
        }
    }

    /** Joins saved rows with the newer live ones: live rows only after the last saved one. */
    fun <T> merge(saved: List<T>, live: List<T>, timeOf: (T) -> Long): List<T> {
        val last = saved.lastOrNull()?.let(timeOf) ?: Long.MIN_VALUE
        return saved + live.filter { timeOf(it) > last }
    }
}
