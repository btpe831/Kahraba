package com.karaba.power

import kotlin.math.roundToLong

/** Where the house's electricity is coming from. */
enum class Source { UNKNOWN, KAHRABA, MOTEUR, NO_POWER }

data class DetectorConfig(
    /** Light wobble (in %) above which a second counts as "flickering". */
    val flickerThresholdPct: Double = 3.0,
    /**
     * Minutes of steady light needed to decide kahraba is back. In the first real recording
     * (26 Oct) kahraba was perfectly steady while the moteur never stayed steady for more than
     * 20 seconds, so 2 minutes leaves a wide margin.
     */
    val steadyMinutes: Double = 2.0,
    /** Minutes looked at when deciding the light is flickering (moteur). */
    val flickerMinutes: Double = 3.0,
    /** Below this the light is too weak to judge. */
    val minLux: Double = 15.0,
    /**
     * After a cut, only call kahraba once the moteur has been seen flickering at least once
     * (or the light has been steady for 3x [steadyMinutes]). Stops a calm moteur being mistaken
     * for kahraba straight after the cut.
     */
    val requireFlickerBeforeKahraba: Boolean = true,
    /** Allow kahraba -> moteur from flicker alone, without seeing a cut. */
    val moteurOnFlickerAlone: Boolean = false,
    /** Charger off for longer than this means a real outage, not a switchover. */
    val noPowerAfterSec: Double = 300.0,
)

data class Transition(
    val timeMs: Long,
    val from: Source,
    val to: Source,
    val reason: String,
    val manual: Boolean = false,
)

/**
 * Works out the power source from two signals:
 *  - the charger: a short loss of charging is a power cut, and a cut means moteur took over;
 *  - the light: halogen bulbs wobble on moteur and stay steady on kahraba, so a long steady
 *    stretch after a cut means kahraba came back silently.
 *
 * Pure Kotlin with no Android code, so it can be unit tested. Call [tick] about 5 times a second.
 */
class PowerDetector(
    var config: DetectorConfig = DetectorConfig(),
    initialSource: Source = Source.UNKNOWN,
    initialSinceMs: Long = 0L,
) {
    private data class Sample(val t: Long, val lux: Double)
    private data class Second(val t: Long, val flicker: Double?)

    var source: Source = initialSource
        private set
    var sinceMs: Long = initialSinceMs
        private set

    /** Light wobble over the last few seconds, in %, or null when the light can't be judged. */
    var flickerPct: Double? = null
        private set
    var lightLevel: Double? = null
        private set
    var lightStatus: String = "Starting…"
        private set
    /** Set each second when [flickerPct] is updated; lets callers collect calibration data. */
    var lastEvalMs: Long = 0L
        private set

    private val samples = ArrayDeque<Sample>()
    private val seconds = ArrayDeque<Second>()
    private var historyStartMs = -1L
    /** Average light level while flickering (moteur), to report how much brighter kahraba is. */
    private var flickerLevel: Double? = null
    private var sawFlicker = false

    private var plugged: Boolean? = null
    private var everPlugged = false
    private var unpluggedAtMs: Long? = null

    private var darkStartMs: Long? = null
    private var darkBaseline = 0.0

    fun tick(nowMs: Long, lux: Double?, isPlugged: Boolean?): List<Transition> {
        val out = mutableListOf<Transition>()
        if (historyStartMs < 0) historyStartMs = nowMs

        handleCharger(nowMs, isPlugged, out)

        if (lux != null) {
            if (!everPlugged) handleLightCut(nowMs, lux, out)
            samples.addLast(Sample(nowMs, lux))
            while (samples.isNotEmpty() && samples.first().t < nowMs - SAMPLE_KEEP_MS) samples.removeFirst()
        }

        if (nowMs - lastEvalMs >= 1000) {
            lastEvalMs = nowMs
            evaluate(nowMs, out)
        }
        return out
    }

    /** The user says which source it is (e.g. from the lamp post). */
    fun setManual(nowMs: Long, to: Source): Transition {
        val t = Transition(nowMs, source, to, "Set by you", manual = true)
        source = to
        sinceMs = nowMs
        resetHistory(nowMs)
        return t
    }

    // ---- Charger ----

    private fun handleCharger(now: Long, isPlugged: Boolean?, out: MutableList<Transition>) {
        if (isPlugged == null) return
        val prev = plugged
        plugged = isPlugged
        if (isPlugged) everPlugged = true

        if (prev == true && !isPlugged) unpluggedAtMs = now

        val offSince = unpluggedAtMs
        if (!isPlugged && offSince != null && source != Source.NO_POWER &&
            now - offSince >= config.noPowerAfterSec * 1000
        ) {
            out += change(offSince, Source.NO_POWER, "Power off – no kahraba and no moteur")
        }

        if (prev == false && isPlugged && offSince != null) {
            val off = now - offSince
            unpluggedAtMs = null
            val reason = if (source == Source.NO_POWER) {
                "Power back after ${formatDuration(off)} – assuming moteur"
            } else {
                "Power cut (${formatDuration(off)}) – moteur took over"
            }
            out += change(now, Source.MOTEUR, reason)
            resetHistory(now)
        }
    }

    // ---- Light-only cut detection (used only when the phone is not on a charger) ----

    private fun handleLightCut(now: Long, lux: Double, out: MutableList<Transition>) {
        val start = darkStartMs
        if (start == null) {
            val base = median(samples.filter { it.t in (now - 20_000)..(now - 2_000) }.map { it.lux })
            if (base != null && base >= config.minLux && lux < 0.2 * base) {
                darkStartMs = now
                darkBaseline = base
            }
        } else if (lux >= 0.5 * darkBaseline) {
            darkStartMs = null
            val dark = now - start
            if (dark <= LIGHT_CUT_MAX_MS) {
                out += change(now, Source.MOTEUR, "Lights went out for ${formatDuration(dark)} – moteur took over")
                resetHistory(now)
            }
        }
    }

    // ---- Light wobble ----

    private fun evaluate(now: Long, out: MutableList<Transition>) {
        val window = samples.filter { it.t > now - FLICKER_WINDOW_MS }.map { it.lux }
        val med = median(window)
        lightLevel = med
        if (window.size < 8 || med == null) {
            record(now, null, "Waiting for light readings…")
            return
        }
        if (med < config.minLux) {
            record(now, null, "Too dark to judge – turn on a halogen light")
            return
        }
        // Max-to-min swing over the last few seconds, as % of the level. The moteur swings a lot
        // (typically 15-25%), so big swings count as flicker too; a shadow only delays a decision.
        val swing = (window.max() - window.min()) / med * 100.0
        val status = when {
            swing > DISTURBANCE_PCT -> "Light jumping a lot"
            swing > config.flickerThresholdPct -> "Light wobbling"
            else -> "Light steady"
        }
        record(now, swing, status)
        if (swing > config.flickerThresholdPct) {
            if (source == Source.MOTEUR) sawFlicker = true
            flickerLevel = if (flickerLevel == null) med else flickerLevel!! * 0.98 + med * 0.02
        }
        decide(now, out)
    }

    private fun record(now: Long, flicker: Double?, status: String) {
        flickerPct = flicker
        lightStatus = status
        seconds.addLast(Second(now, flicker))
        val keepMs = (maxOf(config.steadyMinutes * 3, config.flickerMinutes) * 60_000).roundToLong() + 10_000
        while (seconds.isNotEmpty() && seconds.first().t < now - keepMs) seconds.removeFirst()
    }

    private fun decide(now: Long, out: MutableList<Transition>) {
        val thr = config.flickerThresholdPct
        val historyMin = (now - historyStartMs) / 60_000.0

        if (source == Source.MOTEUR || source == Source.UNKNOWN) {
            val need = config.steadyMinutes
            if (historyMin >= need && isSteady(now, need, thr)) {
                val longSteady = historyMin >= need * 3 && isSteady(now, need * 3, thr)
                val allowed = source == Source.UNKNOWN || !config.requireFlickerBeforeKahraba ||
                    sawFlicker || longSteady
                if (allowed) {
                    val steadySince = steadyRunStart(now, thr)
                    val brighter = flickerLevel?.let { lvl -> lightLevel?.let { (it / lvl - 1) * 100 } }
                    val extra = brighter?.takeIf { it >= 5 }?.let { ", ${it.toInt()}% brighter" } ?: ""
                    val reason = if (source == Source.UNKNOWN) {
                        "Light steady – looks like kahraba"
                    } else {
                        "Light steady since ${formatClock(steadySince)}$extra – kahraba is back"
                    }
                    out += change(now, Source.KAHRABA, reason)
                    return
                }
            }
        }

        if (source == Source.UNKNOWN || (source == Source.KAHRABA && config.moteurOnFlickerAlone)) {
            val need = config.flickerMinutes
            if (historyMin >= need) {
                val w = window(now, need)
                val valid = w.count { it.flicker != null }
                val flicks = w.count { (it.flicker ?: 0.0) > thr }
                if (valid >= 0.5 * need * 60 && flicks >= maxOf(3.0, 0.05 * valid)) {
                    out += change(now, Source.MOTEUR, "Light flickering – looks like moteur")
                    sawFlicker = true
                }
            }
        }
    }

    private fun window(now: Long, minutes: Double): List<Second> {
        val from = now - (minutes * 60_000).roundToLong()
        return seconds.filter { it.t > from }
    }

    /** True when the last [minutes] had enough readable light and no flicker at all. */
    private fun isSteady(now: Long, minutes: Double, thr: Double): Boolean {
        val w = window(now, minutes)
        val valid = w.count { it.flicker != null }
        return valid >= 0.7 * minutes * 60 && w.none { (it.flicker ?: 0.0) > thr }
    }

    private fun steadyRunStart(now: Long, thr: Double): Long {
        var start = now
        for (s in seconds.reversed()) {
            if ((s.flicker ?: 0.0) > thr) break
            start = s.t
        }
        return start
    }

    private fun change(at: Long, to: Source, reason: String): Transition {
        val t = Transition(at, source, to, reason)
        source = to
        sinceMs = at
        return t
    }

    private fun resetHistory(now: Long) {
        samples.clear()
        seconds.clear()
        historyStartMs = now
        sawFlicker = false
        darkStartMs = null
    }

    companion object {
        const val FLICKER_WINDOW_MS = 4_000L
        const val SAMPLE_KEEP_MS = 30_000L
        const val DISTURBANCE_PCT = 25.0
        // The moteur took 75 s to come on during the 26 Oct cut.
        const val LIGHT_CUT_MAX_MS = 5 * 60_000L

        fun median(values: List<Double>): Double? {
            if (values.isEmpty()) return null
            val s = values.sorted()
            val m = s.size / 2
            return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
        }

        fun formatDuration(ms: Long): String {
            val totalSec = ms / 1000
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return when {
                h > 0 -> "${h}h ${m}m"
                m > 0 -> "${m}m ${s}s"
                else -> "${s}s"
            }
        }

        fun formatClock(ms: Long): String =
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(ms))
    }
}
