package com.karaba.power

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import java.util.Calendar
import kotlin.math.sqrt

object Prefs {
    const val FLICKER_THRESHOLD = "flicker_threshold"
    const val STEADY_MINUTES = "steady_minutes"
    const val MIN_LUX = "min_lux"
    const val REQUIRE_FLICKER = "require_flicker"
    const val MOTEUR_ON_FLICKER = "moteur_on_flicker"
    const val QUIET_ENABLED = "quiet_enabled"
    const val QUIET_START = "quiet_start"
    const val QUIET_END = "quiet_end"
    const val FORCE_VOLUME = "force_volume"

    private const val SOURCE = "state_source"
    private const val SINCE = "state_since"
    private const val MONITORING = "monitoring"
    private const val CAL_KAHRABA = "cal_kahraba"
    private const val CAL_MOTEUR = "cal_moteur"

    const val DEFAULT_THRESHOLD = 3.0

    fun sp(ctx: Context): SharedPreferences = PreferenceManager.getDefaultSharedPreferences(ctx)

    private fun num(ctx: Context, key: String, def: Double): Double =
        sp(ctx).getString(key, null)?.trim()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0 } ?: def

    fun config(ctx: Context) = DetectorConfig(
        flickerThresholdPct = num(ctx, FLICKER_THRESHOLD, DEFAULT_THRESHOLD),
        steadyMinutes = num(ctx, STEADY_MINUTES, 2.0),
        minLux = num(ctx, MIN_LUX, 15.0),
        requireFlickerBeforeKahraba = sp(ctx).getBoolean(REQUIRE_FLICKER, true),
        moteurOnFlickerAlone = sp(ctx).getBoolean(MOTEUR_ON_FLICKER, false),
    )

    fun forceVolume(ctx: Context) = sp(ctx).getBoolean(FORCE_VOLUME, true)

    var Context.monitoring: Boolean
        get() = sp(this).getBoolean(MONITORING, false)
        set(v) = sp(this).edit().putBoolean(MONITORING, v).apply()

    fun savedSource(ctx: Context): Source =
        runCatching { Source.valueOf(sp(ctx).getString(SOURCE, null)!!) }.getOrDefault(Source.UNKNOWN)

    fun savedSince(ctx: Context): Long = sp(ctx).getLong(SINCE, System.currentTimeMillis())

    fun saveSource(ctx: Context, s: Source, since: Long) {
        sp(ctx).edit().putString(SOURCE, s.name).putLong(SINCE, since).apply()
    }

    /** True if night silence is on and the time is inside the quiet hours. */
    fun isQuietNow(ctx: Context): Boolean {
        if (!sp(ctx).getBoolean(QUIET_ENABLED, false)) return false
        val start = parseClock(sp(ctx).getString(QUIET_START, "23:00")) ?: return false
        val end = parseClock(sp(ctx).getString(QUIET_END, "07:00")) ?: return false
        val c = Calendar.getInstance()
        val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return if (start <= end) now in start until end else now >= start || now < end
    }

    private fun parseClock(s: String?): Int? {
        val p = s?.trim()?.split(":", ".") ?: return null
        if (p.size != 2) return null
        val h = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    fun calibration(ctx: Context): Pair<Double?, Double?> {
        val s = sp(ctx)
        val k = s.getFloat(CAL_KAHRABA, -1f).takeIf { it >= 0 }?.toDouble()
        val m = s.getFloat(CAL_MOTEUR, -1f).takeIf { it >= 0 }?.toDouble()
        return k to m
    }

    /**
     * Saves a calibration result and sets the flicker limit between the two sources.
     * [noisePct] is the usual wobble seen on that source.
     */
    fun saveCalibration(ctx: Context, source: Source, noisePct: Double): Double {
        val key = if (source == Source.KAHRABA) CAL_KAHRABA else CAL_MOTEUR
        sp(ctx).edit().putFloat(key, noisePct.toFloat()).apply()
        val (k, m) = calibration(ctx)
        val kEff = k?.coerceAtLeast(0.2)
        val thr = when {
            kEff != null && m != null && m > kEff * 1.5 -> sqrt(kEff * m)
            kEff != null -> maxOf(DEFAULT_THRESHOLD, kEff * 2)
            m != null -> (m * 0.6).coerceIn(0.5, DEFAULT_THRESHOLD)
            else -> DEFAULT_THRESHOLD
        }
        val rounded = Math.round(thr * 100) / 100.0
        sp(ctx).edit().putString(FLICKER_THRESHOLD, rounded.toString()).apply()
        return rounded
    }
}
