package com.kahraba.power

/** The order of the main-screen cards below the Check button, which the user can rearrange. */
object CardOrder {
    /** Cards that can be moved, in their default order (as in activity_main.xml). */
    val MOVABLE = listOf(
        "card_advice", "card_history", "card_recent", "card_accuracy", "card_tools", "card_facts", "card_wobble",
    )

    /** Cards that always stay at the top (they can still be turned off). */
    val FIXED = listOf("card_setup", "card_room")

    /** Off at first for new users, to keep the main screen simple (they can turn them on). */
    val OFF_FOR_NEW_USERS = setOf("card_facts", "card_wobble")

    /** Cards merged in 1.1.0: the four history cards became History, the three tool cards Tools. */
    val MERGED = mapOf(
        "card_timeline" to "card_history", "card_stats" to "card_history",
        "card_week" to "card_history", "card_today" to "card_history",
        "card_monitor" to "card_tools", "card_amps" to "card_tools", "card_meter" to "card_tools",
    )

    /**
     * The saved order, cleaned up: merged cards take the place of the first card they replaced,
     * unknown keys are dropped, and cards added in later versions go at the end.
     */
    fun order(saved: List<String>): List<String> {
        val known = saved.map { MERGED[it] ?: it }.filter { it in MOVABLE }.distinct()
        return known + MOVABLE.filter { it !in known }
    }

    fun parse(text: String?): List<String> = order(text.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() })

    fun format(order: List<String>): String = order.joinToString(",")

    /**
     * Whether a merged card starts on for someone upgrading: on if any of the cards it replaced
     * was on ([wasOn] tells for each old card; cards never switched off count as on).
     */
    fun mergedOn(newKey: String, wasOn: (String) -> Boolean): Boolean =
        MERGED.filterValues { it == newKey }.keys.any(wasOn)
}
