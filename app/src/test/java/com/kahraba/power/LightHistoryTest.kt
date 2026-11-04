package com.kahraba.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LightHistoryTest {
    private fun status(s: Int, source: Source, off: Boolean = false) = LightHistory.Status(s * 1000L, source, off)
    private fun reading(s: Int, lux: Double) = LightHistory.Reading(s * 1000L, lux)

    @Test
    fun readsTheSavedFiles() {
        val r = LightHistory.parseReading("766929,4706.25")!!
        assertEquals(766929L, r.timeMs)
        assertEquals(4706.25, r.lux, 0.0)
        assertNull(LightHistory.parseReading("time_ms,lux"))

        val st = LightHistory.parseStatus("2026-11-02 10:21:00,3604.00,0.000,off,KAHRABA,\"Steady, as bright as kahraba\"") {
            if (it == "2026-11-02 10:21:00") 42L else null
        }!!
        assertEquals(42L, st.timeMs)
        assertEquals(Source.KAHRABA, st.source)
        assertTrue(st.chargerOff)
        assertNull(LightHistory.parseStatus("time,lux,wobble_pct,charger,source,light_status") { 0L })
        // A line cut short while the file was being written.
        assertNull(LightHistory.parseStatus("2026-11-02 10:21:00,3604.00,0.0") { 0L })
    }

    @Test
    fun shadesWhatTheAppCalledAndLeavesGapsBlank() {
        // Moteur 0-59 s, nothing recorded 60-119 s, kahraba 120-179 s, charger off 150-159 s.
        val statuses = (0 until 60).map { status(it, Source.MOTEUR) } +
            (120 until 180).map { status(it, Source.KAHRABA, off = it in 150 until 160) }
        val readings = listOf(reading(0, 3000.0), reading(20, 2800.0), reading(25, 3000.0), reading(121, 3600.0))
        val sl = LightHistory.slices(readings, statuses, 0, 180_000, 18) // 10 s each

        assertEquals(Source.MOTEUR, sl[0].shade)
        assertEquals(3000.0, sl[0].meanLux!!, 0.01)
        // 20-30 s: the dip shows as the slice's lowest value.
        assertEquals(2800.0, sl[2].minLux!!, 0.01)
        assertEquals(3000.0, sl[2].maxLux!!, 0.01)
        assertEquals(2900.0, sl[2].meanLux!!, 0.01) // 5 s at 2800, 5 s at 3000
        // Not running: blank.
        assertFalse(sl[6].recorded)
        assertNull(sl[6].meanLux)
        // Kahraba, held from the last reading.
        assertEquals(Source.KAHRABA, sl[13].shade)
        assertEquals(3600.0, sl[13].meanLux!!, 0.01)
        // Charger off: grey, no line.
        assertEquals(Source.NO_POWER, sl[15].shade)
        assertNull(sl[15].meanLux)
    }

    @Test
    fun slicesShorterThanASecondKeepTheirStatus() {
        val statuses = (0 until 10).map { status(it, Source.MOTEUR) }
        val sl = LightHistory.slices(listOf(reading(0, 3000.0)), statuses, 0, 9_000, 36) // 0.25 s each
        assertTrue(sl.all { it.recorded && it.shade == Source.MOTEUR && it.meanLux == 3000.0 })
    }

    @Test
    fun theLiveEdgeIsNotBlank() {
        // The last row is at 29 s; the graph runs to 30 s (now).
        val statuses = (0 until 30).map { status(it, Source.KAHRABA) }
        val sl = LightHistory.slices(listOf(reading(0, 3600.0)), statuses, 0, 30_000, 150)
        assertTrue(sl.takeLast(5).all { it.recorded && it.meanLux == 3600.0 })
        // Long after the last row (Always-on mode stopped): blank.
        assertFalse(LightHistory.slices(listOf(reading(0, 3600.0)), statuses, 60_000, 90_000, 30).any { it.recorded })
    }

    @Test
    fun liveRowsOnlyAfterTheSavedOnes() {
        val saved = listOf(reading(1, 1.0), reading(2, 2.0))
        val live = listOf(reading(2, 2.0), reading(3, 3.0))
        assertEquals(listOf(1000L, 2000L, 3000L), LightHistory.merge(saved, live) { it.timeMs }.map { it.timeMs })
        assertEquals(listOf(2000L, 3000L), LightHistory.merge(emptyList(), live) { it.timeMs }.map { it.timeMs })
    }
}

class LightHistoryRelabelTest {
    private fun st(s: Int, source: Source) = LightHistory.Status(s * 1000L, source, false)

    @Test
    fun kahrabaShowsFromWhenItCameOnNotWhenItWasAnnounced() {
        // Kahraba came on at 100 s; the app said so at 400 s (the check), dating it from 100 s.
        val statuses = (0 until 500).map { st(it, if (it < 400) Source.MOTEUR else Source.KAHRABA) }
        val log = listOf(0L to Source.MOTEUR, 100_000L to Source.KAHRABA)
        val out = LightHistory.relabel(statuses, log)
        assertTrue(out.take(100).all { it.source == Source.MOTEUR })
        assertTrue(out.drop(100).all { it.source == Source.KAHRABA })
    }

    @Test
    fun aStopEndsTheRelabelling() {
        val statuses = (0 until 300).map { st(it, if (it < 200) Source.KAHRABA else Source.MOTEUR) }
        // Kahraba, then Always-on stopped at 150 s; after a restart the saved rows say moteur.
        val log = listOf(0L to Source.KAHRABA, 150_000L to Source.UNKNOWN)
        val out = LightHistory.relabel(statuses, log)
        assertTrue(out.drop(200).all { it.source == Source.MOTEUR })
    }
}
