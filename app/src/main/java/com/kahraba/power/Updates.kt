package com.kahraba.power

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updates. Since 1.5.13 the app updates itself: once a day it asks GitHub for the latest release
 * (the same download page), and when there's a newer version, home shows "Update ready". Update
 * downloads the APK inside the app, checks it's the same app signed with the same key (and the
 * file's SHA-256 matches GitHub's), then hands it to Android's installer, which asks to confirm.
 * Test builds (app id ending .test) can't see draft releases: they keep the old monthly reminder
 * and open the download page, as every version did before 1.5.13.
 */
object Updates {
    const val PAGE = "https://github.com/btpe831/Kahraba/releases/latest"
    const val REMIND_AFTER_DAYS = 30
    const val LATER_DAYS = 14
    private const val DAY_MS = 86_400_000L

    private const val SEEN_VERSION = "update_seen_version"
    private const val SEEN_AT = "update_seen_at"
    private const val SNOOZE_UNTIL = "update_snooze_until"
    private const val LAST_CHECK = "update_last_check"
    private const val FOUND_VERSION = "update_found_version"
    private const val FOUND_URL = "update_found_url"
    private const val FOUND_SIZE = "update_found_size"
    private const val FOUND_SHA = "update_found_sha"
    private const val FOUND_NOTES = "update_found_notes"
    private const val API = "https://api.github.com/repos/btpe831/Kahraba/releases/latest"
    private const val CHECK_EVERY_MS = DAY_MS

    /** A newer version on GitHub: its number, APK link, size, SHA-256 (if given) and what's new. */
    data class Release(val version: String, val url: String, val size: Long, val sha256: String?, val notes: String)

    // Made when first used, so the plain helpers (isDue) also work in unit tests.
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** Whether this copy can update itself (not a test build). */
    fun selfUpdates(ctx: Context): Boolean = !ctx.packageName.endsWith(".test")

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

    /** The newer version found by the last check, if it's still newer than this one. */
    fun found(ctx: Context): Release? {
        val sp = Prefs.sp(ctx)
        val v = sp.getString(FOUND_VERSION, null) ?: return null
        if (!AppVersion.isNewer(v, version(ctx))) return null
        return Release(v, sp.getString(FOUND_URL, "") ?: "", sp.getLong(FOUND_SIZE, 0), sp.getString(FOUND_SHA, null),
            sp.getString(FOUND_NOTES, "") ?: "")
    }

    /** Asks GitHub once a day (when the app opens); [done] runs on the main thread if something new was found. */
    fun autoCheck(ctx: Context, done: () -> Unit) {
        if (!selfUpdates(ctx)) return
        val sp = Prefs.sp(ctx)
        val now = System.currentTimeMillis()
        if (now - sp.getLong(LAST_CHECK, 0) < CHECK_EVERY_MS) return
        sp.edit().putLong(LAST_CHECK, now).apply()
        val app = ctx.applicationContext
        Thread {
            val r = runCatching { fetchLatest() }.getOrNull() ?: return@Thread
            remember(app, r)
            if (AppVersion.isNewer(r.version, version(app))) main.post { done() }
        }.start()
    }

    /** "Check for updates": asks now and says what it found, with Update when there's a newer version. */
    fun checkNow(activity: Activity) {
        if (!selfUpdates(activity)) {
            open(activity)
            return
        }
        val waiting = MaterialAlertDialogBuilder(activity).setTitle("Checking for updates…").setCancelable(true).show()
        Thread {
            val r = runCatching { fetchLatest() }.getOrNull()
            main.post {
                runCatching { waiting.dismiss() }
                if (activity.isFinishing || activity.isDestroyed) return@post
                if (r == null) {
                    MaterialAlertDialogBuilder(activity)
                        .setTitle("Couldn't check right now")
                        .setMessage("Check your internet connection, or open the download page.")
                        .setPositiveButton("Open download page") { _, _ -> open(activity) }
                        .setNegativeButton("Close", null)
                        .show()
                    return@post
                }
                Prefs.sp(activity).edit().putLong(LAST_CHECK, System.currentTimeMillis()).apply()
                remember(activity, r)
                if (AppVersion.isNewer(r.version, version(activity))) offer(activity, r)
                else MaterialAlertDialogBuilder(activity)
                    .setTitle("You have the latest version")
                    .setMessage("Kahraba or Not ${version(activity)}.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }.start()
    }

    /** "Version X is ready", what's new in it, and Update / Later. */
    fun offer(activity: Activity, r: Release) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Version ${r.version} is ready")
            .setMessage(r.notes.ifBlank { "A newer version of the app." } + "\n\nYour data stays as it is.")
            .setPositiveButton("Update") { _, _ -> install(activity, r) }
            .setNegativeButton("Later", null)
            .show()
    }

    /** The latest release on GitHub, or null if it can't be read. Blocking: call off the main thread. */
    private fun fetchLatest(): Release? {
        val c = URL(API).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 15_000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "KahrabaOrNot-app")
            if (c.responseCode != 200) return null
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val assets = j.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val v = AppVersion.fromApkName(a.optString("name")) ?: continue
                val sha = a.optString("digest").removePrefix("sha256:").lowercase().takeIf { it.length == 64 }
                return Release(v, a.getString("browser_download_url"), a.optLong("size"), sha, AppVersion.notesFrom(j.optString("body")))
            }
            return null
        } finally {
            runCatching { c.disconnect() }
        }
    }

    private fun remember(ctx: Context, r: Release) {
        Prefs.sp(ctx).edit()
            .putString(FOUND_VERSION, r.version).putString(FOUND_URL, r.url).putLong(FOUND_SIZE, r.size)
            .putString(FOUND_SHA, r.sha256).putString(FOUND_NOTES, r.notes)
            .apply()
    }

    /**
     * Downloads [r] with a progress bar, checks it, and opens Android's installer. The first time,
     * Android needs the OK to let this app install updates ("Allow from this source").
     */
    private fun install(activity: Activity, r: Release) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            MaterialAlertDialogBuilder(activity)
                .setTitle("Allow updates")
                .setMessage("To update itself, the app needs your OK once. On the next screen, turn on " +
                    "“Allow from this source”, then come back and tap Update again.")
                .setPositiveButton("Continue") { _, _ ->
                    runCatching {
                        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        val dp = activity.resources.displayMetrics.density
        val status = TextView(activity).apply { text = "Starting…" }
        val bar = LinearProgressIndicator(activity).apply { max = 100; isIndeterminate = r.size <= 0 }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (12 * dp).toInt(), (24 * dp).toInt(), 0)
            addView(bar)
            addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (10 * dp).toInt() })
        }
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Downloading ${r.version}")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> cancelled.set(true) }
            .show()
        val app = activity.applicationContext
        Thread {
            val result = runCatching { download(app, r, { cancelled.get() }) { done -> main.post {
                if (r.size > 0) bar.setProgressCompat((done * 100 / r.size).toInt(), true)
                status.text = "%.1f of %.1f MB".format(done / 1_048_576.0, r.size / 1_048_576.0)
            } } }
            main.post {
                runCatching { dialog.dismiss() }
                if (cancelled.get() || activity.isFinishing || activity.isDestroyed) return@post
                val file = result.getOrNull()
                if (file == null) {
                    failed(activity, result.exceptionOrNull()?.message ?: "The download didn't finish.")
                    return@post
                }
                // One tap (1.5.15): Android's package installer, with no extra confirmation on Android 12 and
                // later; on older phones (or if Android insists) its confirmation screen comes up instead.
                val viaSession = runCatching { installSession(activity, file) }.isSuccess
                if (viaSession) {
                    Toast.makeText(activity, "Installing ${r.version}… the app restarts in a moment.", Toast.LENGTH_LONG).show()
                } else runCatching {
                    val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
                    activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { failed(activity, "Android's installer didn't open.") }
            }
        }.start()
    }

    /**
     * Hands the checked APK to Android's package installer. The result (and, if Android wants it, its
     * confirmation screen) comes back to [UpdateReceiver].
     */
    private fun installSession(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("update.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val done = PendingIntent.getBroadcast(ctx, id, Intent(ctx, UpdateReceiver::class.java), flags)
            session.commit(done.intentSender)
        }
    }

    /** Downloads and checks the APK. Throws with a plain reason if anything isn't right. */
    private fun download(ctx: Context, r: Release, cancelled: () -> Boolean, progress: (Long) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "KahrabaOrNot-${r.version}.apk")
        val c = URL(r.url).openConnection() as HttpURLConnection
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            c.connectTimeout = 20_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "KahrabaOrNot-app")
            if (c.responseCode != 200) throw IllegalStateException("The download page answered ${c.responseCode}.")
            var done = 0L
            var shown = 0L
            c.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) throw IllegalStateException("Cancelled.")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (done - shown >= 128 * 1024) { shown = done; progress(done) }
                    }
                }
            }
            progress(done)
            if (r.size > 0 && done != r.size) throw IllegalStateException("The download was incomplete.")
        } finally {
            runCatching { c.disconnect() }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (r.sha256 != null && sha != r.sha256) throw IllegalStateException("The download didn't match GitHub's check.")
        if (!sameAppAndKey(ctx, file)) throw IllegalStateException("The download isn't signed like this app, so it wasn't installed.")
        return file
    }

    /** The APK is this app (same id), signed with the same key as the copy installed now. */
    @Suppress("DEPRECATION")
    private fun sameAppAndKey(ctx: Context, apk: File): Boolean {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val theirs = pm.getPackageArchiveInfo(apk.path, flags) ?: return false
        if (theirs.packageName != ctx.packageName) return false
        val mine = signers(pm.getPackageInfo(ctx.packageName, flags))
        val other = signers(theirs)
        return mine.isNotEmpty() && mine == other
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        val sha = MessageDigest.getInstance("SHA-256")
        return sigs.orEmpty().map { s -> sha.digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    private fun failed(activity: Activity, why: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        MaterialAlertDialogBuilder(activity)
            .setTitle("Couldn't update in the app")
            .setMessage("$why You can download it from the download page instead.")
            .setPositiveButton("Open download page") { _, _ -> open(activity) }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Opens the download page in the browser, and puts the reminder off for a month. */
    fun open(activity: Activity) {
        snooze(activity, REMIND_AFTER_DAYS)
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PAGE))) }.onFailure {
            Toast.makeText(activity, "No browser found. The download page is $PAGE", Toast.LENGTH_LONG).show()
        }
    }
}
