package com.kahraba.power

/**
 * The very first version saved its default settings on the phone as soon as Settings opened
 * (minimum brightness 15 lux, 10 minutes before saying kahraba, 1.5% flicker sensitivity).
 * Those saved values outlived the later, better defaults, and travel in backups. This finds
 * the ones that still hold those old values, so they can be removed and today's defaults apply.
 */
object OldDefaults {
    private val OLD = mapOf(
        Prefs.MIN_LUX to 15.0,
        Prefs.STEADY_MINUTES to 10.0,
        Prefs.FLICKER_THRESHOLD to 1.5,
    )

    /**
     * Keys to remove. The flicker sensitivity is kept if the monitor ever learned one
     * ([hasCalibration]), since learning can also arrive at 1.5%.
     */
    fun toRemove(saved: Map<String, String?>, hasCalibration: Boolean): List<String> =
        OLD.filter { (key, old) ->
            val v = saved[key]?.trim()?.replace(',', '.')?.toDoubleOrNull()
            v != null && v == old && !(key == Prefs.FLICKER_THRESHOLD && hasCalibration)
        }.keys.toList()
}
