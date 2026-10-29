package com.kahraba.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardOrderTest {
    @Test
    fun nothingSavedGivesTheDefault() {
        assertEquals(CardOrder.MOVABLE, CardOrder.parse(null))
        assertEquals(CardOrder.MOVABLE, CardOrder.parse(""))
    }

    @Test
    fun savedOrderIsKeptAndRoundTrips() {
        val moved = listOf("card_tools") + CardOrder.MOVABLE.filter { it != "card_tools" }
        assertEquals(moved, CardOrder.parse(CardOrder.format(moved)))
    }

    @Test
    fun unknownAndDuplicateKeysAreDroppedAndMissingOnesAdded() {
        val order = CardOrder.parse("card_recent,card_gone,card_recent,card_room")
        assertEquals("card_recent", order.first())
        assertEquals(CardOrder.MOVABLE.toSet(), order.toSet())
        assertEquals(CardOrder.MOVABLE.size, order.size)
    }

    @Test
    fun mergedCardsTakeThePlaceOfTheOldOnes() {
        // An order saved by 1.0.x: the meter first, then the calendar, then the facts.
        val order = CardOrder.parse("card_meter,card_today,card_facts,card_timeline,card_amps,card_advice")
        assertEquals(listOf("card_tools", "card_history", "card_facts", "card_advice"), order.take(4))
        assertEquals(CardOrder.MOVABLE.toSet(), order.toSet())
    }

    @Test
    fun mergedCardIsOnIfAnyOldOneWas() {
        val off = setOf("card_timeline", "card_stats", "card_week")
        assertTrue(CardOrder.mergedOn("card_history") { it !in off }) // the calendar was still on
        assertFalse(CardOrder.mergedOn("card_history") { it !in off + "card_today" })
    }
}
