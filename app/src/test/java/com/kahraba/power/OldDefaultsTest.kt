package com.kahraba.power

import org.junit.Assert.assertEquals
import org.junit.Test

class OldDefaultsTest {
    @Test
    fun removesOnlyTheOldSavedValues() {
        val saved = mapOf(Prefs.MIN_LUX to "15", Prefs.STEADY_MINUTES to "10", Prefs.FLICKER_THRESHOLD to "1.5")
        assertEquals(setOf(Prefs.MIN_LUX, Prefs.STEADY_MINUTES, Prefs.FLICKER_THRESHOLD),
            OldDefaults.toRemove(saved, hasCalibration = false).toSet())
    }

    @Test
    fun keepsValuesTheUserChangedOrTheMonitorLearned() {
        val saved = mapOf(Prefs.MIN_LUX to "80", Prefs.STEADY_MINUTES to "2.0", Prefs.FLICKER_THRESHOLD to "1.5")
        assertEquals(emptyList<String>(), OldDefaults.toRemove(saved, hasCalibration = true))
        assertEquals(listOf(Prefs.MIN_LUX), OldDefaults.toRemove(mapOf(Prefs.MIN_LUX to "15.0"), hasCalibration = true))
        assertEquals(emptyList<String>(), OldDefaults.toRemove(emptyMap(), hasCalibration = false))
    }
}
