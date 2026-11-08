package com.kahraba.power

/**
 * "Combine with this backup" (1.5.0): joins a backup into what the app already has, instead of
 * replacing it. Plain Kotlin, so it's tested on the computer. Nothing already here is removed;
 * where both have the same thing (same time), this phone's copy wins.
 */
object Combine {
    private fun time(line: String) = line.substringBefore(";").toLongOrNull()

    /** Switch logs ("timeMs;SOURCE;reason" lines): both, repeats once, in time order. */
    fun log(here: List<String>, backup: List<String>): List<String> =
        (here + backup).filter { time(it) != null }.distinct().sortedBy { time(it) }

    /** Checks (one per line, the time first): both, newest first, at most [keep]. */
    fun checks(here: String, backup: String, keep: Int = 1000): String {
        val byTime = LinkedHashMap<Long, String>()
        for (l in here.lines()) time(l)?.let { byTime.putIfAbsent(it, l) }
        for (l in backup.lines()) time(l)?.let { byTime.putIfAbsent(it, l) }
        return byTime.entries.sortedByDescending { it.key }.take(keep).joinToString("\n") { it.value }
    }

    /** Rooms (one per line): this phone's first, then the backup's new ones. */
    fun rooms(here: String, backup: String): String =
        (here.lines() + backup.lines()).map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n")

    /** A day's note: both, if they differ. */
    fun note(here: String?, backup: String?): String {
        val a = here?.trim().orEmpty()
        val b = backup?.trim().orEmpty()
        return when {
            a.isEmpty() -> b
            b.isEmpty() || a == b || a.contains(b) -> a
            else -> "$a\n\n$b"
        }
    }
}
