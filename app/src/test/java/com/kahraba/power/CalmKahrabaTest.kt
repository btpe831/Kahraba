package com.kahraba.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Pixel 2 in Always-on mode, 31 Oct 2026, daytime (raw readings; times in ms from 10:00).
 * A busy moteur all morning (~3150 lux, wobbling most minutes). At 13:00 the light stepped up to
 * 4090 and settled at 3642 over 5 minutes, then stayed completely still: kahraba, missed by 1.1.04.
 * A 7.5-minute outage at 13:22:43; the power came back at 13:30:13 still and bright (3752): kahraba
 * again, missed too. A 21-minute outage at 15:00:29, back as a wobbling moteur (~2900). At 18:01:12
 * the phone fell off its stand and its charger came off 3 s later; 1.1.04 said "Power off".
 */
class CalmKahrabaTest {
    private fun hms(h: Int, m: Int, s: Int) = ((h - 10) * 3600 + m * 60 + s) * 1000L

    private val outage1 = hms(13, 22, 43) until hms(13, 30, 13)
    private val outage2 = hms(15, 0, 29) until hms(15, 21, 39)
    private val fell = hms(18, 1, 12)
    private val unplugged = hms(18, 1, 15)

    private var phoneMovedAt = -1L

    private fun replay(): List<Transition> {
        val rows = javaClass.classLoader!!.getResourceAsStream("oct31-day-pixel2-raw-calm-kahraba.csv")!!.bufferedReader()
            .readLines().drop(1).filter { it.isNotBlank() }.map { it.split(",") }.map { it[0].toLong() to it[1].toDouble() }
        val d = PowerDetector()
        val start = hms(10, 56, 0) // the app had just said moteur (wobbling light)
        val out = mutableListOf(d.setManual(start, Source.MOTEUR))
        var i = rows.indexOfFirst { it.first > start }
        var lux = rows[i - 1].second
        var t = start
        while (t <= rows.last().first) {
            while (i < rows.size && rows[i].first <= t) { lux = rows[i].second; d.addReading(t, lux); i++ }
            if (t == fell) d.onMotion(t)
            out += d.tick(t, lux, t !in outage1 && t !in outage2 && t < unplugged)
            if (d.takePhoneMoved() && phoneMovedAt < 0) phoneMovedAt = t
            t += 200
        }
        return out.filter { !it.manual }
    }

    @Test
    fun calmBrightLightAfterABusyMoteurIsKahraba() {
        val sw = replay()
        assertEquals(
            "switches: $sw",
            listOf(Source.KAHRABA, Source.NO_POWER, Source.MOTEUR, Source.KAHRABA, Source.NO_POWER, Source.MOTEUR),
            sw.map { it.to },
        )
        // Kahraba once the light has been still for 5 minutes after settling (13:05).
        assertTrue("${sw[0]}", sw[0].timeMs in hms(13, 5, 0)..hms(13, 15, 0))
        // After the first outage: back as kahraba within 10 minutes.
        assertTrue("${sw[2]}", sw[2].timeMs == outage1.last + 1)
        assertTrue("${sw[3]}", sw[3].timeMs in outage1.last..outage1.last + 10 * 60_000)
        // After the second outage: a wobbling moteur, and it stays moteur.
        assertTrue("${sw[5]}", sw[5].timeMs == outage2.last + 1)
    }

    @Test
    fun theFallIsAPhoneMovedAlertNotPowerOff() {
        val sw = replay()
        assertEquals("alert when the charger came off", unplugged, phoneMovedAt)
        assertFalse("no power off after the fall: $sw", sw.any { it.timeMs >= fell })
    }
}
