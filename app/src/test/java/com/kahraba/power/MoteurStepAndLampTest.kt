package com.kahraba.power

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Pixel 2 in Always-on mode, 12 Nov 16:00 to 18:30 (raw readings; times in ms from 16:00). Moteur
 * after the 16:00 cut; in the evening it moves between ~1,640 and ~2,050 lux. At 17:12:58 it stepped up
 * to 2,036 and stayed 5 minutes (12% above its usual level; 219 V, pole light green): 1.5.09 said
 * kahraba at 17:19. At 18:10:25 a hallway lamp raised the light 12% for ~3 minutes, then it went back
 * to 1,808: kahraba at 18:15. Neither had the switch-over flicker. 1.5.10: without it, the light must
 * stay above the moteur's brightest of the 10 minutes before, and if it's less than 10% above it,
 * for 10 minutes (`MARGINAL_CONFIRM_MS`).
 */
class MoteurStepAndLampTest {
    private fun at(h: Int, m: Int, s: Int = 0) = (((h - 16) * 60 + m) * 60 + s) * 1000L

    @Test
    fun anEveningMoteurStepAndAHallwayLampAreNotKahraba() {
        val rows = javaClass.classLoader!!.getResourceAsStream("nov12-pixel2-raw-evening-moteur-hallway-light.csv")!!.bufferedReader()
            .readLines().drop(1).filter { it.isNotBlank() }.map { it.split(",") }.map { it[0].toLong() to it[1].toDouble() }
        val d = PowerDetector(hourOf = recordingClock(16))
        val start = at(16, 1, 30)
        d.setManual(start, Source.MOTEUR)
        val out = mutableListOf<Transition>()
        var i = rows.indexOfFirst { it.first > start }
        var lux = rows[i - 1].second
        var t = start
        while (t <= at(18, 29)) {
            while (i < rows.size && rows[i].first <= t) { lux = rows[i].second; d.addReading(t, lux); i++ }
            out += d.tick(t, lux, true).filter { !it.manual }
            d.takeLightOff()
            t += 200
        }
        assertEquals("switches: $out", emptyList<Source>(), out.map { it.to })
    }
}
