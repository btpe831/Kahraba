package com.kahraba.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Pixel 2 in Always-on mode under the halogen, 1 Nov 2026 morning (raw readings; times in ms
 * from 08:00). Kahraba (231-234 V on the voltmeter), set by the owner at 08:12:59: about 4,400-4,700
 * lux and completely still. Power cut at 10:00:12 for 1m 13s, then the moteur (213 V): about
 * 3,100-3,500 lux, wobbling. Kahraba was ~40% brighter than the moteur.
 */
class KahrabaThenMoteurTest {
    private val cut = 7_211_800L until 7_285_064L
    private val end = 9_420_000L // 10:37, before the phone was picked up

    private fun replay(): List<Transition> {
        val rows = javaClass.classLoader!!.getResourceAsStream("nov01-morning-pixel2-raw-kahraba-cut-moteur.csv")!!.bufferedReader()
            .readLines().drop(1).filter { it.isNotBlank() }.map { it.split(",") }.map { it[0].toLong() to it[1].toDouble() }
        val d = PowerDetector()
        val start = 780_000L // 08:13:00, just after "It's kahraba" was tapped
        val out = mutableListOf(d.setManual(start, Source.KAHRABA))
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
    fun stillKahrabaStaysKahrabaAndTheCutIsMoteur() {
        val sw = replay()
        assertEquals("switches: $sw", listOf(Source.MOTEUR), sw.map { it.to })
        assertTrue("${sw[0]}", sw[0].timeMs in cut.last..cut.last + 1_000)
        assertTrue(sw[0].reason, sw[0].reason.startsWith("Power cut (1m 13s)"))
    }
}
