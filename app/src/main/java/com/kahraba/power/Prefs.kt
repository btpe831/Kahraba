package com.kahraba.power

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import java.util.Calendar
import kotlin.math.sqrt

object Prefs {
    const val FLICKER_THRESHOLD = "flicker_threshold"
    const val STEADY_MINUTES = "steady_minutes"
    const val MIN_LUX = "min_lux"
    private const val LEVEL_KAHRABA = "level_kahraba"
    private const val LEVEL_MOTEUR = "level_moteur"
    const val MOTEUR_ON_FLICKER = "moteur_on_flicker"
    const val QUIET_ENABLED = "quiet_enabled"
    const val QUIET_START = "quiet_start"
    const val QUIET_END = "quiet_end"
    const val BEEP_VOLUME = "beep_volume"
    const val AUTO_CHECK = "auto_check"
    const val HELP_SEEN = "help_seen"
    private const val SPOTS = "spots"
    private const val CURRENT_SPOT = "current_spot"
    private const val MONITOR_ROOM = "monitor_room"
    const val COLOR_THEME = "color_theme"

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
        minLux = num(ctx, MIN_LUX, 100.0),
        kahrabaLevel = monitorLevels(ctx).first,
        moteurLevel = monitorLevels(ctx).second,
        moteurOnFlickerAlone = sp(ctx).getBoolean(MOTEUR_ON_FLICKER, false),
    )

    /** Beep loudness as a share of the alarm channel's maximum: low, medium or full. */
    fun beepVolume(ctx: Context): Double = when (sp(ctx).getString(BEEP_VOLUME, "full")) {
        "low" -> 0.3
        "medium" -> 0.6
        else -> 1.0
    }

    /** The chosen theme (older theme names fall back to the default). */
    fun colorTheme(ctx: Context): String = Themes.byKey(sp(ctx).getString(COLOR_THEME, Themes.DEFAULT)).key

    fun setColorTheme(ctx: Context, key: String) {
        sp(ctx).edit().putString(COLOR_THEME, key).apply()
    }

    fun paletteStyle(ctx: Context): Int = Themes.byKey(colorTheme(ctx)).style

    /** Each theme is either light or dark, so the rest of the system UI follows it. */
    fun nightMode(ctx: Context): Int =
        if (Themes.byKey(colorTheme(ctx)).light) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES

    /** Saved spot names, e.g. "Kitchen", "Bathroom". */
    fun spots(ctx: Context): List<String> =
        sp(ctx).getString(SPOTS, "")!!.lines().filter { it.isNotBlank() }

    private fun saveSpots(ctx: Context, list: List<String>) {
        sp(ctx).edit().putString(SPOTS, list.distinct().joinToString("\n")).apply()
    }

    /** The spot checks are being done at, or "" for none. */
    fun currentSpot(ctx: Context): String =
        (sp(ctx).getString(CURRENT_SPOT, "") ?: "").takeIf { it in spots(ctx) } ?: ""

    fun setCurrentSpot(ctx: Context, name: String) {
        sp(ctx).edit().putString(CURRENT_SPOT, name).apply()
    }

    /** Spot names can't contain the characters used to store them. */
    fun cleanSpotName(raw: String) = raw.replace(";", " ").replace("\n", " ").trim().take(30)

    fun addSpot(ctx: Context, name: String) = saveSpots(ctx, spots(ctx) + name)

    fun removeSpot(ctx: Context, name: String) {
        saveSpots(ctx, spots(ctx) - name)
        if (currentSpot(ctx) == name) setCurrentSpot(ctx, "")
        if (monitorRoom(ctx) == name) setMonitorRoom(ctx, "")
    }

    fun renameSpot(ctx: Context, from: String, to: String) {
        saveSpots(ctx, spots(ctx).map { if (it == from) to else it })
        if (sp(ctx).getString(CURRENT_SPOT, "") == from) setCurrentSpot(ctx, to)
        if (sp(ctx).getString(MONITOR_ROOM, "") == from) setMonitorRoom(ctx, to)
    }

    /** Main-screen cards that can be turned on or off (key to name). */
    val CARDS = listOf(
        "card_timeline" to "Last 12 hours",
        "card_today" to "Today (calendar)",
        "card_week" to "7 days",
        "card_stats" to "Kahraba today",
        "card_wobble" to "Light wobble",
        "card_advice" to "What to run",
        "card_facts" to "Did you know?",
        "card_setup" to "Room setup guide",
        "card_room" to "Room",
        "card_recent" to "Recent checks",
        "card_accuracy" to "How right is it?",
        "card_monitor" to "Always-on monitor",
        "card_amps" to "Amps calculator",
        "card_meter" to "Light meter",
    )

    fun cardOn(ctx: Context, key: String) = sp(ctx).getBoolean(key, true)

    private const val CARD_ORDER = "card_order"

    /** The order of the movable cards (see [CardOrder]). */
    fun cardOrder(ctx: Context): List<String> = CardOrder.parse(sp(ctx).getString(CARD_ORDER, null))

    fun setCardOrder(ctx: Context, order: List<String>?) {
        if (order == null) sp(ctx).edit().remove(CARD_ORDER).apply()
        else sp(ctx).edit().putString(CARD_ORDER, CardOrder.format(order)).apply()
    }

    fun autoCheck(ctx: Context) = sp(ctx).getBoolean(AUTO_CHECK, false)

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

    /** The room the always-on monitor's phone sits in, or "" for none. */
    fun monitorRoom(ctx: Context): String =
        (sp(ctx).getString(MONITOR_ROOM, "") ?: "").takeIf { it in spots(ctx) } ?: ""

    fun setMonitorRoom(ctx: Context, name: String) {
        sp(ctx).edit().putString(MONITOR_ROOM, name).apply()
    }

    /**
     * Brightness the monitor compares against: the chosen room's learned levels, or (with no
     * room) the levels taught where the monitor phone sits.
     */
    fun monitorLevels(ctx: Context): Pair<Double?, Double?> {
        val room = monitorRoom(ctx)
        if (room.isNotEmpty()) return CheckHistory.spotLevels(ctx, room)
        return sp(ctx).getFloat(LEVEL_KAHRABA, -1f).takeIf { it > 0 }?.toDouble() to
            sp(ctx).getFloat(LEVEL_MOTEUR, -1f).takeIf { it > 0 }?.toDouble()
    }

    /** Remembers the light level where the monitor sits (no room chosen) for a confirmed source. */
    fun saveLevel(ctx: Context, source: Source, lux: Double) {
        val key = if (source == Source.KAHRABA) LEVEL_KAHRABA else LEVEL_MOTEUR
        sp(ctx).edit().putFloat(key, lux.toFloat()).apply()
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
    /**
     * Removes settings still holding the very first version's saved defaults (see [OldDefaults]).
     * Runs once on each phone, and again after restoring a backup ([force]).
     */
    fun cleanUpOldDefaults(ctx: Context, force: Boolean = false) {
        val p = sp(ctx)
        if (!force && p.getBoolean(OLD_DEFAULTS_CLEANED, false)) return
        val saved = listOf(MIN_LUX, STEADY_MINUTES, FLICKER_THRESHOLD).associateWith { key ->
            runCatching { p.getString(key, null) }.getOrNull()
        }
        val calibrated = p.contains(CAL_KAHRABA) || p.contains(CAL_MOTEUR)
        val edit = p.edit()
        for (key in OldDefaults.toRemove(saved, calibrated)) edit.remove(key)
        edit.putBoolean(OLD_DEFAULTS_CLEANED, true).apply()
    }

    private const val OLD_DEFAULTS_CLEANED = "old_defaults_cleaned"

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
