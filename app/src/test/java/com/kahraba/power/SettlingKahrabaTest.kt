package com.kahraba.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Pixel 2 in Always-on mode in the washroom, 4 Nov 2026 from 07:00 (raw readings; times in ms
 * from 07:00). "It's moteur" at 07:52; kahraba 07:59 to the 10:00 cut (1m 35s); a 1m 4s cut at 11:16
 * (moteur, wobbling, running high); kahraba 12:01 to an outage at 13:59 (5m 31s, then moteur). The
 * light was switched off at 17:21, 18:06, 18:07 and 19:59-20:51. Kahraba at 22:00 (238 V): the light
 * jumped ~30%, then settled down ~7% a minute later and stayed still. 1.4.09 waited for the slower
 * "stopped wobbling" rule (alert 22:09); 1.4.10 lets kahraba settle and alerts by 22:07.
 */
class SettlingKahrabaTest {
    private fun at(h: Int, m: Int, s: Int = 0) = (((h - 7) * 60 + m) * 60 + s) * 1000L
    private val cuts = listOf(at(10, 0, 12) until at(10, 1, 47), at(11, 16, 39) until at(11, 17, 43), at(13, 59, 51) until at(14, 5, 22))
    private val announced = mutableListOf<Long>()
    private val lightOffAlerts = mutableListOf<Long>()

    private fun replay(): List<Transition> {
        val rows = javaClass.classLoader!!.getResourceAsStream("nov04-pixel2-raw-kahraba-settles.csv")!!.bufferedReader()
            .readLines().drop(1).filter { it.isNotBlank() }.map { it.split(",") }.map { it[0].toLong() to it[1].toDouble() }
        val d = PowerDetector(hourOf = recordingClock(7))
        val start = at(7, 52, 30)
        val out = mutableListOf(d.setManual(start, Source.MOTEUR))
        var i = rows.indexOfFirst { it.first > start }
        var lux = rows[i - 1].second
        var t = start
        while (t <= at(22, 15)) {
            while (i < rows.size && rows[i].first <= t) { lux = rows[i].second; d.addReading(t, lux); i++ }
            val tr = d.tick(t, lux, cuts.none { t in it })
            tr.forEach { announced += t }
            out += tr
            if (d.takeLightOff()) lightOffAlerts += t
            t += 200
        }
        return out.filter { !it.manual }
    }

    @Test
    fun theDayAndAKahrabaThatSettlesInAStep() {
        val sw = replay()
        assertEquals(
            "switches: $sw",
            listOf(Source.KAHRABA, Source.MOTEUR, Source.MOTEUR, Source.KAHRABA, Source.NO_POWER, Source.MOTEUR, Source.KAHRABA),
            sw.map { it.to },
        )
        assertTrue("${sw[0]}", sw[0].timeMs in at(7, 59)..at(8, 1))
        assertTrue("${sw[3]}", sw[3].timeMs in at(12, 0)..at(12, 2))
        // 22:00: from when it came on, announced within 7 minutes (1.4.09: 22:09).
        assertTrue("${sw[6]}", sw[6].timeMs in at(22, 0)..at(22, 1))
        assertTrue("announced ${announced.last()}", announced.last() <= at(22, 7))
    }

    @Test
    fun everyLightSwitchedOffIsAlerted() {
        replay()
        for (s in listOf(at(17, 21), at(18, 6), at(19, 59))) {
            assertTrue("alert for $s: $lightOffAlerts", lightOffAlerts.any { it in s..s + 90_000 })
        }
    }
}
