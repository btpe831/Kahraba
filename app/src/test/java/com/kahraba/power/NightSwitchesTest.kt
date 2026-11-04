package com.kahraba.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Pixel 2 in Always-on mode under the halogen, night of 1-2 Nov 2026 (raw readings; times in ms
 * from 21:00 on 1 Nov). Moteur at 223 V when the owner tapped "It's moteur" (21:44). Kahraba at
 * 01:01 (the light jumped ~2,850 -> ~3,600 lux and stayed still). Power cut 03:30:33 for 1m 14s (the
 * heater clock reset), then the moteur, which ran high: 227 V at 6 AM, as bright as the later
 * kahraba, but wobbling. Kahraba at 09:59 (227-228 V with 8 A in use). 1.2.05 got all three right.
 */
class NightSwitchesTest {
    private val cut = 23_433_204L until 23_507_204L
    private val end = 48_060_000L // 10:21, when Always-on mode was stopped

    private fun replay(): List<Transition> {
        val rows = javaClass.classLoader!!.getResourceAsStream("nov02-night-pixel2-raw-kahraba-cut-moteur-kahraba.csv")!!.bufferedReader()
            .readLines().drop(1).filter { it.isNotBlank() }.map { it.split(",") }.map { it[0].toLong() to it[1].toDouble() }
        val d = PowerDetector(hourOf = recordingClock(21))
        val start = 2_673_000L // 21:44:33, "It's moteur"
        val out = mutableListOf(d.setManual(start, Source.MOTEUR))
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
    fun kahrabaTwiceAndTheHighMoteurInBetween() {
        val sw = replay()
        assertEquals("switches: $sw", listOf(Source.KAHRABA, Source.MOTEUR, Source.KAHRABA), sw.map { it.to })
        // Kahraba from about 01:01.
        assertTrue("${sw[0]}", sw[0].timeMs in 14_430_000L..14_520_000L)
        // The cut: moteur when the power comes back.
        assertTrue("${sw[1]}", sw[1].timeMs in cut.last..cut.last + 1_000)
        // Six hours of a high moteur (as bright as kahraba at 6 AM) never called kahraba; kahraba from 09:59.
        assertTrue("${sw[2]}", sw[2].timeMs in 46_740_000L..46_830_000L)
    }
}
