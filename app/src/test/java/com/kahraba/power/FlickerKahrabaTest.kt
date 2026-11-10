package com.kahraba.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Pixel 2 in Always-on mode, 10 Nov 06:00 to 12:00 (raw readings; times in ms from 06:00). A very
 * calm daytime moteur (~1,100 lux), completely still from 9:12 to 9:58. At 9:58:40 kahraba: the
 * changeover switch flicked the light down to 811 lux for a tenth of a second, then it stayed at
 * ~1,217, only 11% brighter (too little for the 12% rule). 1.5.07 alerted at 10:06 by the 45-minute
 * stillness rule, dated 9:18, and would have done the same with no kahraba at all. 1.5.08: the
 * switch-over flicker (`SWITCH_DIP`) and 8% brighter and still: alert by 10:04, dated 9:58.
 */
class FlickerKahrabaTest {
    private fun at(h: Int, m: Int, s: Int = 0) = (((h - 6) * 60 + m) * 60 + s) * 1000L

    private fun replay(until: Long, keep: (Long) -> Boolean = { true }): List<Pair<Transition, Long>> {
        val rows = javaClass.classLoader!!.getResourceAsStream("nov10-pixel2-raw-flicker-kahraba.csv")!!.bufferedReader()
            .readLines().drop(1).filter { it.isNotBlank() }.map { it.split(",") }.map { it[0].toLong() to it[1].toDouble() }
            .filter { keep(it.first) }
        val d = PowerDetector(hourOf = recordingClock(6))
        d.setManual(0, Source.MOTEUR)
        val out = mutableListOf<Pair<Transition, Long>>()
        var i = rows.indexOfFirst { it.first > 0 }
        var lux = rows[i - 1].second
        var t = 0L
        while (t <= until) {
            while (i < rows.size && rows[i].first <= t) { lux = rows[i].second; d.addReading(t, lux); i++ }
            d.tick(t, lux, true).filter { !it.manual }.forEach { out += it to t }
            d.takeLightOff()
            t += 200
        }
        return out
    }

    @Test
    fun kahrabaAfterTheSwitchFlickerIsCaughtQuickly() {
        val sw = replay(at(12, 0))
        assertEquals("switches: $sw", listOf(Source.KAHRABA), sw.map { it.first.to })
        val (tr, announced) = sw[0]
        assertTrue("dated ${tr.timeMs}", tr.timeMs in at(9, 58, 40)..at(9, 58, 41))
        assertTrue(tr.reason, tr.reason.startsWith("Light flickered"))
        assertTrue("announced $announced (1.5.07: 10:06)", announced <= at(10, 4))
    }

    @Test
    fun theStillDaytimeMoteurIsNotCalledKahraba() {
        // Without kahraba: the moteur's light just stays still (1.5.07 called kahraba at 10:07).
        val sw = replay(at(10, 15)) { it < at(9, 58, 40) }
        assertEquals("switches: $sw", emptyList<Source>(), sw.map { it.first.to })
    }
}
