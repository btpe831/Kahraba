package com.kahraba.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Pixel 2 in Always-on mode, 16 Nov 05:00 to 17 Nov 20:35 (raw readings; times in ms from 05:00 on
 * 16 Nov).
 *
 * 16 Nov, 7:10 AM: the moteur stepped up ~5% above its brightest and stayed there 12 minutes, with
 * short wobbles at 7:12 and 7:21; 1.5.17 forgot the 7:12 ones during its 10-minute check and alerted
 * kahraba at 7:22, half a minute before the light dropped back (1.5.18 keeps every wobble). Real
 * kahraba 3:00 to 5:00 PM, 9:00 to 9:10 PM, 11:00 PM to 1:00 AM.
 *
 * 17 Nov: kahraba 11:01 AM (flicker, only +7.4%; a dip at 11:15 broke the stillness), a 59 s cut at
 * 11:21, back dead still and brighter until the 1:01 PM cut. 1.5.18: the power coming back is a switch
 * moment, then 15 minutes completely still in the daytime: kahraba since 11:22. The cuts at 9:25,
 * 9:51 AM and 5:53 PM had the moteur on both sides.
 */
class CutStillKahrabaTest {
    private fun at(day: Int, h: Int, m: Int, s: Int = 0) = ((((day - 16) * 24 + h - 5) * 60 + m) * 60 + s) * 1000L

    private val cuts = listOf(
        at(16, 16, 59, 30) until at(16, 17, 0, 41),
        at(16, 21, 9, 33) until at(16, 21, 10, 46),
        at(17, 1, 0, 54) until at(17, 1, 2, 5),
        at(17, 9, 25, 30) until at(17, 9, 26, 43),
        at(17, 9, 51, 41) until at(17, 9, 52, 52),
        at(17, 11, 21, 36) until at(17, 11, 22, 35),
        at(17, 13, 1, 13) until at(17, 13, 2, 23),
        at(17, 17, 53, 41) until at(17, 17, 54, 46),
    )

    private fun replay(offsetMs: Long): List<Pair<Transition, Long>> {
        val rows = javaClass.classLoader!!.getResourceAsStream("nov17-pixel2-raw-moteur-step-and-kahraba-after-cut.csv")!!
            .bufferedReader().readLines().drop(1).filter { it.isNotBlank() }.map { it.split(",") }
            .map { it[0].toLong() to it[1].toDouble() }
        val d = PowerDetector(hourOf = recordingClock(5))
        val start = at(16, 5, 0, 10) + offsetMs
        d.setManual(start, Source.MOTEUR)
        val out = mutableListOf<Pair<Transition, Long>>()
        var i = rows.indexOfFirst { it.first > start }
        var lux = rows[i - 1].second
        var t = start
        while (t <= at(17, 20, 35)) {
            while (i < rows.size && rows[i].first <= t) { lux = rows[i].second; d.addReading(t, lux); i++ }
            d.tick(t, lux, cuts.none { t in it }).filter { !it.manual }.forEach { out += it to t }
            d.takeLightOff()
            t += 200
        }
        return out
    }

    @Test
    fun moteurStepIsCancelledAndKahrabaAfterACutIsFound() {
        // 1.5.17 alerted the 7:10 AM moteur step at almost every timing of its once-a-second check.
        for (offset in listOf(0L, 100L, 500L, 900L)) {
            val out = replay(offset)
            val kahraba = out.filter { it.first.to == Source.KAHRABA }
            val day16 = kahraba.filter { it.second < at(17, 0, 0) }
            assertEquals("16 Nov (offset $offset): $day16", 3, day16.size)
            assertTrue("no kahraba before 3 PM (offset $offset): $day16", day16.all { it.first.timeMs >= at(16, 15, 0) })

            val day17 = kahraba.filter { it.second >= at(17, 1, 10) }
            assertEquals("17 Nov (offset $offset): $day17", 1, day17.size)
            val (tr, announced) = day17[0]
            assertTrue("dated ${tr.timeMs}", tr.timeMs in at(17, 11, 22, 30)..at(17, 11, 22, 40))
            assertTrue(tr.reason, tr.reason.startsWith("The power came back"))
            assertTrue("announced $announced", announced in at(17, 11, 38)..at(17, 11, 45))
        }
    }
}
