package com.kahraba.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Pixel 2 in Always-on mode in the washroom, 2 Nov 19:00 to 4 Nov 06:44 (raw readings; times in
 * ms from 19:00 on 2 Nov). Moteur after a cut at 19:09; kahraba 20:01 (the pole light off), cut 22:02
 * for 1m 14s. On 3 Nov the light was switched off four times (07:44-07:51, 18:02-18:14, 18:42-18:45,
 * 20:56-21:33). Kahraba from 10:35, only ~5% brighter than the morning moteur but almost perfectly
 * still for two hours, until a 1m 13s cut at 12:42 (missed by 1.4.08). The moteur after it was green
 * on the pole at 17:27 (227 V). Kahraba again from ~21:00 (234 V; "It's kahraba" tapped at 21:34),
 * cut at midnight for 1m 16s, then a calm night moteur that was still for up to half an hour.
 * The owner's unplug test at 20:19 on 2 Nov is left out. At 10:35:32 the changeover switch flicked the
 * light down to 1,231 lux for a fraction of a second: since 1.5.08 that and the light staying still
 * and brighter is enough (alert 10:40, was 11:23 by the 45-minute stillness rule).
 */
class QuietKahrabaTest {
    private fun at(day: Int, h: Int, m: Int, s: Int = 0) = (((day * 24 + h - 19) * 60 + m) * 60 + s) * 1000L
    private val cuts = listOf(
        at(0, 22, 2, 15) until at(0, 22, 3, 29),
        at(1, 12, 42, 41) until at(1, 12, 43, 55),
        at(1, 23, 59, 24) until at(2, 0, 0, 39),
    )
    private val lightOffAlerts = mutableListOf<Long>()

    private fun replay(keep: (Long) -> Boolean = { true }): List<Transition> {
        val rows = javaClass.classLoader!!.getResourceAsStream("nov03-pixel2-raw-quiet-kahraba-light-off.csv")!!.bufferedReader()
            .readLines().drop(1).filter { it.isNotBlank() }.map { it.split(",") }.map { it[0].toLong() to it[1].toDouble() }
            .filter { keep(it.first) }
        val d = PowerDetector(hourOf = recordingClock(19))
        val start = at(0, 19, 9, 10)
        val tapped = at(1, 21, 34, 56)
        val out = mutableListOf(d.setManual(start, Source.MOTEUR))
        var i = rows.indexOfFirst { it.first > start }
        var lux = rows[i - 1].second
        var t = start
        val end = at(2, 6, 44)
        while (t <= end) {
            while (i < rows.size && rows[i].first <= t) { lux = rows[i].second; d.addReading(t, lux); i++ }
            if (t == tapped) out += d.setManual(t, Source.KAHRABA)
            out += d.tick(t, lux, cuts.none { t in it })
            if (d.takeLightOff()) lightOffAlerts += t
            t += 200
        }
        return out.filter { !it.manual }
    }

    @Test
    fun quietDaytimeKahrabaIsCaughtAndTheCalmNightMoteurIsNot() {
        val sw = replay()
        assertEquals(
            "switches: $sw",
            listOf(Source.KAHRABA, Source.MOTEUR, Source.KAHRABA, Source.MOTEUR, Source.MOTEUR),
            sw.map { it.to },
        )
        // Kahraba from 20:01, by the brightness rule.
        assertTrue("${sw[0]}", sw[0].timeMs in at(0, 20, 0)..at(0, 20, 2))
        assertTrue("${sw[1]}", sw[1].timeMs == at(0, 22, 3, 29))
        // The quiet kahraba of 3 Nov: from 10:35:32, found by the switch-over flicker.
        assertTrue("${sw[2]}", sw[2].timeMs in at(1, 10, 35, 32)..at(1, 10, 35, 34))
        assertTrue("${sw[2]}", sw[2].reason.startsWith("Light flickered"))
        // Its end: the 12:42 cut. Then moteur all afternoon, and after the midnight cut all night.
        assertTrue("${sw[3]}", sw[3].timeMs == at(1, 12, 43, 55))
        assertTrue("${sw[4]}", sw[4].timeMs == at(2, 0, 0, 39))
    }

    @Test
    fun everyLightSwitchedOffIsAlerted() {
        replay()
        val spells = listOf(at(1, 7, 44), at(1, 18, 2), at(1, 18, 42), at(1, 20, 56))
        for (s in spells) assertTrue("alert for ${s}: $lightOffAlerts", lightOffAlerts.any { it in s..s + 90_000 })
    }

    @Test
    fun withoutTheFlickerTheHourOfStillnessStillFindsIt() {
        // The two readings of the dip left out: only the daytime stillness rule (60 minutes) is left.
        val sw = replay { it !in at(1, 10, 35, 32)..at(1, 10, 35, 33) || it % 1000 > 900 }
        assertEquals("switches: $sw", listOf(Source.KAHRABA, Source.MOTEUR, Source.KAHRABA, Source.MOTEUR, Source.MOTEUR), sw.map { it.to })
        assertTrue("${sw[2]}", sw[2].timeMs in at(1, 10, 30)..at(1, 10, 50))
        assertTrue("${sw[2]}", sw[2].reason.startsWith("Light stayed still for 60 minutes"))
    }
}
