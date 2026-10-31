package com.kahraba.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Pixel 2's always-on monitor, night of 30-31 Oct 2026 (raw readings; times in ms from 18:00).
 * Moteur in the evening (223 V at 20:28). At 04:41:41 the power blinked for 10 s and kahraba came
 * back (the light 7% brighter and perfectly steady; 234 V later). At 06:58:08 a 77 s cut, then the
 * moteur (dimmer and wobbling). 1.1.03 and earlier logged the 10 s blink as "moteur took over".
 */
class BlinkTest {
    private val blinkOff = 38_501_000L until 38_511_000L
    private val cutOff = 46_688_000L until 46_765_000L

    private fun replay(): List<Transition> {
        val rows = javaClass.classLoader!!.getResourceAsStream("oct30-night-pixel2-raw-blink-to-kahraba.csv")!!.bufferedReader()
            .readLines().drop(1).map { it.split(",") }.map { it[0].toLong() to it[1].toDouble() }
        val d = PowerDetector()
        val start = 4_191_000L // 19:09:51, when "It's moteur" was tapped
        val out = mutableListOf(d.setManual(start, Source.MOTEUR))
        var i = rows.indexOfFirst { it.first > start }
        var lux = rows[i - 1].second
        var t = start
        while (t <= rows.last().first) {
            while (i < rows.size && rows[i].first <= t) { lux = rows[i].second; d.addReading(t, lux); i++ }
            out += d.tick(t, lux, t !in blinkOff && t !in cutOff)
            t += 200
        }
        return out.filter { !it.manual }
    }

    @Test
    fun theBlinkToKahrabaIsCaughtAndTheLaterCutIsMoteur() {
        val sw = replay()
        assertEquals("switches: $sw", listOf(Source.KAHRABA, Source.MOTEUR), sw.map { it.to })
        // Kahraba within a few minutes of the blink, once the light has settled.
        assertTrue("${sw[0]}", sw[0].timeMs in blinkOff.last..blinkOff.last + 5 * 60_000)
        // Moteur when the 77 s cut ends.
        assertTrue("${sw[1]}", sw[1].timeMs in cutOff.last..cutOff.last + 10_000)
    }
}
