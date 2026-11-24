package com.kahraba.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kahraba at night with a switch-over flicker shallower than 15% (1.5.22, [PowerDetector.SMALL_DIP]).
 *
 * 24 Nov (from 18:00 on 23 Nov, ms from then): a night moteur ~4,400-5,450 lux; at 4:40:24 AM the light dipped
 * 12.8% for a split second and came back 9% brighter, then calm, until the 6:28 cut (228 V at 6:14 with 8.3 A in
 * use, confirmed in the street). 1.5.21 missed it.
 *
 * 22 Nov (from 03:00, ms from then): moteur; at 5:00:29 AM a ~13% dip, then 3,200-3,400 lux dead still for an
 * hour until the 6:05 cut, the moteur after it wobbling at ~2,600-2,800. Also missed.
 */
class SmallFlickerKahrabaTest {
    private fun replay(name: String, originHour: Int, start: Long, end: Long, cut: LongRange): List<Pair<Transition, Long>> {
        val rows = javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readLines().drop(1)
            .filter { it.isNotBlank() }.map { it.split(",") }.map { it[0].toLong() to it[1].toDouble() }
        val d = PowerDetector(hourOf = recordingClock(originHour))
        d.setManual(start, Source.MOTEUR)
        val out = mutableListOf<Pair<Transition, Long>>()
        var i = rows.indexOfFirst { it.first > start }
        var lux = rows[i - 1].second
        var t = start
        while (t <= end) {
            while (i < rows.size && rows[i].first <= t) { lux = rows[i].second; d.addReading(t, lux); i++ }
            d.tick(t, lux, t !in cut).filter { !it.manual }.forEach { out += it to t }
            d.takeLightOff()
            t += 200
        }
        return out
    }

    private fun hms(h: Int, m: Int, s: Int = 0) = ((h * 60L + m) * 60 + s) * 1000

    @Test
    fun nightKahrabaWithASmallFlickerIsFound() {
        // 24 Nov: times from 18:00 on 23 Nov.
        val at = { h: Int, m: Int, s: Int -> hms(h + 6, m, s) }
        val out = replay("nov24-pixel2-raw-night-kahraba-small-flicker.csv", 18, hms(0, 15), at(7, 20, 0),
            at(6, 28, 44) until at(6, 29, 56))
        assertEquals("switches: $out", listOf(Source.KAHRABA, Source.MOTEUR), out.map { it.first.to })
        val (tr, announced) = out[0]
        assertTrue("dated ${tr.timeMs}", tr.timeMs in at(4, 40, 20)..at(4, 40, 30))
        assertTrue(tr.reason, tr.reason.startsWith("Light flickered"))
        assertTrue("announced $announced", announced in at(4, 43, 0)..at(4, 48, 0))
    }

    @Test
    fun dawnKahrabaWithASmallFlickerIsFound() {
        // 22 Nov: times from 03:00.
        val at = { h: Int, m: Int, s: Int -> hms(h - 3, m, s) }
        val out = replay("nov22-pixel2-raw-dawn-kahraba-small-flicker.csv", 3, at(3, 1, 0), at(6, 30, 0),
            at(6, 5, 24) until at(6, 6, 37))
        assertEquals("switches: $out", listOf(Source.KAHRABA, Source.MOTEUR), out.map { it.first.to })
        val (tr, announced) = out[0]
        assertTrue("dated ${tr.timeMs}", tr.timeMs in at(5, 0, 25)..at(5, 0, 35))
        assertTrue("announced $announced", announced in at(5, 3, 0)..at(5, 8, 0))
    }
}
