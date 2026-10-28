package com.kahraba.power

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * Updates without internet access: "Check for updates" opens the download page in the browser,
 * and a month after a version was installed the main screen suggests checking.
 */
object Updates {
    const val PAGE = "https://github.com/btpe831/Kahraba/releases/latest"
    const val REMIND_AFTER_DAYS = 30
    const val LATER_DAYS = 14
    private const val DAY_MS = 86_400_000L

    private const val SEEN_VERSION = "update_seen_version"
    private const val SEEN_AT = "update_seen_at"
    private const val SNOOZE_UNTIL = "update_snooze_until"

    fun version(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"

    /** Whether to suggest checking: this version has been here a month, and it wasn't put off. */
    fun reminderDue(ctx: Context, now: Long = System.currentTimeMillis()): Boolean {
        val sp = Prefs.sp(ctx)
        val v = version(ctx)
        if (sp.getString(SEEN_VERSION, null) != v) {
            // A new version (or first run): start counting from today.
            sp.edit().putString(SEEN_VERSION, v).putLong(SEEN_AT, now).remove(SNOOZE_UNTIL).apply()
            return false
        }
        return isDue(sp.getLong(SEEN_AT, now), sp.getLong(SNOOZE_UNTIL, 0), now)
    }

    fun isDue(seenAt: Long, snoozeUntil: Long, now: Long): Boolean =
        now - seenAt >= REMIND_AFTER_DAYS * DAY_MS && now >= snoozeUntil

    /** Don't suggest checking again for [days]. */
    fun snooze(ctx: Context, days: Int, now: Long = System.currentTimeMillis()) {
        Prefs.sp(ctx).edit().putLong(SNOOZE_UNTIL, now + days * DAY_MS).apply()
    }

    /** Opens the download page in the browser, and puts the reminder off for a month. */
    fun open(activity: Activity) {
        snooze(activity, REMIND_AFTER_DAYS)
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PAGE))) }.onFailure {
            Toast.makeText(activity, "No browser found. The download page is $PAGE", Toast.LENGTH_LONG).show()
        }
    }
}
