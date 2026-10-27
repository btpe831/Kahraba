package com.kahraba.power

import com.kahraba.power.QuickCheck.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CheckHistoryTest {

    private fun entry(t: Long, v: Verdict) = CheckHistory.Entry(t, v)

    @Test
    fun confirmingKeepsWhatTheAppSaid() {
        val fixed = CheckHistory.corrected(entry(1, Verdict.KAHRABA), Verdict.MOTEUR)
        assertEquals(Verdict.MOTEUR, fixed.verdict)
        assertEquals(Verdict.KAHRABA, fixed.said)
        // Fixing it again still remembers the app's own answer.
        val again = CheckHistory.corrected(fixed, Verdict.KAHRABA)
        assertEquals(Verdict.KAHRABA, again.said)
    }

    @Test
    fun oldConfirmationsAreNotScored() {
        // Confirmed before the app kept what it said: unknown, so left out of the score.
        val old = CheckHistory.Entry(1, Verdict.MOTEUR, corrected = true)
        assertNull(CheckHistory.corrected(old, Verdict.KAHRABA).said)
        assertEquals(0, CheckHistory.scores(listOf(old)).sumOf { it.total })
    }

    @Test
    fun scoresEachKindOfResult() {
        val list = listOf(
            CheckHistory.corrected(entry(1, Verdict.MOTEUR), Verdict.MOTEUR),
            CheckHistory.corrected(entry(2, Verdict.MOTEUR), Verdict.KAHRABA),
            CheckHistory.corrected(entry(3, Verdict.KAHRABA), Verdict.KAHRABA),
            CheckHistory.corrected(entry(4, Verdict.NO_FLICKER), Verdict.KAHRABA),
            CheckHistory.corrected(entry(5, Verdict.NO_FLICKER), Verdict.MOTEUR),
            entry(6, Verdict.MOTEUR), // never confirmed
            CheckHistory.Entry(7, Verdict.KAHRABA, corrected = true, fromMonitor = true, said = Verdict.KAHRABA),
        )
        val s = CheckHistory.scores(list).associateBy { it.said }
        assertEquals(1, s[Verdict.MOTEUR]!!.right); assertEquals(2, s[Verdict.MOTEUR]!!.total)
        assertEquals(1, s[Verdict.KAHRABA]!!.right); assertEquals(1, s[Verdict.KAHRABA]!!.total)
        assertEquals(1, s[Verdict.NO_FLICKER]!!.right); assertEquals(2, s[Verdict.NO_FLICKER]!!.total)
    }
}
