package com.kahraba.power

import android.content.Context

/** First-run introduction: the welcome screens, then the tour of the main screen. */
object Tour {
    private const val WELCOME_SEEN = "welcome_seen"
    private const val TOUR_SEEN = "tour_seen"

    // People who used the app before the tour existed (they saw the old guide) skip it.
    fun welcomeSeen(ctx: Context) = Prefs.sp(ctx).getBoolean(WELCOME_SEEN, Prefs.sp(ctx).getBoolean(Prefs.HELP_SEEN, false))
    fun tourSeen(ctx: Context) = Prefs.sp(ctx).getBoolean(TOUR_SEEN, Prefs.sp(ctx).getBoolean(Prefs.HELP_SEEN, false))

    fun setWelcomeSeen(ctx: Context) = Prefs.sp(ctx).edit().putBoolean(WELCOME_SEEN, true).apply()
    fun setTourSeen(ctx: Context) = Prefs.sp(ctx).edit().putBoolean(TOUR_SEEN, true).apply()

    /** Settings > Getting started guide: show the welcome and the tips again. */
    fun replay(ctx: Context) = Prefs.sp(ctx).edit().putBoolean(WELCOME_SEEN, false).putBoolean(TOUR_SEEN, false).apply()

    fun markAllSeen(edit: android.content.SharedPreferences.Editor) {
        edit.putBoolean(WELCOME_SEEN, true).putBoolean(TOUR_SEEN, true)
    }
}
