package com.kahraba.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class PowerDetectorTest {

    private val step = 200L
    private val min = 60_000L

    /** Kahraba: steady light with tiny sensor noise (like the 862–868 lx reading). */
    private fun kahraba(t: Long) = 865.0 + 1.5 * sin(t / 3000.0)

    /** Moteur: dimmer than kahraba (~680 lx, like 213 V vs 234 V) with a 10% dip every [every] ms. */
    private fun moteur(every: Long) = { t: Long -> if (t % every < 1500) 612.0 else 680.0 }

    /** A calm moteur: perfectly steady, just dimmer (like the 21:55–22:13 stretch on 26 Oct). */
    private fun calmMoteur(@Suppress("UNUSED_PARAMETER") t: Long) = 697.0

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
    fun steadyLightAloneProvesNothing() {
        // Steady could be kahraba or a calm moteur, so from "not sure" it stays not sure.
        val r = Run(PowerDetector())
        r.run(20 * min) { kahraba(it) }
        assertEquals(Source.UNKNOWN, r.d.source)
    }

    @Test
    fun calmMoteurSetByUserStaysMoteur() {
        // The bug: user tapped "It's moteur", the light stayed steady, and it switched to kahraba.
        val r = Run(PowerDetector())
        r.d.setManual(0, Source.MOTEUR)
        r.run(60 * min) { calmMoteur(it) }
        assertEquals(Source.MOTEUR, r.d.source)
        assertTrue(r.transitions.isEmpty())
    }

    @Test
    fun learnedLevelsDecideFromUnknown() {
        val cfg = DetectorConfig(kahrabaLevel = 862.0, moteurLevel = 680.0)
        val k = Run(PowerDetector(cfg))
        k.run(3 * min) { kahraba(it) }
        assertEquals(Source.KAHRABA, k.d.source)
        val m = Run(PowerDetector(cfg))
        m.run(3 * min) { calmMoteur(it) }
        assertEquals(Source.MOTEUR, m.d.source)
    }

    @Test
    fun anotherLampSwitchedOnIsNotKahraba() {
        val r = Run(PowerDetector())
        r.d.setManual(0, Source.MOTEUR)
        r.run(10 * min) { calmMoteur(it) }
        r.run(10 * min) { 1400.0 }                       // +100%: another light, not the voltage
        assertEquals(Source.MOTEUR, r.d.source)
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
    fun calmMoteurThenSilentSwitch() {
        val r = Run(PowerDetector(initialSource = Source.KAHRABA))
        r.run(1 * min) { kahraba(it) }
        r.run(3_000, plugged = false) { 0.0 }
        r.run(30 * min) { calmMoteur(it) }
        assertEquals(Source.MOTEUR, r.d.source)           // steady, but no brighter: still moteur
        r.run(3 * min) { kahraba(it) }                    // +24% brighter and steady
        assertEquals(Source.KAHRABA, r.d.source)
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

    /** Feeds readings every 200 ms until the quick check answers. Returns the verdict and seconds taken. */
    private fun quick(fromMs: Long, lux: (Long) -> Double): Pair<QuickCheck.Verdict, Long> {
        val q = QuickCheck(3.0, 100.0)
        var t = fromMs
        while (true) {
            q.add(t, lux(t))?.let { return it.verdict to (t - fromMs) / 1000 }
            t += step
        }
    }

    @Test
    fun quickCheckVerdicts() {
        assertEquals(QuickCheck.Verdict.NO_FLICKER to 25L, quick(0) { kahraba(it) })
        assertEquals(QuickCheck.Verdict.MOTEUR, quick(0, moteur(5_000)).first)
        // Too dim (like the 26 lux check on moteur that wrongly said kahraba): give up fast.
        val dim = quick(0) { 26.0 }
        assertEquals(QuickCheck.Verdict.CANT_TELL, dim.first)
        assertTrue(dim.second <= 8)
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
        assertEquals(listOf(Source.MOTEUR), ts.map { it.to })
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
        // The recording at a given time, held between readings (like the sensor).
        fun at(t: Long): Double {
            var i = rec.binarySearch { it.first.compareTo(t) }
            if (i < 0) i = -i - 2
            return rec[i.coerceIn(0, rec.size - 1)].second
        }
        // Start a check every 10 s through each part of the recording.
        val onKahraba = (60_000L until 1_900_000L step 10_000L).map { quick(it, ::at) }
        val onMoteur = (2_490_000L until 3_660_000L step 10_000L).map { quick(it, ::at) }
        assertTrue(onKahraba.all { it.first == QuickCheck.Verdict.NO_FLICKER })
        assertTrue(onMoteur.all { it.first == QuickCheck.Verdict.MOTEUR })
        val times = onMoteur.map { it.second }.sorted()
        println("Quick check on moteur: ${onMoteur.size} checks, all right; " +
            "median ${times[times.size / 2]}s, slowest ${times.last()}s")
    }

    // ---- Replay of the calm moteur recording from 26 Oct 2026 (21:54–22:30, lamp post green) ----

    private fun calmRecording(): List<Pair<Long, Double>> =
        javaClass.classLoader!!.getResourceAsStream("calm-moteur-2026-10-26.csv")!!.bufferedReader()
            .readLines().filter { it.isNotEmpty() && it[0].isDigit() }
            .map { line -> line.split(",").let { (it[0].toDouble() * 1000).toLong() to it[1].toDouble() } }

    @Test
    fun realCalmMoteurStaysMoteur() {
        // Feed every reading plus 5-per-second samples, the way the app does.
        val d = PowerDetector()
        d.setManual(0, Source.MOTEUR)
        val rec = calmRecording()
        val ts = mutableListOf<Transition>()
        var i = 0
        var t = 0L
        var last = rec[0].second
        while (t <= rec.last().first) {
            while (i < rec.size && rec[i].first <= t) {
                last = rec[i].second
                d.addReading(rec[i].first, last)
                i++
            }
            ts += d.tick(t, last, true)
            t += step
        }
        assertTrue("switched: ${ts.map { it.reason }}", ts.isEmpty())
        assertEquals(Source.MOTEUR, d.source)
    }

    @Test
    fun quickCheckOnCalmMoteurNeverSaysKahraba() {
        val rec = calmRecording()
        fun at(t: Long): Double {
            var i = rec.binarySearch { it.first.compareTo(t) }
            if (i < 0) i = -i - 2
            return rec[i.coerceIn(0, rec.size - 1)].second
        }
        val results = (10_000L until 2_140_000L step 30_000L).map { quick(it, ::at).first }
        assertTrue(results.none { it == QuickCheck.Verdict.KAHRABA })
        println("Quick checks on calm moteur: ${results.count { it == QuickCheck.Verdict.MOTEUR }} moteur, " +
            "${results.count { it == QuickCheck.Verdict.NO_FLICKER }} no flicker, of ${results.size}")
    }

    // ---- Quick check at a learned spot ----

    private fun quickAt(k: Double?, m: Double?, fromMs: Long, lux: (Long) -> Double): Pair<QuickCheck.Verdict, Long> {
        val q = QuickCheck(3.0, 100.0, k, m)
        var t = fromMs
        while (true) {
            q.add(t, lux(t))?.let { return it.verdict to (t - fromMs) / 1000 }
            t += step
        }
    }

    @Test
    fun spotLevelsGiveSureAnswers() {
        // Kitchen learned: kahraba 862, moteur 680.
        val kahrabaHere = quickAt(862.0, 680.0, 0) { kahraba(it) }
        assertEquals(QuickCheck.Verdict.KAHRABA, kahrabaHere.first)
        assertTrue(kahrabaHere.second <= 15)
        assertEquals(QuickCheck.Verdict.MOTEUR, quickAt(862.0, 680.0, 0) { calmMoteur(it) }.first)
        // Brightness that matches neither (phone not at the spot): no sure answer.
        assertEquals(QuickCheck.Verdict.NO_FLICKER, quickAt(862.0, 680.0, 0) { 1500.0 }.first)
        // Levels too close together aren't used.
        assertEquals(QuickCheck.Verdict.NO_FLICKER, quickAt(700.0, 680.0, 0) { 700.0 }.first)
        // Only one level learned: not used yet.
        assertEquals(QuickCheck.Verdict.NO_FLICKER, quickAt(862.0, null, 0) { kahraba(it) }.first)
    }

    @Test
    fun spotLevelsOnRealRecordings() {
        // Levels as if learned from the 26 Oct recordings at the same spot.
        val k = 862.0
        val m = 680.0
        fun atOf(rec: List<Pair<Long, Double>>) = { t: Long ->
            var i = rec.binarySearch { it.first.compareTo(t) }
            if (i < 0) i = -i - 2
            rec[i.coerceIn(0, rec.size - 1)].second
        }
        val busy = recording()
        val calm = calmRecording()
        val onKahraba = (60_000L until 1_900_000L step 30_000L).map { quickAt(k, m, it, atOf(busy)).first }
        val onBusyMoteur = (2_490_000L until 3_660_000L step 30_000L).map { quickAt(k, m, it, atOf(busy)).first }
        val onCalmMoteur = (10_000L until 2_140_000L step 30_000L).map { quickAt(k, m, it, atOf(calm)).first }
        assertTrue(onKahraba.all { it == QuickCheck.Verdict.KAHRABA })
        assertTrue(onBusyMoteur.all { it == QuickCheck.Verdict.MOTEUR })
        assertTrue(onCalmMoteur.all { it == QuickCheck.Verdict.MOTEUR })
        println("At a learned spot: kahraba ${onKahraba.size}/${onKahraba.size}, busy moteur " +
            "${onBusyMoteur.size}/${onBusyMoteur.size}, calm moteur ${onCalmMoteur.size}/${onCalmMoteur.size} right")
    }

    @Test
    fun movingTheMonitorPhoneIsNotKahraba() {
        // Calm moteur, then the phone is picked up and put down somewhere brighter.
        val r = Run(PowerDetector())
        r.d.setManual(0, Source.MOTEUR)
        r.run(10 * min) { calmMoteur(it) }
        r.d.onMotion(r.t)
        r.run(10 * min) { kahraba(it) }                   // +24% brighter, but only because it moved
        assertEquals(Source.MOTEUR, r.d.source)
        // Power cuts are still caught while paused.
        r.d.onMotion(r.t)
        r.run(3_000, plugged = false) { 0.0 }
        r.run(1_000) { calmMoteur(it) }
        assertEquals(1, r.transitions.count { it.reason.startsWith("Power cut") })
    }

    @Test
    fun oneDipOnKahrabaIsNotMoteur() {
        // A kettle switching on dips the voltage once, then the light settles (a bit dimmer).
        val r = quick(0) { t -> if (t < 8_000) 865.0 else if (t < 8_400) 760.0 else 845.0 }
        assertTrue("got ${r.first}", r.first != QuickCheck.Verdict.MOTEUR)
    }

    @Test
    fun repeatedWobbleIsMoteur() {
        val r = quick(0, moteur(5_000))
        assertEquals(QuickCheck.Verdict.MOTEUR, r.first)
    }

    @Test
    fun oneOffJumpsOnKahrabaAreNotMoteur() {
        // 26 Oct, 17:57:50 and 18:02:03: two one-off light jumps while on kahraba (lamp post off).
        val rec = recording()
        fun at(t: Long): Double {
            var i = rec.binarySearch { it.first.compareTo(t) }
            if (i < 0) i = -i - 2
            return rec[i.coerceIn(0, rec.size - 1)].second
        }
        val starts = (1_960_000L until 2_000_000L step 2_000L) + (2_210_000L until 2_250_000L step 2_000L)
        val results = starts.map { quick(it, ::at).first }
        val moteur = results.count { it == QuickCheck.Verdict.MOTEUR }
        println("Checks spanning the one-off jumps on kahraba: $moteur of ${results.size} said moteur")
        assertEquals(0, moteur)
    }
}
