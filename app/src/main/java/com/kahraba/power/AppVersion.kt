package com.kahraba.power

/**
 * Version numbers and release notes for in-app updates (1.5.13). Pure Kotlin, so it can be unit
 * tested; the download itself is in [Updates].
 */
object AppVersion {
    /** "1.5.12" -> [1, 5, 12]; null for anything else (e.g. a test build's "1.5.12 test 3"). */
    fun parts(v: String): List<Int>? {
        val p = v.trim().split('.')
        if (p.isEmpty()) return null
        return p.map { it.toIntOrNull() ?: return null }
    }

    /** Whether [candidate] is a newer version than [current] (1.5.0 < 1.5.01 < 1.5.12 < 1.6.0). */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parts(candidate) ?: return false
        val b = parts(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** The version in a release's APK name, "KahrabaOrNot-1.5.13.apk" -> "1.5.13", or null. */
    fun fromApkName(name: String): String? =
        Regex("""^KahrabaOrNot-(\d+(?:\.\d+)+)\.apk$""").find(name.trim())?.groupValues?.get(1)

    /** The "What's new" part of a release's notes: the lines under its heading, up to "## Install". */
    fun notesFrom(body: String): String {
        val text = body.replace("\r\n", "\n")
        val head = text.indexOf("## What's new")
        val start = if (head < 0) 0 else text.indexOf('\n', head).let { if (it < 0) text.length else it + 1 }
        val end = text.indexOf("\n## Install", start).let { if (it < 0) text.length else it }
        return text.substring(start, end).trim()
    }
}
