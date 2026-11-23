package com.kahraba.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Pixel 2 in Always-on mode, 22 Nov 18:00 to 23 Nov 11:28 (raw readings; times in ms from 18:00 on
 * 22 Nov). Kahraba 1:54 AM to the 4:02 cut; kahraba again at 10:06 (confirmed), then a 47 s cut at 10:15:47
 * and kahraba straight back (236 V at 11:25): the light settled in two steps (+30 s, +4 min), then stayed
 * dead still. 1.5.19 waited for the 60-minute stillness rule (alert 11:20). 1.5.20 also judges the
 * stillness from 5 minutes after the cut: alert 10:39, "since 10:16".
 */
class CutSettleKahrabaTest {
    private fun at(day: Int, h: Int, m: Int, s: Int = 0) = ((((day - 22) * 24 + h - 18) * 60 + m) * 60 + s) * 1000L

    private val cuts = listOf(
        at(23, 4, 2, 0) until at(23, 4, 3, 10),
        at(23, 10, 15, 47) until at(23, 10, 16, 34),
    )

    @Test
    fun kahrabaBackAfterACutIsFoundOnceTheLightHasSettled() {
        val rows = javaClass.classLoader!!.getResourceAsStream("nov23-pixel2-raw-kahraba-cut-settles.csv")!!
            .bufferedReader().readLines().drop(1).filter { it.isNotBlank() }.map { it.split(",") }
            .map { it[0].toLong() to it[1].toDouble() }
        val d = PowerDetector(hourOf = recordingClock(18))
        val start = at(22, 18, 1)
        d.setManual(start, Source.MOTEUR)
        val out = mutableListOf<Pair<Transition, Long>>()
        var i = rows.indexOfFirst { it.first > start }
        var lux = rows[i - 1].second
        var t = start
        while (t <= at(23, 11, 28)) {
            while (i < rows.size && rows[i].first <= t) { lux = rows[i].second; d.addReading(t, lux); i++ }
            d.tick(t, lux, cuts.none { t in it }).filter { !it.manual }.forEach { out += it to t }
            d.takeLightOff()
            t += 200
        }
        assertEquals("switches: $out",
            listOf(Source.KAHRABA, Source.MOTEUR, Source.KAHRABA, Source.MOTEUR, Source.KAHRABA), out.map { it.first.to })
        val (tr, announced) = out[4]
        assertTrue("dated ${tr.timeMs}", tr.timeMs in at(23, 10, 16, 30)..at(23, 10, 16, 40))
        assertTrue(tr.reason, tr.reason.startsWith("The power came back"))
        assertTrue("announced $announced", announced in at(23, 10, 38)..at(23, 10, 42))
    }
}
