package com.kahraba.power

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
     * back. Kahraba's higher voltage made the halogen 25-30% brighter on 26 Oct (234 V vs 213 V),
     * but only 11-21% on later switches (28-29 Oct). 10% caught more of them, but on 29 Oct a busy
     * moteur crept up 10% in two small steps and would have been called kahraba, so 12%. Steadiness
     * alone is NOT enough: on
     * 26 Oct at night a calm moteur stayed perfectly steady for 19 minutes.
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
    /** While the phone is being moved, light changes mean nothing. */
    private var motionUntilMs = 0L

    fun tick(nowMs: Long, lux: Double?, isPlugged: Boolean?): List<Transition> {
        val out = mutableListOf<Transition>()
        if (historyStartMs < 0) historyStartMs = nowMs

        handleCharger(nowMs, isPlugged, out)

        if (nowMs < motionUntilMs) {
            // Power cuts are still watched through the charger; the light is ignored.
            flickerPct = null
            lightStatus = "Phone moved – paused until it's still"
            if (nowMs - lastEvalMs >= 1000) lastEvalMs = nowMs
            return out
        }

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
        if (nowMs >= motionUntilMs) samples.addLast(Sample(nowMs, lux))
    }

    /**
     * The phone moved. Pause judging the light until it has been still for [MOTION_PAUSE_MS], and
     * forget the light seen so far: from a new position the brightness isn't comparable, so a move
     * can never be mistaken for kahraba coming back.
     */
    fun onMotion(nowMs: Long) {
        motionUntilMs = nowMs + MOTION_PAUSE_MS
        resetHistory(motionUntilMs)
    }

    /** The user says which source it is (when they know for sure). */
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
            // Too short for a moteur to start (every "kahraba cut -> moteur" took 1-1.5 min): a
            // changeover, which can be the switch TO kahraba (31 Oct, 04:41: a 10 s blink). Keep
            // the light history and decide once the light has settled (see [checkBlink]).
            if (off < SHORT_BLINK_MS && source != Source.NO_POWER) {
                blink = levelBetween(offSince - minutesMs(REFERENCE_MINUTES), offSince - 2_000)?.let { Blink(now, off, it) }
                return
            }
            blink = null
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
        // Long enough to look back past the switch to the level before it.
        val keepMs = (maxOf(config.steadyMinutes + REFERENCE_MINUTES, config.flickerMinutes) * 60_000).roundToLong() +
            SWITCH_MS + 60_000
        while (seconds.isNotEmpty() && seconds.first().t < now - keepMs) seconds.removeFirst()
    }

    /** A power blink waiting to be judged by the light: when, how long, and the light before it. */
    private data class Blink(val atMs: Long, val offMs: Long, val before: Double)
    private var blink: Blink? = null

    /**
     * After a short power blink, once the light has been steady for [DetectorConfig.steadyMinutes]:
     * clearly brighter than before the blink means kahraba came back, clearly dimmer means moteur,
     * about the same means nothing changed. Returns true if it switched.
     */
    private fun checkBlink(now: Long, need: Double, thr: Double, out: MutableList<Transition>): Boolean {
        val b = blink ?: return false
        if (now - b.atMs > BLINK_DECIDE_MS) {
            blink = null
            return false
        }
        if (now - b.atMs < minutesMs(need) || !isSteady(now, need, thr)) return false
        blink = null
        val recent = levelBetween(now - minutesMs(need), now) ?: return false
        val pct = (recent / b.before - 1) * 100
        val off = formatDuration(b.offMs)
        if (pct >= BLINK_CHANGE_PCT && source != Source.KAHRABA) {
            out += change(now, Source.KAHRABA, "Power blinked ($off) and came back ${pct.toInt()}% brighter – kahraba is back")
            return true
        }
        if (pct <= -BLINK_CHANGE_PCT && source != Source.MOTEUR) {
            out += change(now, Source.MOTEUR, "Power blinked ($off) and came back ${(-pct).toInt()}% dimmer – moteur took over")
            return true
        }
        return false
    }

    private fun decide(now: Long, out: MutableList<Transition>) {
        val thr = config.flickerThresholdPct
        val historyMin = (now - historyStartMs) / 60_000.0
        val need = config.steadyMinutes
        if (checkBlink(now, need, thr, out)) return

        if ((source == Source.MOTEUR || source == Source.UNKNOWN) && historyMin >= need && isSteady(now, need, thr)) {
            val recent = levelBetween(now - minutesMs(need), now)

            // 1) The light got clearly brighter in one go (no blackout) and has stayed steady since.
            if (source == Source.MOTEUR && recent != null) {
                val ref = levelBeforeChange(recent)
                if (ref != null) {
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
            if (recent != null && k != null && m != null && k > m * QuickCheck.LEVEL_GAP) {
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

    /**
     * The steady brightness before the current one, or null if there wasn't one.
     *
     * Traces the current level back to where it began, skips the switch itself (the light can
     * step and bounce for half a minute: 28 Oct went 643 -> 680 -> 738/787 -> 738 lux), and takes
     * the level over the [REFERENCE_MINUTES] before that (a busy moteur wobbles, so it's the
     * typical level). The light must not have gone out in between: that's a power cut, handled
     * through the charger.
     */
    private fun levelBeforeChange(recent: Double): Double? {
        var start: Long? = null
        for (sec in seconds.reversed()) {
            val l = sec.level ?: break
            if (kotlin.math.abs(l / recent - 1) > SAME_LEVEL) break
            start = sec.t
        }
        if (start == null) return null
        val beforeEnd = start - SWITCH_MS
        val before = seconds.filter { it.t in (beforeEnd - minutesMs(REFERENCE_MINUTES))..beforeEnd }.mapNotNull { it.level }
        if (before.size < 0.7 * REFERENCE_MINUTES * 60) return null
        val ref = median(before) ?: return null
        // Just before the switch the light must still have been at that level: when the moteur
        // creeps up in steps (639 -> 688 -> 735 lux on 28 Oct), the last step started from a
        // different level than the minutes before it, so the steps don't add up to a fake jump.
        val tail = median(seconds.filter { it.t in (beforeEnd - 30_000)..beforeEnd }.mapNotNull { it.level }) ?: return null
        if (kotlin.math.abs(tail / ref - 1) > BEFORE_SPREAD) return null
        val during = seconds.filter { it.t in beforeEnd..start }.mapNotNull { it.level }
        if (during.any { it < 0.5 * ref }) return null
        return ref
    }

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
        /** How long the phone must be still after a move before the light is judged again. */
        const val MOTION_PAUSE_MS = 10_000L
        /** Minutes before the steady stretch used as the "before" brightness. */
        const val REFERENCE_MINUTES = 3.0
        /** Levels within this share of each other count as the same brightness (the sensor steps ~5%). */
        const val SAME_LEVEL = 0.06
        /** Time skipped around a switch, where the light can step and bounce. */
        const val SWITCH_MS = 45_000L
        /** A power cut shorter than this is a changeover, not the moteur starting (that took 61 s or more). */
        const val SHORT_BLINK_MS = 30_000L
        /** After a blink, the light must change this much (in %) to count as a different source. */
        const val BLINK_CHANGE_PCT = 5.0
        /** How long after a blink the light is still judged against the level before it. */
        const val BLINK_DECIDE_MS = 10 * 60_000L
        /** The light just before the switch must be this close to the typical "before" level. */
        const val BEFORE_SPREAD = 0.06
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
            java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(java.util.Date(ms))
    }
}
