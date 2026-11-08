package com.kahraba.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Pixel 2 in Always-on mode in the washroom, 5 Nov 23:35 to 8 Nov 18:20 (raw readings; times in ms
 * from 23:00 on 5 Nov). Kahraba 6 Nov 04:00-06:00 (then 1h 30m without power) and ~15:58-18:00;
 * 7 Nov 04:59-05:27 and ~12:05-14:01 (Always-on was restarted at 11:37); 8 Nov 02:03-04:32,
 * 06:14-08:00 and 16:00-18:00. Fifteen power cuts, four of them on 7 Nov 10:33-11:36. The light was
 * switched off a few times. On 8 Nov at 16:00 kahraba came on with a clean ~13% jump, but the moteur
 * had been flicking between two sensor steps (1,153 / 1,235 lux) just before, which the "creep" check
 * took for creep: 1.4.10 only said kahraba at 16:47 (the daytime stillness rule). 1.5.0 by 16:06.
 */
class ThreeDaysTest {
    private fun at(day: Int, h: Int, m: Int, s: Int = 0) = ((((day - 5) * 24 + h - 23) * 60 + m) * 60 + s) * 1000L
    private val cuts = listOf(
        at(6, 6, 0, 27) until at(6, 7, 31, 26), at(6, 9, 56, 59) until at(6, 9, 59, 1),
        at(6, 10, 16, 58) until at(6, 10, 18, 34), at(6, 12, 47, 16) until at(6, 12, 48, 20),
        at(6, 17, 59, 31) until at(6, 18, 0, 43), at(7, 1, 2, 10) until at(7, 1, 3, 19),
        at(7, 5, 26, 48) until at(7, 5, 27, 59), at(7, 10, 33, 4) until at(7, 10, 34, 7),
        at(7, 11, 11, 35) until at(7, 11, 12, 45), at(7, 11, 17, 34) until at(7, 11, 18, 39),
        at(7, 11, 35, 26) until at(7, 11, 36, 27), at(7, 14, 1, 13) until at(7, 14, 2, 26),
        at(8, 4, 31, 46) until at(8, 4, 32, 58), at(8, 8, 0, 6) until at(8, 8, 1, 19),
        at(8, 18, 0, 22) until at(8, 18, 1, 33),
    )
    /** When kahraba was really on (from the log and the light), with a minute or two of slack. */
    private val kahraba = listOf(
        at(6, 3, 59) until at(6, 6, 1), at(6, 15, 50) until at(6, 18, 1), at(7, 4, 58) until at(7, 5, 28),
        at(7, 11, 39) until at(7, 14, 2), at(8, 2, 2) until at(8, 4, 33), at(8, 6, 13) until at(8, 8, 2),
        at(8, 15, 59) until at(8, 18, 2),
    )

    private fun replay(): List<Pair<Transition, Long>> {
        val rows = javaClass.classLoader!!.getResourceAsStream("nov08-pixel2-raw-three-days.csv")!!.bufferedReader()
            .readLines().drop(1).filter { it.isNotBlank() }.map { it.split(",") }.map { it[0].toLong() to it[1].toDouble() }
        val d = PowerDetector(hourOf = recordingClock(23))
        val start = at(5, 23, 35)
        d.setManual(start, Source.MOTEUR)
        val out = mutableListOf<Pair<Transition, Long>>()
        var i = rows.indexOfFirst { it.first > start }
        var lux = rows[i - 1].second
        var t = start
        while (t <= at(8, 18, 20)) {
            while (i < rows.size && rows[i].first <= t) { lux = rows[i].second; d.addReading(t, lux); i++ }
            d.tick(t, lux, cuts.none { t in it }).filter { !it.manual }.forEach { out += it to t }
            d.takeLightOff()
            t += 200
        }
        return out
    }

    @Test
    fun everyKahrabaCaughtAndNoFalseOne() {
        val k = replay().filter { it.first.to == Source.KAHRABA }
        assertEquals("kahraba: $k", kahraba.size, k.size)
        for ((tr, _) in k) assertTrue("false kahraba ${tr.timeMs}: ${tr.reason}", kahraba.any { tr.timeMs in it })
    }

    @Test
    fun aJumpRightAfterAFlickeringMoteurIsCaughtQuickly() {
        val (tr, announced) = replay().first { it.first.to == Source.KAHRABA && it.first.timeMs >= at(8, 15, 0) }
        assertTrue("dated ${tr.timeMs}", tr.timeMs in at(8, 15, 59)..at(8, 16, 1))
        assertTrue("announced $announced (1.4.10: 16:47)", announced <= at(8, 16, 6))
    }
}
