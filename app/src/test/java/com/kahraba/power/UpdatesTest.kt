package com.kahraba.power

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    private val day = 86_400_000L

    @Test
    fun remindsAfterAMonth() {
        assertFalse(Updates.isDue(seenAt = 0, snoozeUntil = 0, now = 29 * day))
        assertTrue(Updates.isDue(seenAt = 0, snoozeUntil = 0, now = 30 * day))
    }

    @Test
    fun laterPutsItOff() {
        assertFalse(Updates.isDue(seenAt = 0, snoozeUntil = 44 * day, now = 31 * day))
        assertTrue(Updates.isDue(seenAt = 0, snoozeUntil = 44 * day, now = 44 * day))
    }
}
