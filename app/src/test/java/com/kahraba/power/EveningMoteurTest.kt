package com.kahraba.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Pixel 2 in Always-on mode under the halogen, 1 Nov 2026 afternoon and evening (raw readings;
 * times in ms from 14:00). A busy moteur (~3,400-3,900 lux), then kahraba at 16:01 (~4,300-4,900 lux,
 * dead still for 2 hours). Power cut 18:00:14 for 1m 14s, then the moteur at 222 V (the green light on
 * the pole was on): calm and bright, ~3,600-4,150 lux, close to kahraba. 1.2.04 said kahraba at 18:37
 * because the light matched the kahraba level learned for this spot.
 */
class EveningMoteurTest {
    private val cut = 14_414_844L until 14_488_844L
    private val end = 17_460_000L // 18:51, before the owner tapped "It's moteur"

    // Learned levels like the phone's: kahraba ~4,700 lux, the moteur ~3,550.
    private val config = DetectorConfig(kahrabaLevel = 4700.0, moteurLevel = 3550.0)

    private fun replay(): List<Transition> {
        val rows = javaClass.classLoader!!.getResourceAsStream("nov01-evening-pixel2-raw-kahraba-cut-calm-moteur.csv")!!.bufferedReader()
            .readLines().drop(1).filter { it.isNotBlank() }.map { it.split(",") }.map { it[0].toLong() to it[1].toDouble() }
        val d = PowerDetector(config, hourOf = recordingClock(14))
        val start = 2_280_000L // 14:38:00, once the phone was set down
        val out = mutableListOf<Transition>()
        var i = rows.indexOfFirst { it.first > start }
        var lux = rows[i - 1].second
        var t = start
        while (t <= end) {
            while (i < rows.size && rows[i].first <= t) { lux = rows[i].second; d.addReading(t, lux); i++ }
            out += d.tick(t, lux, t !in cut)
            t += 200
        }
        return out.filter { !it.manual }
    }

    @Test
    fun kahrabaIsCaughtAndTheBrightEveningMoteurIsNot() {
        val sw = replay()
        assertEquals("switches: $sw", listOf(Source.MOTEUR, Source.KAHRABA, Source.MOTEUR), sw.map { it.to })
        // Kahraba shown from when it came on (16:01), announced once confirmed.
        assertTrue("${sw[1]}", sw[1].timeMs in 7_200_000L..7_330_000L)
        // The cut: moteur when the power comes back, and moteur from then on.
        assertTrue("${sw[2]}", sw[2].timeMs in cut.last..cut.last + 1_000)
    }
}
