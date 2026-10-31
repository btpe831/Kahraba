package com.kahraba.power

import android.content.Context

/**
 * The "What's new" window, shown once after an update. The notes are in assets/whats_new.txt,
 * which the download page uses too, so both say the same.
 */
object WhatsNew {
    data class Section(val version: String, val items: List<String>)

    private const val SEEN = "whats_new_seen_version"
    /** Saved by [Updates] since 1.1.01: the version opened before this one, on older installs. */
    private const val OLD_SEEN = "update_seen_version"
    const val MAX_SECTIONS = 3

    /**
     * What's new since the version opened last time, or nothing on a new install or the same
     * version. Marks this version as seen, so it shows once. Call before [Updates.reminderDue].
     */
    fun pending(ctx: Context): List<Section> {
        val sp = Prefs.sp(ctx)
        val now = Updates.version(ctx)
        val before = sp.getString(SEEN, null) ?: sp.getString(OLD_SEEN, null)
        sp.edit().putString(SEEN, now).apply()
        if (before == null || before == now) return emptyList()
        val text = runCatching { ctx.assets.open("whats_new.txt").bufferedReader().use { it.readText() } }.getOrNull()
            ?: return emptyList()
        return since(parse(text), before, now)
    }

    /** Sections newer than [before], up to [now], newest first, at most [MAX_SECTIONS]. */
    fun since(all: List<Section>, before: String, now: String): List<Section> =
        all.filter { compare(it.version, before) > 0 && compare(it.version, now) <= 0 }
            .sortedWith { a, b -> compare(b.version, a.version) }
            .take(MAX_SECTIONS)

    fun parse(text: String): List<Section> {
        val out = mutableListOf<Section>()
        var version: String? = null
        val items = mutableListOf<String>()
        fun flush() {
            version?.let { if (items.isNotEmpty()) out += Section(it, items.toList()) }
            items.clear()
        }
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("## ") -> { flush(); version = line.removePrefix("## ").trim() }
                line.startsWith("- ") && version != null -> items += line.removePrefix("- ").trim()
            }
        }
        flush()
        return out
    }

    /** Compares versions part by part: 1.0.10 < 1.1.0 < 1.1.04 < 1.2.0 < 1.2.01. */
    fun compare(a: String, b: String): Int {
        val x = a.split(".").map { it.toIntOrNull() ?: 0 }
        val y = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    /** The window's text: one version's points, or several versions under their numbers. */
    fun message(sections: List<Section>): String =
        if (sections.size == 1) sections[0].items.joinToString("\n\n") { "• $it" }
        else sections.joinToString("\n\n") { s -> "Version ${s.version}\n" + s.items.joinToString("\n") { "• $it" } }

    fun title(sections: List<Section>): String =
        if (sections.size == 1) "What's new in ${sections[0].version}" else "What's new"
}
