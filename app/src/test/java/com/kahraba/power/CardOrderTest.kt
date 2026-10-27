package com.kahraba.power

import org.junit.Assert.assertEquals
import org.junit.Test

class CardOrderTest {
    @Test
    fun nothingSavedGivesTheDefault() {
        assertEquals(CardOrder.MOVABLE, CardOrder.parse(null))
        assertEquals(CardOrder.MOVABLE, CardOrder.parse(""))
    }

    @Test
    fun savedOrderIsKeptAndRoundTrips() {
        val moved = listOf("card_meter") + CardOrder.MOVABLE.filter { it != "card_meter" }
        assertEquals(moved, CardOrder.parse(CardOrder.format(moved)))
    }

    @Test
    fun unknownAndDuplicateKeysAreDroppedAndMissingOnesAdded() {
        val order = CardOrder.parse("card_amps,card_gone,card_amps,card_room")
        assertEquals("card_amps", order.first())
        assertEquals(CardOrder.MOVABLE.toSet(), order.toSet())
        assertEquals(CardOrder.MOVABLE.size, order.size)
    }
}
