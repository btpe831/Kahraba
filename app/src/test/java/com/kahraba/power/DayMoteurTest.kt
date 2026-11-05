package com.kahraba.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Pixel 2 in Always-on mode in the washroom, 5 Nov 2026 (raw readings; times in ms from
 * midnight). Night: a 1m 15s cut at 00:00 (kahraba -> moteur), then a calm moteur that stayed
 * completely still for 60 minutes (02:13-03:13), and kahraba at 06:01. Always-on stopped at 06:33
 * and was restarted at 07:36 ("It's moteur"). Day: moteur from 07:36 to 17:00, with a 2m 38s cut at
 * 08:04 and two ~1 min cuts at 12:35 and 12:45 (moteur before and after, so a 1-minute cut alone
 * doesn't prove kahraba was on). Its calmest 45 minutes still had 6 separate wobbles. Kahraba at
 * 17:00 (light ~30% up, dead still) to an outage at 19:00 (12m 16s, then a busy moteur).
 */
class DayMoteurTest {
    private fun at(h: Int, m: Int, s: Int = 0) = ((h * 60 + m) * 60 + s) * 1000L
    private val cuts = listOf(
        at(0, 0, 11) until at(0, 1, 26), at(8, 4, 44) until at(8, 7, 22), at(12, 34, 58) until at(12, 36, 11),
        at(12, 45, 19) until at(12, 46, 40), at(19, 0, 12) until at(19, 12, 28),
    )
    private val rows by lazy {
        javaClass.classLoader!!.getResourceAsStream("nov05-pixel2-raw-day-moteur-evening-kahraba.csv")!!.bufferedReader()
            .readLines().drop(1).filter { it.isNotBlank() }.map { it.split(",") }.map { it[0].toLong() to it[1].toDouble() }
    }

    /** Replays [start, end] from a known source; returns the app's own switches and when each was announced. */
    private fun replay(start: Long, end: Long, from: Source): List<Pair<Transition, Long>> {
        val d = PowerDetector(hourOf = recordingClock(0))
        d.setManual(start, from)
        val out = mutableListOf<Pair<Transition, Long>>()
        var i = rows.indexOfFirst { it.first > start }
        var lux = rows[i - 1].second
        var t = start
        while (t <= end) {
            while (i < rows.size && rows[i].first <= t) { lux = rows[i].second; d.addReading(t, lux); i++ }
            d.tick(t, lux, cuts.none { t in it }).filter { !it.manual }.forEach { out += it to t }
            assertTrue("light-off alert at $t", !d.takeLightOff())
            t += 200
        }
        return out
    }

    @Test
    fun aStillNightMoteurIsNotKahraba() {
        val sw = replay(at(0, 0, 7), at(6, 30), Source.KAHRABA)
        assertEquals("switches: $sw", listOf(Source.MOTEUR, Source.KAHRABA), sw.map { it.first.to })
        assertTrue("${sw[1]}", sw[1].first.timeMs in at(6, 0)..at(6, 2))
    }

    @Test
    fun aDayOfMoteurThenKahrabaAtFive() {
        val sw = replay(at(7, 37), at(19, 49), Source.MOTEUR)
        assertEquals(
            "switches: $sw",
            listOf(Source.MOTEUR, Source.MOTEUR, Source.MOTEUR, Source.KAHRABA, Source.NO_POWER, Source.MOTEUR),
            sw.map { it.first.to },
        )
        // 17:00: dated when it came on and announced by 17:06.
        val kahraba = sw[3]
        assertTrue("$kahraba", kahraba.first.timeMs in at(17, 0)..at(17, 1))
        assertTrue("announced ${kahraba.second}", kahraba.second <= at(17, 6))
    }
}
