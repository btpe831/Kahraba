package com.kahraba.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KahrabaPatternTest {
    private val h = 3_600_000L
    private fun e(hours: Double, s: Source, reason: String = "x") = EventLog.Entry((hours * h).toLong(), s, reason)
    private val cut = "Power cut (1m 14s) – moteur took over"
    private val back = "Light got 20% brighter and stayed steady – kahraba is back"

    /** Kahraba every 9 hours for 2h 30m, with Always-on mode running all along. */
    private val regular = listOf(
        e(0.0, Source.MOTEUR, KahrabaPattern.MANUAL),
        e(1.0, Source.KAHRABA, back), e(3.5, Source.MOTEUR, cut),
        e(10.0, Source.KAHRABA, back), e(12.5, Source.MOTEUR, cut),
        e(19.0, Source.KAHRABA, back), e(21.5, Source.MOTEUR, cut),
        e(28.0, Source.KAHRABA, back), e(30.5, Source.MOTEUR, cut),
    )

    @Test
    fun learnsLengthAndGapAndEstimatesTheNext() {
        val s = KahrabaPattern.summary(regular, (31 * h), running = true)
        assertEquals(150 * 60_000L, s.typicalMs)
        assertEquals(9 * h, s.gapMs)
        assertEquals(37 * h, s.nextFromMs)
        assertEquals(37 * h, s.nextToMs)
        assertNull(s.onSinceMs)
    }

    @Test
    fun whileKahrabaIsOnThereIsNoNextEstimate() {
        val s = KahrabaPattern.summary(regular.dropLast(1), (29 * h), running = true)
        assertEquals(28 * h, s.onSinceMs)
        assertNull(s.nextFromMs)
    }

    @Test
    fun notEnoughData() {
        val s = KahrabaPattern.summary(regular.take(5), 13 * h, running = true)
        assertNull(s.typicalMs)
        assertNull(s.gapMs)
        assertEquals(2, s.lengths)
        assertEquals(1, s.gaps)
    }

    @Test
    fun gapsAcrossAStopOrASetByYouStartDoNotCount() {
        val log = listOf(
            e(1.0, Source.KAHRABA, back), e(3.0, Source.MOTEUR, cut),
            e(5.0, Source.UNKNOWN, "Always-on mode stopped"),
            e(7.0, Source.KAHRABA, KahrabaPattern.MANUAL), e(9.0, Source.MOTEUR, cut),
            e(16.0, Source.KAHRABA, back), e(18.0, Source.MOTEUR, cut),
            e(25.0, Source.KAHRABA, back),
        )
        val s = KahrabaPattern.summary(log, 26 * h, running = true)
        assertEquals(1, s.gaps) // only 16 -> 25; the stop breaks the first, the manual start isn't a start
        assertEquals(2, s.lengths)
    }

    @Test
    fun aCorrectedKahrabaIsLeftOut() {
        // 1 Nov: the app said kahraba at 18:37, the owner tapped "It's moteur" at 18:51.
        val log = listOf(
            e(16.0, Source.KAHRABA, back), e(18.0, Source.MOTEUR, cut),
            e(18.6, Source.KAHRABA, "Light matches your kahraba level (4102 lux)"),
            e(18.85, Source.MOTEUR, KahrabaPattern.MANUAL),
        )
        val s = KahrabaPattern.summary(log, 19 * h, running = true)
        assertEquals(1, s.lengths)
        assertEquals(0, s.gaps)
        val spans = KahrabaPattern.spans(log, 19 * h, running = true)
        assertEquals(Source.MOTEUR, spans.first { it.start == (18.6 * h).toLong() }.source)
    }

    @Test
    fun irregularGapsGiveAWindowAndAPassedEstimateIsDropped() {
        val log = listOf(
            e(0.0, Source.KAHRABA, back), e(2.0, Source.MOTEUR, cut),
            e(6.0, Source.KAHRABA, back), e(8.0, Source.MOTEUR, cut),
            e(16.0, Source.KAHRABA, back), e(18.0, Source.MOTEUR, cut),
            e(25.0, Source.KAHRABA, back), e(27.0, Source.MOTEUR, cut),
        )
        val s = KahrabaPattern.summary(log, 28 * h, running = true)
        assertEquals(31 * h, s.nextFromMs) // 25 + 6
        assertEquals(35 * h, s.nextToMs)   // 25 + 10
        assertNull(KahrabaPattern.summary(log, 36 * h, running = true).nextFromMs)
    }
}

class KahrabaOverviewTest {
    private val h = 3_600_000L
    private val zone = java.util.TimeZone.getTimeZone("GMT+2")
    private fun at(d: Int, hh: Int, mm: Int) = java.util.Calendar.getInstance(zone).apply {
        clear(); set(2026, 10, d, hh, mm)
    }.timeInMillis
    private fun midnight(d: Int) = at(d, 0, 0)
    private val cut = "Power cut (1m 14s) – moteur took over"
    private val back = "Light got 20% brighter and stayed steady – kahraba is back"

    // The owner's real 1-2 Nov: moteur set 21:44, kahraba 01:01, cut 03:31, kahraba 09:59, cut 12:01.
    private val log = listOf(
        EventLog.Entry(at(1, 21, 44), Source.MOTEUR, KahrabaPattern.MANUAL),
        EventLog.Entry(at(2, 1, 1), Source.KAHRABA, back),
        EventLog.Entry(at(2, 3, 31), Source.MOTEUR, cut),
        EventLog.Entry(at(2, 9, 59), Source.KAHRABA, back),
        EventLog.Entry(at(2, 12, 1), Source.MOTEUR, cut),
    )

    private fun <T> inZone(block: () -> T): T {
        val old = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(zone)
        try { return block() } finally { java.util.TimeZone.setDefault(old) }
    }

    @Test
    fun todayInPlainWords() = inZone {
        val text = KahrabaPattern.today(log, at(2, 13, 0), running = true, dayStart = midnight(2))
        assertEquals("Kahraba for 4h 32m: 1:01 AM to 3:31 AM and 9:59 AM to 12:01 PM. 2 power cuts.", text)
    }

    @Test
    fun todayWhileKahrabaIsOn() = inZone {
        val text = KahrabaPattern.today(log.take(4), at(2, 10, 30), running = true, dayStart = midnight(2))
        assertEquals("Kahraba for 3h 1m: 1:01 AM to 3:31 AM and since 9:59 AM. 1 power cut.", text)
    }

    @Test
    fun stoppedTimeIsNotRecordedTime() = inZone {
        val stopped = log + EventLog.Entry(at(2, 12, 30), Source.UNKNOWN, "Always-on mode stopped")
        val text = KahrabaPattern.today(stopped, at(2, 18, 0), running = false, dayStart = midnight(2))
        assertTrue(text, text.endsWith("Always-on mode ran for 12h 30m of it."))
        assertTrue(KahrabaPattern.spans(stopped, at(2, 18, 0), running = false).none { it.end > at(2, 12, 30) })
    }

    @Test
    fun theWeekInPlainWords() = inZone {
        val days = (0 until 7).map { midnight(2 - it) }
        val text = KahrabaPattern.week(log, at(2, 13, 0), running = true, days)
        assertEquals("Kahraba for 4h 32m, 2 times, about 4h 32m a day. 2 power cuts. Always-on mode ran for 15h 16m in all.", text)
    }

    @Test
    fun nothingRecorded() = inZone {
        assertEquals("Nothing recorded today: Always-on mode wasn't running.",
            KahrabaPattern.today(emptyList(), at(2, 13, 0), running = false, dayStart = midnight(2)))
    }
}
