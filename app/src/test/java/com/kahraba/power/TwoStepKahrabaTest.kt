package com.kahraba.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Pixel 2 in Always-on mode, 8 Nov 21:18 to 9 Nov 21:24 (raw readings; times in ms from 21:00 on
 * 8 Nov). Kahraba 23:30 to the 01:30 cut; moteur; kahraba 10:29:46 to the 12:28 cut, arriving in two
 * steps 74 s apart (1,290 -> 1,566 lux, then 1,683): the light just before the last step was already
 * up, which looked like a creeping moteur, so 1.5.05 waited for the 45-minute stillness rule (alert
 * 12:11, dated 11:23). 1.5.06 takes a clear first step (>= 12%) as part of the switch: alert by 10:37.
 */
class TwoStepKahrabaTest {
    private fun at(day: Int, h: Int, m: Int, s: Int = 0) = ((((day - 8) * 24 + h - 21) * 60 + m) * 60 + s) * 1000L
    private val cuts = listOf(
        at(9, 1, 30, 26) until at(9, 1, 31, 37), at(9, 12, 28, 11) until at(9, 12, 29, 23),
        at(9, 16, 19, 3) until at(9, 16, 19, 14), at(9, 16, 19, 37) until at(9, 16, 20, 16),
    )

    private fun replay(): List<Pair<Transition, Long>> {
        val rows = javaClass.classLoader!!.getResourceAsStream("nov09-pixel2-raw-two-step-kahraba.csv")!!.bufferedReader()
            .readLines().drop(1).filter { it.isNotBlank() }.map { it.split(",") }.map { it[0].toLong() to it[1].toDouble() }
        val d = PowerDetector(hourOf = recordingClock(21))
        val start = at(8, 21, 18, 40)
        d.setManual(start, Source.MOTEUR)
        val out = mutableListOf<Pair<Transition, Long>>()
        var i = rows.indexOfFirst { it.first > start }
        var lux = rows[i - 1].second
        var t = start
        while (t <= at(9, 21, 24)) {
            while (i < rows.size && rows[i].first <= t) { lux = rows[i].second; d.addReading(t, lux); i++ }
            d.tick(t, lux, cuts.none { t in it }).filter { !it.manual }.forEach { out += it to t }
            d.takeLightOff()
            t += 200
        }
        return out
    }

    @Test
    fun kahrabaInTwoStepsIsCaughtQuickly() {
        val k = replay().filter { it.first.to == Source.KAHRABA }
        assertEquals("kahraba: $k", 2, k.size)
        assertTrue("${k[0]}", k[0].first.timeMs in at(8, 23, 29)..at(8, 23, 32))
        val (tr, announced) = k[1]
        assertTrue("dated ${tr.timeMs}", tr.timeMs in at(9, 10, 29)..at(9, 10, 32))
        assertTrue("announced $announced (1.5.05: 12:11)", announced <= at(9, 10, 37))
    }
}
