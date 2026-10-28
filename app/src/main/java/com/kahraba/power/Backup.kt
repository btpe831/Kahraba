package com.kahraba.power

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Back up and restore everything the app keeps (settings, checks, rooms, notes, the monitor's
 * switch log) as one JSON file. Used to move data to a new phone or keep a copy (e.g. on Google Drive).
 */
object Backup {
    private const val FORMAT = "kahraba-or-not-backup"
    private const val VERSION = 1

    /** Files (inside the app's storage) that are included besides the settings. */
    private val FILES = listOf("switch-log.csv")

    fun export(ctx: Context): String {
        val prefs = JSONObject()
        for ((key, value) in Prefs.sp(ctx).all) {
            val entry = JSONObject()
            when (value) {
                is Boolean -> entry.put("t", "b").put("v", value)
                is Int -> entry.put("t", "i").put("v", value)
                is Long -> entry.put("t", "l").put("v", value)
                is Float -> entry.put("t", "f").put("v", value.toDouble())
                is String -> entry.put("t", "s").put("v", value)
                is Set<*> -> entry.put("t", "set").put("v", JSONArray(value.map { it.toString() }))
                else -> continue
            }
            prefs.put(key, entry)
        }
        val files = JSONObject()
        for (name in FILES) {
            val f = File(ctx.filesDir, name)
            if (f.exists()) files.put(name, f.readText())
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("createdMs", System.currentTimeMillis())
            .put("prefs", prefs)
            .put("files", files)
            .toString(1)
    }

    /** How many checks a backup holds, to show before restoring. Null if it isn't a backup. */
    fun summary(json: String): String? = runCatching {
        val root = JSONObject(json)
        if (root.optString("format") != FORMAT) return null
        val prefs = root.getJSONObject("prefs")
        val checks = prefs.optJSONObject("check_history")?.optString("v").orEmpty()
            .lines().count { it.isNotBlank() }
        val rooms = prefs.optJSONObject("spots")?.optString("v").orEmpty().lines().count { it.isNotBlank() }
        val notes = prefs.keys().asSequence().count { it.startsWith("note_") }
        "$checks checks, $rooms rooms and $notes notes"
    }.getOrNull()

    /** Replaces everything in this app with the backup. Returns false if the file isn't a backup. */
    fun restore(ctx: Context, json: String): Boolean {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return false
        if (root.optString("format") != FORMAT) return false
        val prefs = root.getJSONObject("prefs")
        val edit = Prefs.sp(ctx).edit().clear()
        for (key in prefs.keys()) {
            val e = prefs.getJSONObject(key)
            when (e.getString("t")) {
                "b" -> edit.putBoolean(key, e.getBoolean("v"))
                "i" -> edit.putInt(key, e.getInt("v"))
                "l" -> edit.putLong(key, e.getLong("v"))
                "f" -> edit.putFloat(key, e.getDouble("v").toFloat())
                "s" -> edit.putString(key, e.getString("v"))
                "set" -> {
                    val arr = e.getJSONArray("v")
                    edit.putStringSet(key, (0 until arr.length()).map { arr.getString(it) }.toSet())
                }
            }
        }
        // Don't show the first-run guide again after moving.
        edit.putBoolean(Prefs.HELP_SEEN, true)
        Tour.markAllSeen(edit)
        edit.commit()
        // Old backups can carry the first version's saved defaults.
        Prefs.cleanUpOldDefaults(ctx, force = true)
        val files = root.optJSONObject("files")
        for (name in FILES) {
            val text = files?.optString(name, "") ?: ""
            val f = File(ctx.filesDir, name)
            if (text.isNotEmpty()) f.writeText(text) else f.delete()
        }
        return true
    }
}
