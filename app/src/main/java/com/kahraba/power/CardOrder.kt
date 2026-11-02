package com.kahraba.power

/** The order of the cards in Your power, which the user can rearrange. */
object CardOrder {
    /**
     * The cards in Your power, in their default order (1.4.01: as in the mockup). The layout file
     * may list them differently; the app arranges them in this order.
     */
    val MOVABLE = listOf(
        "card_pattern", "card_recent", "card_accuracy", "card_history", "card_advice", "card_tools", "card_wobble",
        "card_facts",
    )

    /** Cards that always stay at the top: none since 1.4.01 (the room is in Tools › Rooms). */
    val FIXED = emptyList<String>()

    /** Off at first, to keep Your power simple (they can be turned on in Settings › Cards). */
    val OFF_FOR_NEW_USERS = setOf("card_history", "card_advice", "card_tools", "card_wobble", "card_facts")

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
