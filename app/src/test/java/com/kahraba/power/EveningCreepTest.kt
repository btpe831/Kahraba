package com.kahraba.power

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Pixel 2 in Always-on mode, 11 Nov 15:00 to 19:11 (raw readings; times in ms from 15:00). Moteur
 * after a 1m 11s cut at 15:16, calm in the evening and creeping brighter in sensor steps (2,330 ->
 * 2,530 -> 2,700 lux; the owner saw 222 V and the pole light green). At 18:53-18:54 two steps came
 * ~10 s apart, which counted as a busy moteur, and the still light after it was 10% brighter: 1.5.08
 * said kahraba at 19:02 (dated 18:54). 1.5.09: busy needs wobbling in 3 of the last 10 minutes
 * (`BUSY_MINUTES`); the busy moteurs before real kahraba had 3-10.
 */
class EveningCreepTest {
    private fun at(h: Int, m: Int, s: Int = 0) = (((h - 15) * 60 + m) * 60 + s) * 1000L

    @Test
    fun aCalmEveningMoteurCreepingUpIsNotKahraba() {
        val rows = javaClass.classLoader!!.getResourceAsStream("nov11-pixel2-raw-evening-moteur-creeps.csv")!!.bufferedReader()
            .readLines().drop(1).filter { it.isNotBlank() }.map { it.split(",") }.map { it[0].toLong() to it[1].toDouble() }
        val d = PowerDetector(hourOf = recordingClock(15))
        val start = at(15, 20)
        d.setManual(start, Source.MOTEUR)
        val out = mutableListOf<Transition>()
        var i = rows.indexOfFirst { it.first > start }
        var lux = rows[i - 1].second
        var t = start
        while (t <= at(19, 11, 50)) {
            while (i < rows.size && rows[i].first <= t) { lux = rows[i].second; d.addReading(t, lux); i++ }
            out += d.tick(t, lux, true).filter { !it.manual }
            d.takeLightOff()
            t += 200
        }
        assertEquals("switches: $out", emptyList<Source>(), out.map { it.to })
    }
}
