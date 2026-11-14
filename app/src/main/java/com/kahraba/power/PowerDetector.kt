package com.kahraba.power

import kotlin.math.roundToLong
import kotlin.math.sqrt

/** Where the house's electricity is coming from. POWER_ON: on, source not judged (power cuts only). */
enum class Source { UNKNOWN, KAHRABA, MOTEUR, NO_POWER, POWER_ON }

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
    /**
     * Power cuts only (no halogen light): log when the power goes off and comes back, with no
     * kahraba/moteur judgement. The light is ignored and nothing is learned.
     */
    val powerCutsOnly: Boolean = false,
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
    /**
     * The local hour (0-23) of a time, for rules that only hold in the daytime. Null turns those
     * rules off (tests replaying recordings pass the recording's own clock).
     */
    private val hourOf: ((Long) -> Int)? = null,
) {
    private data class Sample(val t: Long, val lux: Double)
    private data class Second(val t: Long, val flicker: Double?, val level: Double?)

    var source: Source = initialSource
        private set
    var sinceMs: Long = initialSinceMs
        private set

    /**
     * "Change the time" on the switch that's on now (1.5.07): it really started at [newMs], not
     * [oldMs]. Only if [oldMs] is still the current start and the new time is earlier than now.
     */
    fun correctSince(oldMs: Long, newMs: Long, nowMs: Long): Boolean {
        if (sinceMs != oldMs || newMs <= 0 || newMs >= nowMs) return false
        sinceMs = newMs
        return true
    }

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
    private var lastMotionMs = -1L
    /** The charger came off because the phone moved (it fell, or was picked up): not a power cut. */
    private var unpluggedByMove = false
    /** Set when the phone moved and its charger came off; cleared by [takePhoneMoved]. */
    private var phoneMovedAlert = false

    /**
     * The light went dark while the charger stayed on: someone switched it off (or the bulb went),
     * not a power cut. When it went dark and how bright it was before; alerted once until it's back.
     */
    private var lightOffSinceMs = -1L
    private var lightOffBaseline = 0.0
    private var lightOffAlerted = false
    private var lightOffAlert = false

    /**
     * The last [LONG_KEEP_MS] of light, second by second, to remember the latest busy moteur (light
     * wobbling again and again: surely the moteur) and how bright it was. Kept through power cuts
     * (the phone hasn't moved), cleared when the phone moves.
     */
    private val longSeconds = ArrayDeque<Second>()
    /** The last time the moteur was busy, and its typical light level then. */
    private var lastBusyMs = -1L
    private var busyLevel: Double? = null
    /** When the power last came back after a cut or an outage. */
    private var lastPowerBackMs = -1L

    fun tick(nowMs: Long, lux: Double?, isPlugged: Boolean?): List<Transition> {
        val out = mutableListOf<Transition>()
        if (historyStartMs < 0) historyStartMs = nowMs

        syncMode(nowMs, isPlugged, out)
        handleCharger(nowMs, isPlugged, out)

        if (unpluggedByMove) {
            flickerPct = null
            lightStatus = PHONE_MOVED
            if (nowMs - lastEvalMs >= 1000) lastEvalMs = nowMs
            return out
        }

        if (config.powerCutsOnly) {
            flickerPct = null
            lightLevel = lux
            lightStatus = CUTS_ONLY
            if (nowMs - lastEvalMs >= 1000) lastEvalMs = nowMs
            return out
        }

        if (nowMs < motionUntilMs) {
            // Power cuts are still watched through the charger; the light is ignored.
            flickerPct = null
            lightStatus = "Phone moved – paused until it's still"
            if (nowMs - lastEvalMs >= 1000) lastEvalMs = nowMs
            return out
        }

        if (lux != null) {
            if (!everPlugged) handleLightCut(nowMs, lux, out)
            watchLightSwitch(nowMs, lux)
            samples.addLast(Sample(nowMs, lux))
            while (samples.isNotEmpty() && samples.first().t < nowMs - SAMPLE_KEEP_MS) samples.removeFirst()
        }

        if (nowMs - lastEvalMs >= 1000) {
            lastEvalMs = nowMs
            evaluate(nowMs, out)
            if (lightOffAlerted) lightStatus = LIGHT_OFF
        }
        return out
    }

    /**
     * Every raw sensor reading. [tick] samples the latest value 5 times a second; this also keeps
     * short dips that fall between ticks, so they count as wobble.
     */
    fun addReading(nowMs: Long, lux: Double) {
        if (nowMs < motionUntilMs) return
        watchSwitchFlicker(nowMs, lux)
        watchBlink(nowMs, lux)
        samples.addLast(Sample(nowMs, lux))
    }

    /** A dip under way: when it began and the light just before it. */
    private var dipAtMs = -1L
    private var dipRef = 0.0
    /** The last switch-over flicker: a short, deep dip with the light straight back (or -1). */
    private var switchFlickerMs = -1L
    /** The last switch-over flicker seen, kept after rule 1b has looked at it. */
    private var lastDipMs = -1L

    /**
     * The changeover switch flicks the light as kahraba comes on: down 15-30% for a fraction of a
     * second, then straight back (seen at most switches to kahraba, 31 Oct to 10 Nov).
     */
    private fun watchSwitchFlicker(now: Long, lux: Double) {
        if (dipAtMs < 0) {
            val last = samples.lastOrNull()?.lux ?: return
            if (lux >= last * 0.9) return
            val ref = median(samples.filter { it.t in (now - 20_000)..(now - 2_000) }.map { it.lux }) ?: return
            if (ref >= config.minLux && lux < ref * (1 - SWITCH_DIP) && lux > ref * 0.25) {
                dipAtMs = now
                dipRef = ref
            }
            return
        }
        when {
            lux <= dipRef * 0.25 || now - dipAtMs > SWITCH_DIP_MS -> dipAtMs = -1L
            lux >= dipRef * 0.95 -> {
                switchFlickerMs = dipAtMs
                lastDipMs = dipAtMs
                lastSwitchMs = dipAtMs
                lastSwitchBlink = false
                dipAtMs = -1L
            }
        }
    }

    /** A blink under way (the light gone dark): when, and the light just before it. */
    private var blinkAtMs = -1L
    private var blinkRef = 0.0
    /** The last switch moment: a switch-over flicker, or a blink too short for the charger (1.5.11). */
    private var lastSwitchMs = -1L
    /** Whether that moment was a blink (dark), which a hand on the light switch can also make. */
    private var lastSwitchBlink = false

    /**
     * The light goes dark for a second or so and comes straight back, with the charger still on: the
     * changeover switch moving (14 Nov, 8:19 AM: dark for ~1 s, then kahraba at the moteur's brightness).
     * The moteur blinks too (14 Nov, 6:13 AM), so on its own it proves nothing; see [stillAfterSwitch].
     */
    private fun watchBlink(now: Long, lux: Double) {
        if (blinkAtMs < 0) {
            val last = samples.lastOrNull()?.lux ?: return
            if (lux >= last * 0.5) return
            val ref = median(samples.filter { it.t in (now - 20_000)..(now - 2_000) }.map { it.lux }) ?: return
            if (ref >= config.minLux && lux < ref * 0.25) {
                blinkAtMs = now
                blinkRef = ref
            }
            return
        }
        when {
            now - blinkAtMs > SWITCH_BLINK_MS -> blinkAtMs = -1L
            lux >= blinkRef * 0.9 -> {
                lastSwitchMs = blinkAtMs
                lastSwitchBlink = true
                blinkAtMs = -1L
            }
        }
    }

    /** How long the light must stay still after the last switch moment: longer after a blink. */
    private fun switchStillMs() = if (lastSwitchBlink) BLINK_STILL_MS else SWITCH_STILL_MS

    /**
     * Daytime, from moteur: a switch moment, then the light completely still (no wobble at all) for
     * [SWITCH_STILL_MS] after a flicker, [BLINK_STILL_MS] after a blink (someone switching the light
     * off and on looks the same), and not dimmer than before. After a flicker or blink the moteur
     * stayed still for at most 8.5 minutes (7 Nov, 16:56); kahraba for 10 to 60 (13 and 14 Nov:
     * kahraba only ~6% brighter than the moteur, missed by every other rule).
     */
    private fun stillAfterSwitch(now: Long, thr: Double): Long? {
        val hour = hourOf ?: return null
        val sw = lastSwitchMs
        val need = switchStillMs()
        if (sw < 0 || now - sw < need + SWITCH_SETTLE_MS || now - sw > need + 2 * 60_000) return null
        if (hour(sw) !in QUIET_DAY_FROM until QUIET_DAY_TO || hour(now) !in QUIET_DAY_FROM until QUIET_DAY_TO) return null
        val after = longSeconds.filter { it.t > sw + SWITCH_SETTLE_MS }
        if (after.size < need / 1_000 * 0.9) return null
        if (after.any { (it.flicker ?: 0.0) > thr } || after.count { it.level == null } > after.size / 10) return null
        val before = median(longSeconds.filter { it.t in (sw - 5_000 - 3 * 60_000)..(sw - 5_000) }.mapNotNull { it.level }) ?: return null
        val nowLevel = median(after.mapNotNull { it.level }) ?: return null
        return if (nowLevel >= before * 0.97) sw else null
    }

    /**
     * The phone moved. Pause judging the light until it has been still for [MOTION_PAUSE_MS], and
     * forget the light seen so far: from a new position the brightness isn't comparable, so a move
     * can never be mistaken for kahraba coming back.
     */
    fun onMotion(nowMs: Long) {
        motionUntilMs = nowMs + MOTION_PAUSE_MS
        lastMotionMs = nowMs
        longSeconds.clear()
        lastBusyMs = -1L
        busyLevel = null
        resetHistory(motionUntilMs)
    }

    /**
     * True once after the phone moved and its charger came off (31 Oct: it fell off its stand and
     * the app then said "Power off"). The caller alerts the user to check the phone.
     */
    fun takePhoneMoved(): Boolean = phoneMovedAlert.also { phoneMovedAlert = false }

    /** True once when the light has been off for [LIGHT_OFF_MS] with the charger still on. */
    fun takeLightOff(): Boolean = lightOffAlert.also { lightOffAlert = false }

    /** The user says which source it is (when they know for sure). */
    fun setManual(nowMs: Long, to: Source): Transition {
        val t = Transition(nowMs, source, to, "Set by you", manual = true)
        source = to
        sinceMs = nowMs
        resetHistory(nowMs)
        return t
    }

    /**
     * Switching into power-cuts-only mode: from a source to "power on" (or off). Switching out of
     * it: "not sure yet" until the light or a cut shows which. Logged, without alerts.
     */
    private fun syncMode(now: Long, isPlugged: Boolean?, out: MutableList<Transition>) {
        val to = when {
            config.powerCutsOnly && source != Source.POWER_ON && source != Source.NO_POWER ->
                if (isPlugged == false) Source.NO_POWER else Source.POWER_ON
            !config.powerCutsOnly && source == Source.POWER_ON -> Source.UNKNOWN
            else -> return
        }
        out += Transition(now, source, to, if (config.powerCutsOnly) "Power cuts only" else "Full detection", manual = true)
        source = to
        sinceMs = now
        resetHistory(now)
    }

    // ---- Charger ----

    private fun handleCharger(now: Long, isPlugged: Boolean?, out: MutableList<Transition>) {
        if (isPlugged == null) return
        val prev = plugged
        plugged = isPlugged
        if (isPlugged) everPlugged = true

        if (prev == true && !isPlugged) {
            // Moved just before the charger came off: the phone fell or was picked up. In a power
            // cut the charger goes first; anyone picking the phone up does so after.
            if (lastMotionMs >= 0 && now - lastMotionMs <= MOVED_UNPLUG_MS) {
                unpluggedByMove = true
                phoneMovedAlert = true
                blink = null
                return
            }
            unpluggedAtMs = now
        }
        if (unpluggedByMove) {
            if (isPlugged) {
                // Back on the charger: carry on from the same source, judging the light afresh.
                unpluggedByMove = false
                unpluggedAtMs = null
                resetHistory(now)
            }
            return
        }

        if (config.powerCutsOnly) {
            if (prev == true && !isPlugged && source != Source.NO_POWER) {
                out += change(now, Source.NO_POWER, "Power went off")
            }
            if (prev == false && isPlugged) {
                val off = unpluggedAtMs?.let { now - it }
                unpluggedAtMs = null
                if (source == Source.NO_POWER) {
                    out += change(now, Source.POWER_ON, off?.let { "Power back after ${formatDuration(it)}" } ?: "Power back")
                }
            }
            return
        }

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
                pending = null
                blink = levelBetween(offSince - minutesMs(REFERENCE_MINUTES), offSince - 2_000)?.let { Blink(now, off, it) }
                return
            }
            blink = null
            lastPowerBackMs = now
            val reason = if (source == Source.NO_POWER) {
                "Power back after ${formatDuration(off)} – assuming moteur"
            } else {
                "Power cut (${formatDuration(off)}) – moteur took over"
            }
            out += change(now, Source.MOTEUR, reason)
            resetHistory(now)
        }
    }

    // ---- The light switched off, with the charger still on ----

    /**
     * In a power cut the charger goes off too. Dark light with the charger still on for
     * [LIGHT_OFF_MS] means the light itself was switched off (it happened three times on 3 Nov in
     * the washroom): alert once, so someone can switch it back on. When it's back, the light history
     * starts again, so the dark spell is never compared with the light after it.
     */
    private fun watchLightSwitch(now: Long, lux: Double) {
        if (plugged != true) {
            lightOffSinceMs = -1L
            return
        }
        if (lightOffAlerted) {
            if (lux >= 0.5 * lightOffBaseline) {
                lightOffAlerted = false
                resetHistory(now)
            }
            return
        }
        if (lightOffSinceMs < 0) {
            val base = median(samples.filter { it.t in (now - 20_000)..(now - 2_000) }.map { it.lux })
            if (base != null && base >= config.minLux && lux < 0.2 * base) {
                lightOffSinceMs = now
                lightOffBaseline = base
            }
        } else if (lux >= 0.5 * lightOffBaseline) {
            lightOffSinceMs = -1L // only a short dip
        } else if (now - lightOffSinceMs >= LIGHT_OFF_MS) {
            lightOffSinceMs = -1L
            lightOffAlerted = true
            lightOffAlert = true
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
        longSeconds.addLast(Second(now, flicker, level))
        while (longSeconds.isNotEmpty() && longSeconds.first().t < now - LONG_KEEP_MS) longSeconds.removeFirst()
        // Separate wobbles: kahraba has at most one in 5 minutes (a step in brightness), a moteur
        // three or more, even when calm (1 Nov: 222 V evening moteur).
        if (level == null) judgedSinceMs = -1L else if (judgedSinceMs < 0) judgedSinceMs = now
        if (flicker != null && flicker > config.flickerThresholdPct) {
            if (calmSeconds >= EPISODE_GAP_S) {
                wobbleEpisodes.addLast(now)
                quietEpisodes.addLast(now)
            }
            calmSeconds = 0
        } else {
            calmSeconds++
        }
        while (wobbleEpisodes.isNotEmpty() && wobbleEpisodes.first() < now - CONFIRM_MS * 2) wobbleEpisodes.removeFirst()
        while (quietEpisodes.isNotEmpty() && quietEpisodes.first() < now - QUIET_MS - 60_000) quietEpisodes.removeFirst()
        if (flicker != null && flicker > config.flickerThresholdPct) {
            // Busy: wobbling again and again within a minute (one step in brightness only shows for a
            // few seconds), and in several of the last 10 minutes: a calm evening moteur creeping up two
            // steps at once is not busy (11 Nov, 18:54: a false kahraba). Its level is the typical light
            // over the minutes before.
            val wobbles = longSeconds.count { it.t > now - 60_000 && (it.flicker ?: 0.0) > config.flickerThresholdPct }
            if (wobbles >= BUSY_WOBBLES && busyMinutes(now) >= BUSY_MINUTES) {
                lastBusyMs = now
                val levels = longSeconds.filter { it.t > now - BUSY_LEVEL_MS }.mapNotNull { it.level }
                if (levels.size >= BUSY_LEVEL_MS / 2_000) busyLevel = median(levels)
            }
        }
        // Long enough to look back past the switch to the level before it.
        val keepMs = (maxOf(config.steadyMinutes + REFERENCE_MINUTES, config.flickerMinutes) * 60_000).roundToLong() +
            SWITCH_MS + 60_000
        while (seconds.isNotEmpty() && seconds.first().t < now - keepMs) seconds.removeFirst()
    }

    /** How many of the last 10 minutes had [BUSY_WOBBLES] or more wobbling seconds. */
    private fun busyMinutes(now: Long): Int = (0 until 10).count { k ->
        val from = now - (k + 1) * 60_000L
        val to = now - k * 60_000L
        longSeconds.count { it.t > from && it.t <= to && (it.flicker ?: 0.0) > config.flickerThresholdPct } >= BUSY_WOBBLES
    }

    /** Start times of separate wobbles (after [EPISODE_GAP_S] calm seconds). */
    private val wobbleEpisodes = ArrayDeque<Long>()
    /** The same, kept for [QUIET_MS], for the daytime stillness rule. */
    private val quietEpisodes = ArrayDeque<Long>()
    /** Since when every second could be judged (not too dim, no gap): the stillness must be seen. */
    private var judgedSinceMs = -1L
    private var calmSeconds = EPISODE_GAP_S

    /**
     * Kahraba seen in the light, waiting [CONFIRM_MS] to be sure before alerting: [sinceMs] is when
     * it came on (the time shown), [foundMs] when it was spotted. A calm moteur at a high voltage can
     * look like kahraba for a few minutes, but it soon wobbles again (1 Nov, 18:37).
     */
    private data class Pending(
        val sinceMs: Long, val foundMs: Long, val reason: String,
        /** The moteur's brightest in the 10 minutes before: back down to it means still moteur. */
        val floor: Double? = null,
        val confirmMs: Long = CONFIRM_MS,
    )
    private var pending: Pending? = null

    /**
     * Light says kahraba: check it for [CONFIRM_MS] before switching. With [guard] (a brighter
     * level), the light must also stay above the moteur's brightest of the 10 minutes before, and
     * if it isn't clearly above it, the check lasts [MARGINAL_CONFIRM_MS]: on 12 Nov an evening
     * moteur stepped up to its usual top and stayed 5 minutes (17:13), and a hallway lamp was on for
     * 3 (18:10); both went back down, kahraba never does without a cut.
     */
    private fun propose(now: Long, sinceMs: Long, reason: String, guard: Boolean = false) {
        if (pending != null) return
        var floor: Double? = null
        var confirm = CONFIRM_MS
        if (guard) {
            val end = sinceMs - SWITCH_MS
            floor = longSeconds.filter { it.t in (end - FLOOR_MS)..end }.mapNotNull { it.level }.maxOrNull()
            val recent = levelBetween(now - 60_000, now)
            if (floor != null && recent != null && recent < floor * (1 + MARGINAL_PCT / 100)) confirm = MARGINAL_CONFIRM_MS
        }
        pending = Pending(minOf(sinceMs, now), now, reason, floor, confirm)
    }

    /** True while a kahraba is being confirmed (nothing else is decided meanwhile). */
    private fun checkPending(now: Long, out: MutableList<Transition>): Boolean {
        val p = pending ?: return false
        if (wobbleEpisodes.count { it > p.foundMs } >= CONFIRM_WOBBLES) {
            pending = null
            lightStatus = "Light wobbling – still moteur"
            return true
        }
        val level = seconds.lastOrNull()?.level
        if (p.floor != null && level != null && level <= p.floor) {
            pending = null
            lightStatus = "Light went back down – still moteur"
            return true
        }
        if (now - p.foundMs >= p.confirmMs) {
            pending = null
            out += change(p.sinceMs, Source.KAHRABA, p.reason)
            return true
        }
        val left = (p.confirmMs - (now - p.foundMs) + 59_999) / 60_000
        lightStatus = "Looks like kahraba – checking the light stays still ($left min)"
        return true
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
            propose(now, b.atMs, "Power blinked ($off) and came back ${pct.toInt()}% brighter – kahraba is back")
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
        if (checkPending(now, out)) return
        if (checkBlink(now, need, thr, out)) return

        if ((source == Source.MOTEUR || source == Source.UNKNOWN) && historyMin >= need && isSteady(now, need, thr)) {
            val recent = levelBetween(now - minutesMs(need), now)

            // 1) The light got clearly brighter in one go (no blackout) and has stayed steady since.
            if (source == Source.MOTEUR && recent != null) {
                val found = levelBeforeChange(recent)
                if (found != null) {
                    val (ref, start) = found
                    val rise = (recent / ref - 1) * 100
                    if (rise >= config.levelRisePct && rise <= config.levelRiseMaxPct) {
                        // A switch-over flicker at the change (or its first step, 4 Nov 12:00) is evidence enough;
                        // without it, the extra check.
                        val flickered = lastDipMs in (start - TWO_STEPS_MS)..(start + 5_000)
                        propose(now, start, "Light got ${rise.toInt()}% brighter and stayed steady – kahraba is back", guard = !flickered)
                        return
                    }
                }
            }

            // 1b) The changeover switch flickered and the light came back clearly brighter, and has
            //    stayed completely still since (10 Nov, 9:58: +11%, too little for (1)). The moteur
            //    flickers like that too, but then never came back more than ~6% brighter and still.
            val f = switchFlickerMs
            if (source == Source.MOTEUR && f >= 0 && recent != null && now - f >= minutesMs(need) + FLICKER_WINDOW_MS + 1_000) {
                val after = seconds.filter { it.t > f + FLICKER_WINDOW_MS + 1_000 }
                val steadyAfter = after.none { (it.flicker ?: 0.0) > thr } && after.count { it.flicker != null } >= 0.7 * after.size
                val before = seconds.filter { it.t in (f - 5_000 - minutesMs(REFERENCE_MINUTES))..(f - 5_000) }.mapNotNull { it.level }
                if (!steadyAfter || now - f > SWITCH_FLICKER_KEEP_MS) switchFlickerMs = -1L
                else if (before.size >= 0.7 * REFERENCE_MINUTES * 60) {
                    val rise = (recent / median(before)!! - 1) * 100
                    if (rise >= SWITCH_FLICKER_RISE_PCT && rise <= config.levelRiseMaxPct) {
                        switchFlickerMs = -1L
                        propose(now, f, "Light flickered as the power switched and came back ${rise.toInt()}% brighter – kahraba is back")
                        return
                    }
                }
            }

            // 1c) A switch moment (flicker or blink), then 15 minutes completely still: kahraba even when
            //    it's no brighter than the moteur (13 Nov 9:07, 14 Nov 8:19).
            if (source == Source.MOTEUR) {
                val sw = stillAfterSwitch(now, thr)
                if (sw != null) {
                    val minutes = switchStillMs() / 60_000
                    lastSwitchMs = -1L
                    propose(now, sw, "The power switched and the light stayed completely still for $minutes minutes – kahraba is back")
                    return
                }
            }

            // 2) The moteur was busy (wobbling), then the light went completely still and is clearly
            //    brighter than that moteur. On 31 Oct kahraba came at 13:00 in steps over 5 minutes
            //    (4090 -> 3642 lux), too slowly for (1), after half an hour of busy moteur at ~3150.
            //    The busy moteur must have stopped just before, or the power gone off in between: in
            //    the evening a moteur also calms down and creeps brighter, but slowly (30 Oct, 21:00).
            val calmFrom = now - minutesMs(CALM_MINUTES)
            val moteur = busyLevel
            if (source == Source.MOTEUR && moteur != null && lastBusyMs >= 0 && now - lastBusyMs <= BUSY_KEEP_MS &&
                historyStartMs <= calmFrom &&
                (calmFrom - lastBusyMs <= CALM_AFTER_BUSY_MS || lastPowerBackMs > lastBusyMs) &&
                isSteady(now, CALM_MINUTES, thr)
            ) {
                val calm = levelBetween(calmFrom, now)
                if (calm != null) {
                    val rise = (calm / moteur - 1) * 100
                    if (rise >= CALM_RISE_PCT && rise <= config.levelRiseMaxPct) {
                        // It came on when the wobble stopped, or when the power came back.
                        propose(
                            now, maxOf(lastBusyMs, lastPowerBackMs),
                            "Light stopped wobbling and is ${rise.toInt()}% brighter than the moteur – kahraba is back",
                        )
                        return
                    }
                }
            }

            // 2b) Daytime stillness, the last resort. In the day the moteur rarely stays still for long
            //    (24 min until 10 Nov, then 46), kahraba often for an hour; at night a calm moteur can be
            //    as still as kahraba (4 Nov), so only 7 AM-7 PM. Made for 3 Nov, 10:35 (kahraba only a
            //    little brighter), which (1b) now finds at once.
            if (source == Source.MOTEUR && quietDaytime(now)) {
                val from = now - QUIET_MS
                propose(now, from, "Light stayed still for ${QUIET_MS / 60_000} minutes – a daytime moteur doesn't – kahraba is back")
                return
            }

            // 3) Not sure yet: the steady level matches what was learned for this spot. Never from
            //    moteur: an evening moteur at 222 V reached the kahraba level (1 Nov, 18:37).
            val k = config.kahrabaLevel
            val m = config.moteurLevel
            if (source == Source.UNKNOWN && recent != null && k != null && m != null && k > m * QuickCheck.LEVEL_GAP) {
                val mid = sqrt(k * m)
                if (recent >= mid && recent <= k * 1.25) {
                    propose(now, now - minutesMs(need), "Light matches your kahraba level (${recent.toInt()} lux)")
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
     * The last [QUIET_MS], all in the daytime, all judged, with at most [QUIET_MAX_WOBBLES] separate
     * wobbles, and the light now not dimmer than in the [QUIET_REF_MS] before (the moteur it followed).
     */
    private fun quietDaytime(now: Long): Boolean {
        val hour = hourOf ?: return false
        val from = now - QUIET_MS
        // Both ends in the daytime (and it's only an hour, so the same day).
        if (hour(from) !in QUIET_DAY_FROM until QUIET_DAY_TO || hour(now) !in QUIET_DAY_FROM until QUIET_DAY_TO) return false
        if (judgedSinceMs < 0 || judgedSinceMs > from || historyStartMs > from) return false
        if (quietEpisodes.count { it > from } > QUIET_MAX_WOBBLES) return false
        val before = longSeconds.filter { it.t in (from - QUIET_REF_MS) until from }.mapNotNull { it.level }
        if (before.size < QUIET_REF_MS / 2_000) return false
        val nowLevel = median(longSeconds.filter { it.t > now - 10 * 60_000 }.mapNotNull { it.level }) ?: return false
        val ref = median(before) ?: return false
        return nowLevel >= ref
    }

    /**
     * The steady brightness before the current one, or null if there wasn't one.
     *
     * Traces the current level back to where it began, skips the switch itself (the light can
     * step and bounce for half a minute: 28 Oct went 643 -> 680 -> 738/787 -> 738 lux), and takes
     * the level over the [REFERENCE_MINUTES] before that (a busy moteur wobbles, so it's the
     * typical level). The light must not have gone out in between: that's a power cut, handled
     * through the charger. Also returns when the current level started.
     */
    private fun levelBeforeChange(recent: Double): Pair<Double, Long>? {
        var start: Long? = null
        for (sec in seconds.reversed()) {
            val l = sec.level ?: break
            // The same level, or a little brighter: kahraba can overshoot and settle down a step
            // (4 Nov, 22:00: 2,209 lux, then 2,068 a minute later and still).
            if (l < recent * (1 - SAME_LEVEL) || l > recent * (1 + SETTLE_OVERSHOOT)) break
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
        // Only a tail *above* the usual level is creep. A tail below it is the moteur flicking between
        // two neighbouring sensor steps (8 Nov, 16:00: 1,153 / 1,235 lux, then kahraba at 1,391): the
        // rise is still measured from the usual (higher) level, so that's no easier to fake.
        if (tail > ref * (1 + BEFORE_SPREAD)) return twoSteps(tail, beforeEnd, start)
        val during = seconds.filter { it.t in beforeEnd..start }.mapNotNull { it.level }
        if (during.any { it < 0.5 * ref }) return null
        return ref to start
    }

    /**
     * Kahraba can arrive in two steps a minute apart (9 Nov, 10:29: 1,290 -> 1,566 lux, then 1,683 at
     * 10:31). Just before the current level the light was then already up, which looks like a creeping
     * moteur. It isn't if that earlier step was itself a clear jump (a creeping moteur goes up ~7% at a
     * time, 28 Oct): then the change began there, and the rise is measured from before it.
     */
    private fun twoSteps(earlier: Double, beforeEnd: Long, start: Long): Pair<Double, Long>? {
        var earlierStart: Long? = null
        for (sec in seconds.reversed()) {
            if (sec.t > beforeEnd) continue
            val l = sec.level ?: return null
            if (l < earlier * (1 - SAME_LEVEL) || l > earlier * (1 + SAME_LEVEL)) break
            earlierStart = sec.t
        }
        val from = earlierStart ?: return null
        if (start - from > TWO_STEPS_MS) return null
        val end = from - SWITCH_MS
        val before = seconds.filter { it.t in (end - minutesMs(REFERENCE_MINUTES))..end }.mapNotNull { it.level }
        if (before.size < 0.7 * REFERENCE_MINUTES * 60) return null
        val ref = median(before) ?: return null
        val tail = median(seconds.filter { it.t in (end - 30_000)..end }.mapNotNull { it.level }) ?: return null
        if (tail > ref * (1 + BEFORE_SPREAD)) return null
        if ((earlier / ref - 1) * 100 < config.levelRisePct) return null
        if (seconds.filter { it.t in end..start }.mapNotNull { it.level }.any { it < 0.5 * ref }) return null
        return ref to from
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
        pending = null
        return t
    }

    private fun resetHistory(now: Long) {
        pending = null
        wobbleEpisodes.clear()
        quietEpisodes.clear()
        judgedSinceMs = -1L
        calmSeconds = EPISODE_GAP_S
        samples.clear()
        seconds.clear()
        historyStartMs = now
        darkStartMs = null
        dipAtMs = -1L
        switchFlickerMs = -1L
        lastDipMs = -1L
        blinkAtMs = -1L
        lastSwitchMs = -1L
        lastSwitchBlink = false
    }

    companion object {
        const val FLICKER_WINDOW_MS = 4_000L
        /** How long the phone must be still after a move before the light is judged again. */
        const val MOTION_PAUSE_MS = 10_000L
        /** The charger coming off this soon after the phone moved means it fell or was picked up. */
        const val MOVED_UNPLUG_MS = 10_000L
        const val PHONE_MOVED = "Phone moved – please check"
        /** Dark this long with the charger still on: the light was switched off, not a power cut. */
        const val LIGHT_OFF_MS = 30_000L
        const val LIGHT_OFF = "The light is off – please switch it back on"
        const val CUTS_ONLY = "Power cuts only – the light isn't used"
        /** Minutes of completely still light needed after a busy moteur. */
        const val CALM_MINUTES = 5.0
        /** How much brighter (in %) than the busy moteur that still light must be. */
        const val CALM_RISE_PCT = 10.0
        /** The still light must start within this long after the moteur was last busy (the switch can take a few minutes to settle). */
        const val CALM_AFTER_BUSY_MS = 5 * 60_000L
        /**
         * Kahraba seen in the light is confirmed for this long before the alert (5 min until 1.4.08; every
         * recording still came out right at 3 min, and the calm moteur of 1 Nov wobbled again within 2).
         */
        const val CONFIRM_MS = 3 * 60_000L
        /** Separate wobbles during that time that mean it was a calm moteur after all. */
        const val CONFIRM_WOBBLES = 3
        /** Calm seconds between two wobbles for them to count as separate. */
        const val EPISODE_GAP_S = 5
        /** A busy moteur is remembered this long. */
        const val BUSY_KEEP_MS = 60 * 60_000L
        /**
         * Daytime stillness (1.4.09): this long with at most [QUIET_MAX_WOBBLES] wobbles. 45 minutes
         * until 1.5.08: on 10 Nov a daytime moteur was still for 46 (9:12-9:58).
         */
        const val QUIET_MS = 60 * 60_000L
        const val QUIET_MAX_WOBBLES = 2
        /** The light before the still stretch it must not be dimmer than. */
        const val QUIET_REF_MS = 15 * 60_000L
        /** Daytime, local hours: the whole still stretch must fall in it. */
        const val QUIET_DAY_FROM = 7
        const val QUIET_DAY_TO = 19

        /** The local hour of a time, on this phone's clock. */
        fun localHour(ms: Long): Int =
            java.util.Calendar.getInstance().apply { timeInMillis = ms }.get(java.util.Calendar.HOUR_OF_DAY)
        /** Wobbling seconds within a minute that make the moteur "busy". */
        const val BUSY_WOBBLES = 10
        /**
         * …in at least this many of the last 10 minutes (1.5.09). A busy moteur: 3-10 (30 Oct, 29 Oct,
         * 31 Oct); a calm evening moteur creeping up: 1 (11 Nov).
         */
        const val BUSY_MINUTES = 3
        /** The busy moteur's level: the typical light over this long. */
        const val BUSY_LEVEL_MS = 10 * 60_000L
        /** Minutes before the steady stretch used as the "before" brightness. */
        const val REFERENCE_MINUTES = 3.0
        /** Levels within this share of each other count as the same brightness (the sensor steps ~5%). */
        const val SAME_LEVEL = 0.06
        /** How much brighter than the current level the light may have been just after the switch (1.4.10). */
        const val SETTLE_OVERSHOOT = 0.15
        /** Time skipped around a switch, where the light can step and bounce. */
        const val SWITCH_MS = 45_000L
        /** A power cut shorter than this is a changeover, not the moteur starting (that took 61 s or more). */
        const val SHORT_BLINK_MS = 30_000L
        /** After a blink, the light must change this much (in %) to count as a different source. */
        const val BLINK_CHANGE_PCT = 5.0
        /** How long after a blink the light is still judged against the level before it. */
        const val BLINK_DECIDE_MS = 10 * 60_000L
        /** The light just before the switch may be at most this much above the typical "before" level (creep). */
        const val BEFORE_SPREAD = 0.06
        /** Kahraba arriving in two steps: at most this long between them (9 Nov: 74 s). */
        const val TWO_STEPS_MS = 2 * 60_000L
        /** A blink: dark and back within this long, with the charger on (1.5.11). */
        const val SWITCH_BLINK_MS = 3_000L
        /** After a switch moment, completely still this long: kahraba (1.5.11). */
        const val SWITCH_STILL_MS = 15 * 60_000L
        /** The same after a blink, which someone switching the light off and on can also make. */
        const val BLINK_STILL_MS = 25 * 60_000L
        /** The light may settle in a step or two just after the switch (11 Nov, 13:33: 2,590 then 2,749). */
        const val SWITCH_SETTLE_MS = 10_000L
        /** A brighter level is checked against the moteur's brightest over this long before it (1.5.10). */
        const val FLOOR_MS = 10 * 60_000L
        /** Less than this much (%) above that brightest: a longer check, [MARGINAL_CONFIRM_MS]. */
        const val MARGINAL_PCT = 10.0
        const val MARGINAL_CONFIRM_MS = 10 * 60_000L
        /** How long [longSeconds] is kept: the busy moteur, and the stillness rule with its level before. */
        val LONG_KEEP_MS = maxOf(BUSY_KEEP_MS, QUIET_MS + QUIET_REF_MS + 60_000L)
        /** Switch-over flicker: the light dips at least this much (fraction)… */
        const val SWITCH_DIP = 0.15
        /** …and is back within this long. */
        const val SWITCH_DIP_MS = 1_500L
        /** Then still and at least this much brighter (%) than the 3 minutes before. */
        const val SWITCH_FLICKER_RISE_PCT = 8.0
        const val SWITCH_FLICKER_KEEP_MS = 4 * 60_000L
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
