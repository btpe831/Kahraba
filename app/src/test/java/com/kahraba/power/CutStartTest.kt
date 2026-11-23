package com.kahraba.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.5.21: a power cut is told to the other phones the moment it happens (the charger off and the light
 * out together), and a short blink is followed by "power's back". The Pixel 2 recording of 17-19 Nov
 * (see [NightStepCableTest]): cuts at 10:02 AM and 11:01 PM on 18 Nov, a touched cable at 5:59 AM (the
 * light stayed on), a 12 s blink at 7:02 AM on 19 Nov; plus made-up light switched off with the power on.
 */
class CutStartTest {
    private fun at(day: Int, h: Int, m: Int, s: Int = 0) = ((((day - 17) * 24 + h - 23) * 60 + m) * 60 + s) * 1000L

    private val chargerOff = listOf(
        at(18, 5, 59, 52) until at(18, 6, 5, 30),
        at(18, 10, 2, 44) until at(18, 10, 3, 54),
        at(18, 23, 1, 3) until at(18, 23, 2, 15),
        at(19, 7, 2, 18) until at(19, 7, 2, 30),
    )

    @Test
    fun cutsAreToldAtOnceButNotACableOrABlinkBack() {
        val rows = javaClass.classLoader!!.getResourceAsStream("nov19-pixel2-raw-night-moteur-step-cable-blink.csv")!!
            .bufferedReader().readLines().drop(1).filter { it.isNotBlank() }.map { it.split(",") }
            .map { it[0].toLong() to it[1].toDouble() }
        val d = PowerDetector(hourOf = recordingClock(23))
        val start = at(17, 23, 15)
        d.setManual(start, Source.MOTEUR)
        val starts = mutableListOf<Pair<Long, Long>>()
        val backs = mutableListOf<Pair<Long, Long>>()
        var i = rows.indexOfFirst { it.first > start }
        var lux = rows[i - 1].second
        var t = start
        while (t <= at(19, 7, 10, 50)) {
            while (i < rows.size && rows[i].first <= t) { lux = rows[i].second; d.addReading(t, lux); i++ }
            d.tick(t, lux, chargerOff.none { t in it })
            d.takeCutStart()?.let { starts += it to t }
            d.takeBlinkBack()?.let { backs += it to t }
            d.takeLightOff()
            d.takeNotCharging()
            t += 200
        }
        // The three real cuts, each told within a second of the charger going off; not the cable.
        assertEquals("cut starts: $starts", listOf(chargerOff[1].first, chargerOff[2].first, chargerOff[3].first), starts.map { it.first })
        assertTrue("told at: $starts", starts.all { it.second - it.first <= 1_000 })
        // Only the 12 s blink came back as a blink.
        assertEquals("blinks back: $backs", 1, backs.size)
        assertTrue("${backs[0]}", backs[0].first in 11_000L..13_000L && backs[0].second in chargerOff[3].last..chargerOff[3].last + 1_000)
    }

    @Test
    fun aLightSwitchedOffIsNotAPowerCut() {
        val d = PowerDetector()
        d.setManual(0, Source.MOTEUR)
        var t = 0L
        fun run(until: Long, lux: Double, plugged: Boolean): List<Long> {
            val out = mutableListOf<Long>()
            while (t <= until) {
                d.addReading(t, lux)
                d.tick(t, lux, plugged)
                d.takeCutStart()?.let { out += it }
                d.takeLightOff()
                d.takeNotCharging()
                t += 200
            }
            return out
        }
        assertEquals(emptyList<Long>(), run(120_000, 2_000.0, true))
        // Switched off with the power on: the charger stays on.
        assertEquals(emptyList<Long>(), run(300_000, 0.0, true))
        // Then, still dark, the charger comes off (unplugged): the light wasn't on just before.
        assertEquals(emptyList<Long>(), run(400_000, 0.0, false))
    }
}
