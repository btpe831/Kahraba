package com.kahraba.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Pixel 2 in Always-on mode, 14 Nov 05:00 to 08:57 (raw readings; times in ms from 05:00). Moteur,
 * its light changing every few minutes (~1,800-2,250 lux). At 6:13 AM the light went dark for ~1.6 s
 * with the charger on and the moteur carried on. At 8:19:51 it went dark for ~0.9 s, came back only 6%
 * brighter and stayed completely still for 38 minutes, until the 1m 10s cut at 8:57 that ends kahraba:
 * 1.5.10 never saw it. 1.5.11: a blink, then 25 minutes completely still (`BLINK_STILL_MS`; 15 after a
 * flicker, `SWITCH_STILL_MS`), in the daytime: kahraba since the blink.
 */
class BlinkStillKahrabaTest {
    private fun at(h: Int, m: Int, s: Int = 0) = (((h - 5) * 60 + m) * 60 + s) * 1000L

    @Test
    fun aBlinkThenLongStillnessIsKahrabaButTheMoteurBlinkIsNot() {
        val rows = javaClass.classLoader!!.getResourceAsStream("nov14-pixel2-raw-blink-quiet-kahraba.csv")!!.bufferedReader()
            .readLines().drop(1).filter { it.isNotBlank() }.map { it.split(",") }.map { it[0].toLong() to it[1].toDouble() }
        val d = PowerDetector(hourOf = recordingClock(5))
        val start = at(5, 0, 10)
        d.setManual(start, Source.MOTEUR)
        val out = mutableListOf<Pair<Transition, Long>>()
        var i = rows.indexOfFirst { it.first > start }
        var lux = rows[i - 1].second
        var t = start
        while (t <= at(8, 57, 15)) {
            while (i < rows.size && rows[i].first <= t) { lux = rows[i].second; d.addReading(t, lux); i++ }
            d.tick(t, lux, true).filter { !it.manual }.forEach { out += it to t }
            d.takeLightOff()
            t += 200
        }
        assertEquals("switches: $out", listOf(Source.KAHRABA), out.map { it.first.to })
        val (tr, announced) = out[0]
        assertTrue("dated ${tr.timeMs}", tr.timeMs in at(8, 19, 51)..at(8, 19, 52))
        assertTrue(tr.reason, tr.reason.contains("25 minutes"))
        assertTrue("announced $announced", announced in at(8, 44)..at(8, 50))
    }
}
