package com.karaba.power

/**
 * A one-off check: hold the phone under a halogen bulb for a few seconds and get a verdict.
 *
 * Flicker seen  -> moteur (strong evidence).
 * No flicker    -> probably kahraba, but a moteur that only wobbles now and then can look steady
 *                  for a short time, so confidence depends on how long the check ran.
 */
class QuickCheck(private val thresholdPct: Double, private val minLux: Double) {

    enum class Verdict { MOTEUR, KAHRABA, CANT_TELL }

    data class Result(
        val verdict: Verdict,
        val confidence: String,
        val maxFlickerPct: Double?,
        val detail: String,
    )

    private val samples = ArrayList<Pair<Long, Double>>()

    fun add(tMs: Long, lux: Double) {
        samples += tMs to lux
    }

    fun result(): Result {
        if (samples.size < 10) {
            return Result(Verdict.CANT_TELL, "", null, "Not enough light readings.")
        }
        val start = samples.first().first
        val end = samples.last().first
        // Same measure as the monitor: max-to-min swing over each 4-second stretch.
        val swings = ArrayList<Double>()
        var dark = 0
        var t = start + PowerDetector.FLICKER_WINDOW_MS
        while (t <= end) {
            val w = samples.filter { it.first > t - PowerDetector.FLICKER_WINDOW_MS && it.first <= t }.map { it.second }
            val med = PowerDetector.median(w)
            when {
                w.size < 8 || med == null -> {}
                med < minLux -> dark++
                else -> swings += (w.max() - w.min()) / med * 100
            }
            t += 1000
        }
        if (swings.isEmpty()) {
            val why = when {
                dark > 0 -> "Too dark – hold the phone closer to a halogen light."
                else -> "Not enough light readings."
            }
            return Result(Verdict.CANT_TELL, "", null, why)
        }
        val max = swings.max()
        val seconds = (end - start) / 1000
        val flickering = swings.count { it > thresholdPct }
        return if (flickering > 0) {
            Result(
                Verdict.MOTEUR,
                if (max > thresholdPct * 2) "High" else "Medium",
                max,
                "Light wobbled up to ${"%.1f".format(max)}% (limit ${"%.1f".format(thresholdPct)}%).",
            )
        } else {
            // In the 26 Oct recording the moteur never stayed steady longer than 20 s, and a
            // random 10 s stretch of moteur looked steady only about 1-2% of the time.
            val confidence = when {
                seconds >= 25 -> "High"
                seconds >= 10 -> "Medium"
                else -> "Low"
            }
            Result(
                Verdict.KAHRABA,
                confidence,
                max,
                "Light stayed steady for ${seconds}s (max wobble ${"%.1f".format(max)}%). " +
                    "A 30-second check is more reliable.",
            )
        }
    }
}
