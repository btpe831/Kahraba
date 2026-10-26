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
        r.run(3 * min) { kahraba(it) }
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
        r.run(30 * min, lux = moteur(15_000))             // moteur, flickers every 15 s
        assertEquals(Source.MOTEUR, r.d.source)
        r.run(3 * min) { kahraba(it) }                   // silent switch to kahraba
        assertEquals(Source.KAHRABA, r.d.source)
        assertEquals(listOf(Source.MOTEUR, Source.KAHRABA), r.transitions.map { it.to })
    }

    @Test
    fun calmMoteurAfterCutNeedsLongSteadyStretch() {
        val r = Run(PowerDetector(initialSource = Source.KAHRABA))
        r.run(1 * min) { kahraba(it) }
        r.run(3_000, plugged = false) { 0.0 }
        r.run(4 * min) { kahraba(it) }
        assertEquals(Source.MOTEUR, r.d.source)           // no flicker seen yet, so wait
        r.run(3 * min) { kahraba(it) }
        assertEquals(Source.KAHRABA, r.d.source)          // steady for 3x the steady time
    }

    @Test
    fun longOutageThenPowerBack() {
        val r = Run(PowerDetector(initialSource = Source.KAHRABA))
        r.run(1 * min) { kahraba(it) }
        r.run(6 * min, plugged = false) { 0.0 }
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
        assertEquals("Medium", steady.result().confidence)
        assertEquals(QuickCheck.Verdict.MOTEUR, wobbly.result().verdict)
        assertEquals(QuickCheck.Verdict.CANT_TELL, dark.result().verdict)
    }

    // ---- Replay of the real recording from 26 Oct 2026 (17:24–18:26) ----
    // Kahraba until the cut at 18:04:19, dark for 75 s, then moteur (lamp post green).

    private fun recording(): List<Pair<Long, Double>> =
        javaClass.classLoader!!.getResourceAsStream("recording-2026-10-26.csv")!!.bufferedReader()
            .readLines().filter { it.isNotEmpty() && it[0].isDigit() }
            .map { line -> line.split(",").let { (it[0].toDouble() * 1000).toLong() to it[1].toDouble() } }

    private fun replay(d: PowerDetector, useCharger: Boolean): MutableList<Transition> {
        val out = mutableListOf<Transition>()
        val darkFrom = 2401_000L   // 18:04:19
        val darkTo = 2476_000L     // 18:05:34
        for ((t, lux) in recording()) {
            val plugged = if (useCharger) t !in darkFrom until darkTo else false
            out += d.tick(t, lux, plugged)
        }
        return out
    }

    @Test
    fun realRecordingWithCharger() {
        val d = PowerDetector(initialSource = Source.KAHRABA)
        val ts = replay(d, useCharger = true)
        assertEquals(listOf(Source.MOTEUR), ts.map { it.to })
        assertTrue(ts[0].timeMs in 2476_000L..2477_000L)
        assertEquals(Source.MOTEUR, d.source)
    }

    @Test
    fun realRecordingWithoutCharger() {
        val d = PowerDetector(initialSource = Source.KAHRABA)
        val ts = replay(d, useCharger = false)
        assertEquals(listOf(Source.MOTEUR), ts.map { it.to })
        assertEquals(Source.MOTEUR, d.source)
    }

    @Test
    fun realRecordingFromUnknown() {
        val d = PowerDetector()
        val ts = replay(d, useCharger = true)
        assertEquals(listOf(Source.KAHRABA, Source.MOTEUR), ts.map { it.to })
    }

    @Test
    fun realMoteurThenKahrabaComesBackSilently() {
        val d = PowerDetector(initialSource = Source.KAHRABA)
        replay(d, useCharger = true)
        val end = recording().last().first
        var t = end
        var switchedAt: Long? = null
        while (t < end + 5 * min) {
            t += step
            if (d.tick(t, 862.31, true).any { it.to == Source.KAHRABA }) switchedAt = t
        }
        assertEquals(Source.KAHRABA, d.source)
        println("Kahraba detected ${(switchedAt!! - end) / 1000}s after the silent switch")
        assertTrue(switchedAt - end <= 150_000)
    }

    @Test
    fun quickCheckOnRealData() {
        val rec = recording()
        fun check(fromMs: Long, seconds: Int): QuickCheck.Verdict {
            val q = QuickCheck(3.0, 15.0)
            rec.filter { it.first in fromMs..(fromMs + seconds * 1000L) }.forEach { q.add(it.first, it.second) }
            return q.result().verdict
        }
        // Every 30 s check on kahraba says kahraba; every 30 s check on moteur says moteur.
        for (start in 60_000L until 1_900_000L step 60_000L) assertEquals(QuickCheck.Verdict.KAHRABA, check(start, 30))
        for (start in 2_490_000L until 3_660_000L step 30_000L) assertEquals(QuickCheck.Verdict.MOTEUR, check(start, 30))
        // 10 s checks on moteur: almost all should say moteur.
        val tenSec = (2_490_000L until 3_680_000L step 10_000L).map { check(it, 10) }
        val right = tenSec.count { it == QuickCheck.Verdict.MOTEUR }
        println("10 s quick checks on moteur: $right / ${tenSec.size} correct")
        assertTrue(right >= tenSec.size * 0.9)
    }
}
