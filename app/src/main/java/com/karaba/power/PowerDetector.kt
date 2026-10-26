package com.karaba.power

import kotlin.math.roundToLong
import kotlin.math.sqrt

/** Where the house's electricity is coming from. */
enum class Source { UNKNOWN, KAHRABA, MOTEUR, NO_POWER }

data class DetectorConfig(
    /** Light wobble (in %) above which a second counts as "flickering". */
    val flickerThresholdPct: Double = 3.0,
    /** Minutes the light must stay steady at a new, brighter level before calling kahraba. */
    val steadyMinutes: Double = 2.0,
    /** Minutes looked at when deciding the light is flickering (moteur). */
    val flickerMinutes: Double = 3.0,
    /**
     * Below this the light is too weak to judge: at 26 lux a check stayed perfectly steady on the
     * moteur. The halogen in the 26 Oct recording gave 570-860 lux.
     */
    val minLux: Double = 100.0,
    /**
     * How much brighter (in %) the light must get, without a blackout, to count as kahraba coming
     * back. Kahraba's higher voltage (234 V vs 213 V) made the halogen ~25-30% brighter
     * (862 vs 650-700 lux). Steadiness alone is NOT enough: on 26 Oct at night a calm moteur
     * stayed perfectly steady for 19 minutes.
     */
    val levelRisePct: Double = 12.0,
    /** Brightness rises above this are more likely another lamp being switched on. */
    val levelRiseMaxPct: Double = 60.0,
    /** Light levels learned with "It's kahraba" / "It's moteur" at this spot, if any. */
    val kahrabaLevel: Double? = null,
    val moteurLevel: Double? = null,
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
 *  - the light: halogen bulbs wobble on the moteur (any wobble -> moteur), and get brighter on
 *    kahraba's higher voltage. Kahraba is only called on positive evidence: the light got
 *    clearly brighter without a blackout, or it matches a level learned for this spot.
 *    A steady light on its own proves nothing, because a calm moteur can be steady too.
 *
 * Pure Kotlin with no Android code, so it can be unit tested. Call [tick] about 5 times a second.
 */
class PowerDetector(
    var config: DetectorConfig = DetectorConfig(),
    initialSource: Source = Source.UNKNOWN,
    initialSinceMs: Long = 0L,
) {
    private data class Sample(val t: Long, val lux: Double)
    private data class Second(val t: Long, val flicker: Double?, val level: Double?)

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

    /**
     * Every raw sensor reading. [tick] samples the latest value 5 times a second; this also keeps
     * short dips that fall between ticks, so they count as wobble.
     */
    fun addReading(nowMs: Long, lux: Double) {
        samples.addLast(Sample(nowMs, lux))
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
            record(now, null, null, "Waiting for light readings…")
            return
        }
        if (med < config.minLux) {
            record(now, null, null, "Too dim to judge – needs a halogen light nearby")
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
        record(now, swing, med, status)
        decide(now, out)
    }

    private fun record(now: Long, flicker: Double?, level: Double?, status: String) {
        flickerPct = flicker
        lightStatus = status
        seconds.addLast(Second(now, flicker, level))
        val keepMs = (maxOf(config.steadyMinutes + REFERENCE_MINUTES, config.flickerMinutes) * 60_000).roundToLong() + 10_000
        while (seconds.isNotEmpty() && seconds.first().t < now - keepMs) seconds.removeFirst()
    }

    private fun decide(now: Long, out: MutableList<Transition>) {
        val thr = config.flickerThresholdPct
        val historyMin = (now - historyStartMs) / 60_000.0
        val need = config.steadyMinutes

        if ((source == Source.MOTEUR || source == Source.UNKNOWN) && historyMin >= need && isSteady(now, need, thr)) {
            val recent = levelBetween(now - minutesMs(need), now)

            // 1) The light got clearly brighter (no blackout) and has stayed steady since.
            if (source == Source.MOTEUR && recent != null) {
                val refEnd = now - minutesMs(need)
                val ref = levelBetween(refEnd - minutesMs(REFERENCE_MINUTES), refEnd)
                // The "before" level must hold right up to the change, so a window that mixes the
                // old and new levels can't fake a moderate rise.
                val tail = levelBetween(refEnd - 30_000, refEnd, minCount = 20)
                if (ref != null && tail != null && kotlin.math.abs(tail / ref - 1) < 0.10) {
                    val rise = (recent / ref - 1) * 100
                    if (rise >= config.levelRisePct && rise <= config.levelRiseMaxPct) {
                        out += change(now, Source.KAHRABA, "Light got ${rise.toInt()}% brighter and stayed steady – kahraba is back")
                        return
                    }
                }
            }

            // 2) The steady level matches what was learned for this spot.
            val k = config.kahrabaLevel
            val m = config.moteurLevel
            if (recent != null && k != null && m != null && k > m * 1.08) {
                val mid = sqrt(k * m)
                if (recent >= mid && recent <= k * 1.25) {
                    out += change(now, Source.KAHRABA, "Light matches your kahraba level (${recent.toInt()} lux)")
                    return
                }
                if (source == Source.UNKNOWN && recent < mid && recent >= m * 0.75) {
                    out += change(now, Source.MOTEUR, "Light matches your moteur level (${recent.toInt()} lux)")
                    return
                }
            }
            if (source == Source.UNKNOWN) lightStatus = "Steady – a calm moteur looks the same, so not sure yet"
        }

        if (source == Source.UNKNOWN || (source == Source.KAHRABA && config.moteurOnFlickerAlone)) {
            val fNeed = config.flickerMinutes
            if (historyMin >= fNeed) {
                val w = window(now, fNeed)
                val valid = w.count { it.flicker != null }
                val flicks = w.count { (it.flicker ?: 0.0) > thr }
                if (valid >= 0.5 * fNeed * 60 && flicks >= maxOf(3.0, 0.05 * valid)) {
                    out += change(now, Source.MOTEUR, "Light flickering – looks like moteur")
                }
            }
        }
    }

    private fun minutesMs(minutes: Double) = (minutes * 60_000).roundToLong()

    /** Median light level over a time span, or null if too few readable seconds in it. */
    private fun levelBetween(from: Long, to: Long, minCount: Int = 30): Double? {
        val levels = seconds.filter { it.t > from && it.t <= to }.mapNotNull { it.level }
        return if (levels.size >= minCount) median(levels) else null
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
        darkStartMs = null
    }

    companion object {
        const val FLICKER_WINDOW_MS = 4_000L
        /** Minutes before the steady stretch used as the "before" brightness. */
        const val REFERENCE_MINUTES = 3.0
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
