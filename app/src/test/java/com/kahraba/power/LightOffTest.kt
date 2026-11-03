package com.kahraba.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3 Nov 2026: the washroom light where the Pixel 2 records was switched off three times. The light
 * went dark but the charger stayed on, so it wasn't a power cut: the app should say so (once), and
 * a real power cut (charger off too) should not.
 */
class LightOffTest {
    private val start = 1_000_000L

    /** Runs [seconds] of ticks (5 a second); [lux] and [plugged] by time from [start]. */
    private fun run(seconds: Int, lux: (Long) -> Double, plugged: (Long) -> Boolean): Pair<List<Transition>, List<Long>> {
        val d = PowerDetector()
        val out = mutableListOf(d.setManual(start, Source.KAHRABA))
        val alerts = mutableListOf<Long>()
        var t = start
        while (t <= start + seconds * 1000L) {
            val rel = t - start
            out += d.tick(t, lux(rel), plugged(rel))
            if (d.takeLightOff()) alerts += rel
            t += 200
        }
        return out.filter { !it.manual } to alerts
    }

    @Test
    fun lightSwitchedOffWithTheChargerOnIsAlertedOnce() {
        // Steady 3,600 lux; switched off from 5 min to 15 min; back on after.
        val (sw, alerts) = run(20 * 60, { t -> if (t in 300_000L until 900_000L) 2.0 else 3600.0 }, { true })
        assertEquals(1, alerts.size)
        assertTrue("alert after 30 s of dark: $alerts", alerts[0] in 330_000L..332_000L)
        assertTrue("no switch for a light switched off: $sw", sw.isEmpty())
    }

    @Test
    fun aPowerCutIsNotALightSwitchedOff() {
        // The light and the charger both go off for 75 s: a cut, then the moteur.
        val cut = 300_000L until 375_000L
        val (sw, alerts) = run(10 * 60, { t -> if (t in cut) 2.0 else 3600.0 }, { t -> t !in cut })
        assertTrue("no light-off alert in a cut: $alerts", alerts.isEmpty())
        assertEquals(listOf(Source.MOTEUR), sw.map { it.to })
    }

    @Test
    fun aShortDipIsNotALightSwitchedOff() {
        val (_, alerts) = run(5 * 60, { t -> if (t in 120_000L until 140_000L) 2.0 else 3600.0 }, { true })
        assertFalse(alerts.isNotEmpty())
    }

    @Test
    fun alertsAgainAfterTheLightWasBackOn() {
        val dark = { t: Long -> t in 120_000L until 240_000L || t in 600_000L until 700_000L }
        val (_, alerts) = run(15 * 60, { t -> if (dark(t)) 2.0 else 3600.0 }, { true })
        assertEquals(2, alerts.size)
    }
}
