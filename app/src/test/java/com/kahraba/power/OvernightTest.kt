package com.kahraba.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Pixel 2 left overnight under a halogen (~30 cm away, 140–184 lux), 28–29 Oct 2026, with the
 * always-on monitor running. Its real switch log: power cut 00:15:23, moteur from 00:16:44 ("Power
 * cut (1m 18s) – moteur took over"); kahraba came back silently at 06:29:43 and the monitor said so
 * at 06:31:49 ("Light got 20% brighter"). The moteur was calm all night.
 */
class OvernightTest {

    private class Rec(val t: LongArray, val v: DoubleArray) {
        fun at(ms: Long): Double {
            var i = t.binarySearch(ms)
            if (i < 0) i = -i - 2
            return v[i.coerceAtLeast(0)]
        }
        val start get() = t.first()
        val end get() = t.last()
    }

    private fun load(name: String): Rec {
        val rows = javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader()
            .readLines().drop(1).filter { it.isNotBlank() }.map { it.split(",") }
        return Rec(rows.map { it[0].toLong() }.toLongArray(), rows.map { it[1].toDouble() }.toDoubleArray())
    }

    private fun hms(h: Int, m: Int, s: Int) = ((h * 3600 + m * 60 + s) * 1000L)

    // Night file: ms from 22:00 on 28 Oct.
    private val night by lazy { load("oct28-night-pixel2-kahraba-cut-moteur.csv") }
    private val nightKahraba get() = night.start..hms(2, 15, 22)
    private val chargerOff = hms(2, 15, 24) until hms(2, 16, 42)
    private val nightMoteur get() = hms(2, 17, 30)..night.end

    // Morning file: ms from 06:00 on 29 Oct.
    private val morning by lazy { load("oct29-morning-pixel2-moteur-kahraba.csv") }
    private val switchToKahraba = hms(0, 29, 43)
    private val morningMoteur get() = morning.start..switchToKahraba - 1000
    private val morningKahraba get() = switchToKahraba + 1000..morning.end

    private fun monitor(r: Rec, startAs: Source, pluggedAt: (Long) -> Boolean): List<Transition> {
        val d = PowerDetector()
        d.setManual(r.start, startAs)
        val out = mutableListOf<Transition>()
        var i = 0
        var t = r.start
        while (t <= r.end) {
            while (i < r.t.size && r.t[i] <= t) { d.addReading(r.t[i], r.v[i]); i++ }
            out += d.tick(t, r.at(t), pluggedAt(t))
            t += 200
        }
        return out
    }

    private fun check(r: Rec, start: Long): QuickCheck.Verdict {
        val q = QuickCheck(3.0, 100.0)
        var i = r.t.indexOfFirst { it > start }.let { if (it < 0) r.t.size else it }
        var t = start
        while (true) {
            while (i < r.t.size && r.t[i] <= t) { q.addRaw(r.t[i], r.v[i]); i++ }
            q.add(t, r.at(t))?.let { return it.verdict }
            t += 200
        }
    }

    /** A quick check every [everyMs] within [span], ending before it does. */
    private fun checks(r: Rec, span: LongRange, everyMs: Long): List<QuickCheck.Verdict> =
        (span.first..(span.last - QuickCheck.MAX_MS - 5_000) step everyMs).map { check(r, it) }

    @Test
    fun monitorSeesTheCutAndTheMoteurAndNothingElseOvernight() {
        val sw = monitor(night, Source.KAHRABA) { it !in chargerOff }.filter { !it.manual }
        assertEquals("switches: $sw", listOf(Source.MOTEUR), sw.map { it.to })
        // As in the real log: moteur as soon as the charger came back.
        assertTrue("at ${sw[0].timeMs}", sw[0].timeMs in chargerOff.last..chargerOff.last + 10_000)
    }

    @Test
    fun monitorSeesKahrabaComeBackInTheMorning() {
        val sw = monitor(morning, Source.MOTEUR) { true }.filter { !it.manual }
        assertEquals("switches: $sw", listOf(Source.KAHRABA), sw.map { it.to })
        // The real monitor said so at 06:31:49, about two minutes after the switch.
        assertTrue("at ${sw[0].timeMs}", sw[0].timeMs in switchToKahraba..switchToKahraba + 4 * 60_000)
    }

    @Test
    fun quickChecksNeverMixThemUp() {
        val onMoteur = checks(night, nightMoteur, 5 * 60_000) + checks(morning, morningMoteur, 60_000)
        val onKahraba = checks(night, nightKahraba, 5 * 60_000) + checks(morning, morningKahraba, 60_000)
        assertTrue("moteur checks: ${onMoteur.groupingBy { it }.eachCount()}", QuickCheck.Verdict.KAHRABA !in onMoteur)
        assertTrue("kahraba checks: ${onKahraba.groupingBy { it }.eachCount()}", QuickCheck.Verdict.MOTEUR !in onKahraba)
        println("Overnight quick checks: moteur ${onMoteur.groupingBy { it }.eachCount()}, kahraba ${onKahraba.groupingBy { it }.eachCount()}")
    }
}
