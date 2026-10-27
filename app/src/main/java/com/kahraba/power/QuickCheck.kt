package com.kahraba.power

/**
 * A one-off check under a halogen bulb that stops as soon as it is sure.
 *
 * - Wobble above the limit that repeats ([WOBBLES_NEEDED] separate times) or keeps going (longer
 *   than [SUSTAINED_WOBBLE_MS]) -> moteur. The moteur's engine keeps hunting; a single dip is more
 *   likely an appliance switching on, which dips the voltage on kahraba too.
 * - Steady light for [STEADY_NEEDED_MS] -> "no flicker": probably kahraba, but not proof. A busy
 *   moteur wobbles within seconds, but at night a calm moteur stayed steady for 19 minutes, and
 *   the Pixel's sensor only reports changes of about 8% or more.
 * - At the user's spot, once its brightness on each source is learned ([kahrabaLevel],
 *   [moteurLevel]): 10 s of steady light at the kahraba level -> kahraba, at the moteur level ->
 *   moteur (a calm moteur is dimmer, ~213 V vs 234 V).
 * - After [MAX_MS] with neither -> can't tell (too dark, or the phone was moved).
 */
class QuickCheck(
    private val thresholdPct: Double,
    private val minLux: Double,
    private val kahrabaLevel: Double? = null,
    private val moteurLevel: Double? = null,
) {
    /** The learned levels are usable only if the two sources are clearly apart. */
    private val useLevels = kahrabaLevel != null && moteurLevel != null && kahrabaLevel > moteurLevel * LEVEL_GAP

    /** How long the light must be steady before a verdict. */
    val steadyNeededMs: Long get() = if (useLevels) STEADY_LEVEL_MS else STEADY_NEEDED_MS

    /** KAHRABA is kept for older saved results and user corrections; a check itself never claims it. */
    enum class Verdict { MOTEUR, KAHRABA, NO_FLICKER, CANT_TELL }

    /** [lux] is the typical light level during the check, kept with the result for tuning. */
    /** [byLevel]: decided by comparing brightness with the spot's learned levels. */
    data class Result(val verdict: Verdict, val detail: String, val lux: Double? = null, val byLevel: Boolean = false)

    private val samples = ArrayList<Pair<Long, Double>>()
    private var startMs = -1L
    private var steadySinceMs = -1L
    private var maxSwing = 0.0
    private var lastEvalMs = -1L
    private var darkSeen = false
    private var darkCount = 0
    private var lastMed: Double? = null
    private var inWobble = false
    private var wobbleStartMs = 0L

    /** Separate wobbles seen so far (one dip spans several overlapping windows). */
    var wobbles = 0
        private set

    /** Seconds of steady light so far, for the progress display. */
    val steadySeconds: Int
        get() = if (steadySinceMs < 0 || samples.isEmpty()) 0 else ((samples.last().first - steadySinceMs) / 1000).toInt()

    /** A raw sensor reading between samples, so short dips still count as wobble. */
    fun addRaw(tMs: Long, lux: Double) {
        if (startMs >= 0) samples += tMs to lux
    }

    /** Add a reading; returns a result once the check is sure (or out of time), else null. */
    fun add(tMs: Long, lux: Double): Result? {
        if (startMs < 0) startMs = tMs
        samples += tMs to lux
        samples.removeAll { it.first <= tMs - PowerDetector.FLICKER_WINDOW_MS }

        if (tMs - startMs >= PowerDetector.FLICKER_WINDOW_MS && tMs - lastEvalMs >= 1000) {
            lastEvalMs = tMs
            val w = samples.map { it.second }
            val med = PowerDetector.median(w)
            if (w.size >= 8 && med != null) {
                lastMed = med
                if (med < minLux) {
                    // Too dim: the sensor can't see the flicker, and a steady reading means nothing.
                    darkSeen = true
                    steadySinceMs = -1
                    if (++darkCount >= DIM_GIVE_UP) {
                        return Result(
                            Verdict.CANT_TELL,
                            "Too dim (${med.toInt()} lux). Move the phone closer to a halogen light – " +
                                "it needs at least ${minLux.toInt()} lux.",
                            med,
                        )
                    }
                } else {
                    darkCount = 0
                    val swing = (w.max() - w.min()) / med * 100
                    maxSwing = maxOf(maxSwing, swing)
                    if (swing > thresholdPct) {
                        if (!inWobble) {
                            inWobble = true
                            wobbleStartMs = tMs
                            wobbles++
                        }
                        steadySinceMs = -1
                        if (wobbles >= WOBBLES_NEEDED || tMs - wobbleStartMs >= SUSTAINED_WOBBLE_MS) {
                            return Result(
                                Verdict.MOTEUR,
                                "Light kept wobbling (up to ${"%.0f".format(maxSwing)}%) over " +
                                    "${(tMs - startMs) / 1000}s. Kahraba doesn't keep doing that.",
                                med,
                            )
                        }
                        return null
                    }
                    inWobble = false
                    if (steadySinceMs < 0) steadySinceMs = tMs - PowerDetector.FLICKER_WINDOW_MS
                    if (useLevels && tMs - steadySinceMs >= STEADY_LEVEL_MS) {
                        val k = kahrabaLevel!!
                        val m = moteurLevel!!
                        val mid = kotlin.math.sqrt(k * m)
                        val levels = "${med.toInt()} lux; this room: kahraba ${k.toInt()}, moteur ${m.toInt()}"
                        if (med >= mid && med <= k * 1.25) {
                            return Result(Verdict.KAHRABA, "Steady and as bright as kahraba in this room ($levels).", med, byLevel = true)
                        }
                        if (med < mid && med >= m * 0.75) {
                            return Result(
                                Verdict.MOTEUR,
                                "Steady but dimmer, like the moteur in this room ($levels). A calm moteur.",
                                med,
                                byLevel = true,
                            )
                        }
                        // Doesn't match either level: probably not at the spot. Fall back to waiting.
                    }
                    if (tMs - steadySinceMs >= STEADY_NEEDED_MS) {
                        val offSpot = if (useLevels) {
                            " The brightness (${med.toInt()} lux) doesn't match this room – is the phone in its usual place, with the same lights on?"
                        } else ""
                        return Result(
                            Verdict.NO_FLICKER,
                            "No wobble in ${STEADY_NEEDED_MS / 1000}s. Probably kahraba – but a calm moteur " +
                                "(often at night) can look the same. Confirm it if you know for sure.$offSpot",
                            med,
                        )
                    }
                }
            }
        }

        if (tMs - startMs >= MAX_MS) {
            return Result(
                Verdict.CANT_TELL,
                if (darkSeen) "Too dark – put the phone closer to a halogen light and try again."
                else "Not enough steady readings – keep the phone still and try again.",
                lastMed,
            )
        }
        return null
    }

    companion object {
        const val STEADY_NEEDED_MS = 25_000L
        /** Steady time needed when the spot's levels are known. */
        const val STEADY_LEVEL_MS = 10_000L
        /** Kahraba must be at least this much brighter than moteur at the spot to use levels. */
        const val LEVEL_GAP = 1.08
        const val MAX_MS = 40_000L
        /** Separate wobbles needed before calling moteur. */
        const val WOBBLES_NEEDED = 2
        /**
         * A wobble lasting this long also counts. One dip only shows for about the 4 s window plus
         * the dip itself, so 6 s of continuous wobble means it's still going.
         */
        const val SUSTAINED_WOBBLE_MS = 6_000L
        /** Seconds of too-dim light before giving up. */
        const val DIM_GIVE_UP = 3
    }
}
