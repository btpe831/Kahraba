package com.kahraba.power

/** The order of the main-screen cards below the Check button, which the user can rearrange. */
object CardOrder {
    /** Cards that can be moved, in their default order (as in activity_main.xml). */
    val MOVABLE = listOf(
        "card_advice", "card_facts", "card_wobble", "card_timeline", "card_stats", "card_week",
        "card_today", "card_recent", "card_accuracy", "card_monitor", "card_amps", "card_meter",
    )

    /** Cards that always stay at the top (they can still be turned off). */
    val FIXED = listOf("card_setup", "card_room")

    /** The saved order, cleaned up: unknown keys dropped, cards added in later versions at the end. */
    fun order(saved: List<String>): List<String> {
        val known = saved.filter { it in MOVABLE }.distinct()
        return known + MOVABLE.filter { it !in known }
    }

    fun parse(text: String?): List<String> = order(text.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() })

    fun format(order: List<String>): String = order.joinToString(",")
}
