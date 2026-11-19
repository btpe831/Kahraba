package com.kahraba.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Pixel 2 in Always-on mode, 17 Nov 23:15 to 19 Nov 07:11 (raw readings; times in ms from 23:00 on
 * 17 Nov). Moteur all night, ~2,700-3,250 lux.
 *
 * - 18 Nov 3:38 AM: the night moteur stepped up 15% above its last 10 minutes (2,826 -> 3,263), to the
 *   top it had already reached around 2 AM, and stayed 4 minutes: 1.5.18 alerted kahraba at 3:43
 *   (confirmed false). 1.5.19 compares with the moteur's brightest over the last hour.
 * - 5:59:52 to 6:05:30: the charger off (a touched cable), the light on all along: 1.5.18 said "Power
 *   off", then "assuming moteur". 1.5.19: "The phone isn't charging", and nothing switches.
 * - Kahraba 7:58 AM to the 10:02 cut; 9:07 PM to the 11:01 PM cut (231 V); 19 Nov 7:02 AM after a
 *   12 s blink (233 V).
 */
class NightStepCableTest {
    private fun at(day: Int, h: Int, m: Int, s: Int = 0) = ((((day - 17) * 24 + h - 23) * 60 + m) * 60 + s) * 1000L

    private val chargerOff = listOf(
        at(18, 5, 59, 52) until at(18, 6, 5, 30),
        at(18, 10, 2, 44) until at(18, 10, 3, 54),
        at(18, 23, 1, 3) until at(18, 23, 2, 15),
        at(19, 7, 2, 18) until at(19, 7, 2, 30),
    )

    @Test
    fun nightMoteurStepIsNotKahrabaAndALooseCableIsNotACut() {
        val rows = javaClass.classLoader!!.getResourceAsStream("nov19-pixel2-raw-night-moteur-step-cable-blink.csv")!!
            .bufferedReader().readLines().drop(1).filter { it.isNotBlank() }.map { it.split(",") }
            .map { it[0].toLong() to it[1].toDouble() }
        val d = PowerDetector(hourOf = recordingClock(23))
        val start = at(17, 23, 15)
        d.setManual(start, Source.MOTEUR)
        val out = mutableListOf<Transition>()
        val notCharging = mutableListOf<Long>()
        var i = rows.indexOfFirst { it.first > start }
        var lux = rows[i - 1].second
        var t = start
        while (t <= at(19, 7, 10, 50)) {
            while (i < rows.size && rows[i].first <= t) { lux = rows[i].second; d.addReading(t, lux); i++ }
            out += d.tick(t, lux, chargerOff.none { t in it }).filter { !it.manual }
            if (d.takeNotCharging()) notCharging += t
            d.takeLightOff()
            t += 200
        }
        assertEquals("switches: $out",
            listOf(Source.KAHRABA, Source.MOTEUR, Source.KAHRABA, Source.MOTEUR, Source.KAHRABA), out.map { it.to })
        assertTrue("${out[0]}", out[0].timeMs in at(18, 7, 58, 30)..at(18, 7, 58, 45))
        assertTrue("${out[2]}", out[2].timeMs in at(18, 21, 7, 30)..at(18, 21, 7, 45))
        assertTrue("${out[4]}", out[4].timeMs in at(19, 7, 2, 25)..at(19, 7, 2, 35))
        assertEquals("not charging: $notCharging", 1, notCharging.size)
        assertTrue("not charging at ${notCharging[0]}", notCharging[0] in at(18, 6, 0, 20)..at(18, 6, 0, 30))
    }
}
