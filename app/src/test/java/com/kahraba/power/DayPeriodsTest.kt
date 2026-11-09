package com.kahraba.power

import org.junit.Assert.assertEquals
import org.junit.Test

/** A day's kahraba periods for the Kahraba pattern's day details (1.5.04). */
class DayPeriodsTest {
    private val h = 3_600_000L
    private fun e(t: Long, s: Source, r: String = "x") = EventLog.Entry(t, s, r)

    @Test
    fun periodsOfOneDayClippedToIt() {
        val log = listOf(
            e(23 * h, Source.KAHRABA), // the evening before, into this day
            e(25 * h, Source.MOTEUR, "Power cut (1m 10s) – moteur took over"),
            e(30 * h, Source.KAHRABA),
            e(32 * h, Source.MOTEUR, "Power cut (1m 12s) – moteur took over"),
            e(41 * h, Source.KAHRABA),
        )
        val day = 24 * h until 48 * h
        val now = 42 * h
        val p = KahrabaPattern.kahrabaPeriods(log, day.first, day.last + 1, now, running = true)
        assertEquals(listOf(24 * h to 25 * h, 30 * h to 32 * h, 41 * h to 42 * h), p.map { it.start to it.end })
        assertEquals(18 * h, KahrabaPattern.recordedMs(log, day.first, day.last + 1, now, running = true))
    }
}
