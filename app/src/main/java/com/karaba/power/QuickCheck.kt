package com.karaba.power

/**
 * A one-off check under a halogen bulb that stops as soon as it is sure.
 *
 * - Any wobble above the limit -> moteur, straight away (usually within a few seconds).
 * - Steady light for [STEADY_NEEDED_MS] -> "no flicker": probably kahraba, but not proof. A busy
 *   moteur wobbles within seconds, but at night a calm moteur stayed steady for 19 minutes, and
 *   the Pixel's sensor only reports changes of about 8% or more.
 * - After [MAX_MS] with neither -> can't tell (too dark, or the phone was moved).
 */
class QuickCheck(private val thresholdPct: Double, private val minLux: Double) {

    /** KAHRABA is kept for older saved results and user corrections; a check itself never claims it. */
    enum class Verdict { MOTEUR, KAHRABA, NO_FLICKER, CANT_TELL }

    /** [lux] is the typical light level during the check, kept with the result for tuning. */
    data class Result(val verdict: Verdict, val detail: String, val lux: Double? = null)

    private val samples = ArrayList<Pair<Long, Double>>()
    private var startMs = -1L
    private var steadySinceMs = -1L
    private var maxSwing = 0.0
    private var lastEvalMs = -1L
    private var darkSeen = false
    private var darkCount = 0
    private var lastMed: Double? = null

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
                        return Result(
                            Verdict.MOTEUR,
                            "Light wobbled ${"%.0f".format(swing)}% after ${(tMs - startMs) / 1000}s. " +
                                "Kahraba doesn't do that.",
                            med,
                        )
                    }
                    if (steadySinceMs < 0) steadySinceMs = tMs - PowerDetector.FLICKER_WINDOW_MS
                    if (tMs - steadySinceMs >= STEADY_NEEDED_MS) {
                        return Result(
                            Verdict.NO_FLICKER,
                            "No wobble in ${STEADY_NEEDED_MS / 1000}s. Probably kahraba – but a calm moteur " +
                                "(often at night) can look the same. Check the lamp post or your voltage meter to be sure.",
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
        const val MAX_MS = 40_000L
        /** Seconds of too-dim light before giving up. */
        const val DIM_GIVE_UP = 3
    }
}
