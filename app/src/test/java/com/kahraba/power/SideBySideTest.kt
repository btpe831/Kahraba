package com.kahraba.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pixel 2 and Pixel 9 side by side under a 40 W halogen, 28 Oct 2026, 18:16–20:28 (times in ms
 * from 18:00). Moteur until 18:32:40, kahraba until 18:50:09 (it switched seamlessly), dark until
 * 19:21:49, then moteur. Kahraba was only ~9% brighter than the moteur, and the moteur crept up
 * to kahraba's brightness later in the evening.
 */
class SideBySideTest {

    private class Rec(val t: LongArray, val v: DoubleArray) {
        fun at(ms: Long): Double {
            var i = t.binarySearch(ms)
            if (i < 0) i = -i - 2
            return v[i.coerceAtLeast(0)]
        }
    }

    private fun load(phone: String): Rec {
        val rows = javaClass.classLoader!!.getResourceAsStream("oct28-$phone-side-by-side.csv")!!.bufferedReader()
            .readLines().drop(1).filter { it.isNotBlank() }.map { it.split(",") }
        return Rec(rows.map { it[0].toLong() }.toLongArray(), rows.map { it[1].toDouble() }.toDoubleArray())
    }

    private fun min(m: Int, s: Int = 0) = ((m * 60 + s) * 1000L)
    private val moteur1 = min(16, 35)..min(32, 40)
    private val kahraba = min(32, 41)..min(50, 9)
    private val dark = min(50, 10)..min(81, 49)
    private val moteur2 = min(81, 55)..min(148, 19)

    private fun truth(from: Long, to: Long): Source? = when {
        from in moteur1 && to in moteur1 -> Source.MOTEUR
        from in moteur2 && to in moteur2 -> Source.MOTEUR
        from in kahraba && to in kahraba -> Source.KAHRABA
        else -> null
    }

    /** A quick check started at [start], the way the app runs it. */
    private fun check(r: Rec, start: Long, k: Double?, m: Double?): QuickCheck.Result {
        val q = QuickCheck(3.0, 100.0, k, m)
        var i = r.t.indexOfFirst { it > start }.let { if (it < 0) r.t.size else it }
        var t = start
        while (true) {
            while (i < r.t.size && r.t[i] <= t) { q.addRaw(r.t[i], r.v[i]); i++ }
            q.add(t, r.at(t))?.let { return it }
            t += 200
        }
    }

    /** Median of the light over a span, as the app would learn it from confirmed checks. */
    private fun level(r: Rec, span: LongRange): Double {
        val vals = (span.first..span.last step 1000).map { r.at(it) }.sorted()
        return vals[vals.size / 2]
    }

    /** The room as the app learns it: kahraba's typical level, the moteur's brightest (one check a minute). */
    private fun room(r: Rec): Pair<Double, Double> {
        val moteurChecks = (moteur1.first..moteur1.last step 60_000).map { level(r, it..it + 30_000) }
        return level(r, kahraba) to moteurChecks.max()
    }

    private fun quickCheckReport(phone: String, rooms: Boolean): Map<Source, Map<QuickCheck.Verdict, Int>> {
        val r = load(phone)
        val (k, m) = if (rooms) room(r) else null to null
        val counts = mutableMapOf<Source, MutableMap<QuickCheck.Verdict, Int>>()
        var start = moteur1.first
        while (start < moteur2.last - 45_000) {
            val src = truth(start, start + 45_000)
            if (src != null) {
                val v = check(r, start, k, m).verdict
                counts.getOrPut(src) { mutableMapOf() }.merge(v, 1, Int::plus)
            }
            start += 60_000
        }
        return counts
    }

    /** The always-on monitor over the whole recording; returns its switches. */
    private fun monitorReport(phone: String, rooms: Boolean): List<Transition> {
        val r = load(phone)
        val cfg = if (rooms) room(r).let { (k, m) -> DetectorConfig(kahrabaLevel = k, moteurLevel = m) } else DetectorConfig()
        val d = PowerDetector(cfg)
        val out = mutableListOf<Transition>()
        out += d.setManual(moteur1.first, Source.MOTEUR)
        var i = 0
        var t = moteur1.first
        while (t <= moteur2.last) {
            while (i < r.t.size && r.t[i] <= t) { d.addReading(r.t[i], r.v[i]); i++ }
            // The charger is on the house power: it goes off during the outage.
            out += d.tick(t, r.at(t), t !in dark)
            t += 200
        }
        return out
    }

    @Test
    fun quickChecksNeverSayKahrabaOnTheMoteur() {
        for (phone in listOf("pixel2", "pixel9")) for (rooms in listOf(false, true)) {
            val c = quickCheckReport(phone, rooms)
            assertEquals("$phone rooms=$rooms: $c", null, c[Source.MOTEUR]?.get(QuickCheck.Verdict.KAHRABA))
            // Kahraba is never called moteur either.
            assertEquals("$phone rooms=$rooms: $c", null, c[Source.KAHRABA]?.get(QuickCheck.Verdict.MOTEUR))
        }
    }

    @Test
    fun monitorNeverSaysKahrabaOnTheMoteur() {
        for (phone in listOf("pixel2", "pixel9")) for (rooms in listOf(false, true)) {
            val sw = monitorReport(phone, rooms).filter { !it.manual }
            val wrong = sw.filter { it.to == Source.KAHRABA && (it.timeMs in moteur1 || it.timeMs in moteur2) }
            assertTrue("$phone rooms=$rooms: $wrong", wrong.isEmpty())
            // The Pixel 2 catches the silent switch to kahraba at 18:32:40 (a 14% rise), within 5
            // minutes: before 1.1.02 it was missed because the switch stepped and bounced. The
            // Pixel 9 saw only an 11.7% rise, under the 12% needed, so it stays on moteur there.
            if (phone == "pixel2") {
                assertTrue("$phone rooms=$rooms: $sw", sw.any { it.to == Source.KAHRABA && it.timeMs in kahraba.first..kahraba.first + 5 * 60_000 })
            }
            // It still sees the outage and the moteur coming back.
            assertTrue(sw.any { it.to == Source.NO_POWER && it.timeMs in dark })
            assertTrue(sw.any { it.to == Source.MOTEUR && it.timeMs >= dark.last })
        }
    }

    @Test
    fun report() {
        for (phone in listOf("pixel2", "pixel9")) for (rooms in listOf(false, true)) {
            println("$phone ${if (rooms) "room learned" else "no room"}: quick checks ${quickCheckReport(phone, rooms)}")
            val sw = monitorReport(phone, rooms).filter { !it.manual }
            println("  monitor: " + sw.joinToString("; ") {
                "%d:%02d %s (%s)".format(18 + (it.timeMs / 3_600_000), (it.timeMs / 60_000) % 60, it.to, it.reason)
            })
        }
    }
}
