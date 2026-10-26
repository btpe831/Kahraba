package com.karaba.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class PowerDetectorTest {

    private val step = 200L
    private val min = 60_000L

    /** Kahraba: steady light with tiny sensor noise (like the 862–868 lx reading). */
    private fun kahraba(t: Long) = 865.0 + 1.5 * sin(t / 3000.0)

    /** Moteur: steady most of the time, with a 4% dim lasting ~1.5s every [every] ms. */
    private fun moteur(every: Long) = { t: Long -> if (t % every < 1500) 830.0 else 865.0 }

    private class Run(val d: PowerDetector) {
        var t = 0L
        val transitions = mutableListOf<Transition>()
        fun run(ms: Long, plugged: Boolean? = true, lux: (Long) -> Double?) {
            val end = t + ms
            while (t < end) {
                transitions += d.tick(t, lux(t), plugged)
                t += 200
            }
        }
    }

    @Test
    fun steadyLightFromUnknownBecomesKahraba() {
        val r = Run(PowerDetector())
        r.run(11 * min) { kahraba(it) }
        assertEquals(Source.KAHRABA, r.d.source)
    }

    @Test
    fun flickerFromUnknownBecomesMoteur() {
        val r = Run(PowerDetector())
        r.run(4 * min, lux = moteur(20_000))
        assertEquals(Source.MOTEUR, r.d.source)
    }

    @Test
    fun cutThenSilentSwitchBackToKahraba() {
        val r = Run(PowerDetector(initialSource = Source.KAHRABA))
        r.run(2 * min) { kahraba(it) }
        r.run(3_000, plugged = false) { 0.0 }            // short cut
        r.run(1_000) { kahraba(it) }
        assertEquals(Source.MOTEUR, r.d.source)
        r.run(30 * min, lux = moteur(60_000))             // moteur, flickers every minute
        assertEquals(Source.MOTEUR, r.d.source)
        r.run(11 * min) { kahraba(it) }                   // silent switch to kahraba
        assertEquals(Source.KAHRABA, r.d.source)
        assertEquals(listOf(Source.MOTEUR, Source.KAHRABA), r.transitions.map { it.to })
    }

    @Test
    fun moteurThatFlickersEveryFiveMinutesStaysMoteur() {
        val r = Run(PowerDetector(initialSource = Source.MOTEUR))
        r.run(2 * 60 * min, lux = moteur(5 * min))
        assertEquals(Source.MOTEUR, r.d.source)
    }

    @Test
    fun calmMoteurAfterCutNeedsLongSteadyStretch() {
        val r = Run(PowerDetector(initialSource = Source.KAHRABA))
        r.run(1 * min) { kahraba(it) }
        r.run(3_000, plugged = false) { 0.0 }
        r.run(15 * min) { kahraba(it) }
        assertEquals(Source.MOTEUR, r.d.source)           // no flicker seen yet, so wait
        r.run(16 * min) { kahraba(it) }
        assertEquals(Source.KAHRABA, r.d.source)          // steady for 30+ min
    }

    @Test
    fun longOutageThenPowerBack() {
        val r = Run(PowerDetector(initialSource = Source.KAHRABA))
        r.run(1 * min) { kahraba(it) }
        r.run(5 * min, plugged = false) { 0.0 }
        assertEquals(Source.NO_POWER, r.d.source)
        r.run(1_000) { kahraba(it) }
        assertEquals(Source.MOTEUR, r.d.source)
    }

    @Test
    fun shadowWhileChargingIsNotACut() {
        val r = Run(PowerDetector(initialSource = Source.KAHRABA))
        r.run(2 * min) { kahraba(it) }
        r.run(5_000) { 50.0 }                             // someone leans over the phone
        r.run(2 * min) { kahraba(it) }
        assertEquals(Source.KAHRABA, r.d.source)
        assertTrue(r.transitions.isEmpty())
    }

    @Test
    fun lightCutDetectedWhenNotOnCharger() {
        val r = Run(PowerDetector(initialSource = Source.KAHRABA))
        r.run(1 * min, plugged = false) { kahraba(it) }
        r.run(3_000, plugged = false) { 2.0 }
        r.run(2_000, plugged = false) { kahraba(it) }
        assertEquals(Source.MOTEUR, r.d.source)
    }

    @Test
    fun quickCheckVerdicts() {
        val steady = QuickCheck(1.5, 15.0)
        val wobbly = QuickCheck(1.5, 15.0)
        val dark = QuickCheck(1.5, 15.0)
        var t = 0L
        while (t <= 10_000) {
            steady.add(t, kahraba(t))
            wobbly.add(t, moteur(5_000)(t))
            dark.add(t, 3.0)
            t += step
        }
        assertEquals(QuickCheck.Verdict.KAHRABA, steady.result().verdict)
        assertEquals("Low", steady.result().confidence)
        assertEquals(QuickCheck.Verdict.MOTEUR, wobbly.result().verdict)
        assertEquals(QuickCheck.Verdict.CANT_TELL, dark.result().verdict)
    }
}
