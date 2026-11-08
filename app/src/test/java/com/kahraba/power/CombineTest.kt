package com.kahraba.power

import org.junit.Assert.assertEquals
import org.junit.Test

class CombineTest {
    @Test
    fun switchLogsJoinInTimeOrder() {
        val official = listOf("100;MOTEUR;Power cut", "300;KAHRABA;Light got brighter")
        val test = listOf("300;KAHRABA;Light got brighter", "200;UNKNOWN;Always-on mode stopped", "400;MOTEUR;Set by you", "bad line")
        assertEquals(
            listOf("100;MOTEUR;Power cut", "200;UNKNOWN;Always-on mode stopped", "300;KAHRABA;Light got brighter", "400;MOTEUR;Set by you"),
            Combine.log(official, test),
        )
    }

    @Test
    fun checksKeepBothAndThisPhoneWins() {
        val here = "300;KAHRABA;1;4100.0;Kitchen;0;0;\n100;MOTEUR;0;3000.0;Kitchen;0;0;"
        val backup = "300;MOTEUR;0;3900.0;Kitchen;0;0;\n200;NO_FLICKER;0;;;0;0;"
        assertEquals(
            "300;KAHRABA;1;4100.0;Kitchen;0;0;\n200;NO_FLICKER;0;;;0;0;\n100;MOTEUR;0;3000.0;Kitchen;0;0;",
            Combine.checks(here, backup),
        )
        assertEquals("300;KAHRABA;1;4100.0;Kitchen;0;0;", Combine.checks(here, backup, keep = 1))
    }

    @Test
    fun roomsAndNotes() {
        assertEquals("Kitchen\nGuest bathroom\nMain Bathroom", Combine.rooms("Kitchen\nGuest bathroom", "Main Bathroom\nKitchen\n"))
        assertEquals("Moteur off at 8", Combine.note("", "Moteur off at 8"))
        assertEquals("Hot day", Combine.note("Hot day", "Hot day"))
        assertEquals("Hot day\n\nGenerator fixed", Combine.note("Hot day", "Generator fixed"))
    }
}
