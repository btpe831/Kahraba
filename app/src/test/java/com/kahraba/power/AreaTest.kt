package com.kahraba.power

import org.junit.Assert.assertEquals
import org.junit.Test

class AreaTest {
    @Test
    fun labels() {
        assertEquals("", Area.label("", "Bhamdoun"))
        assertEquals("Aley", Area.label("Aley", ""))
        assertEquals("Aley", Area.label("Aley", "aley"))
        assertEquals("Aley · Bhamdoun", Area.label("Aley", "Bhamdoun"))
    }

    @Test
    fun townIsTidied() {
        assertEquals("Souk El Gharb", Area.cleanTown("  Souk   El\tGharb "))
        assertEquals(Area.TOWN_MAX, Area.cleanTown("x".repeat(100)).length)
    }
}
