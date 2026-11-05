package com.kahraba.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WhatsNewTest {
    private val notes by lazy {
        val f = listOf("src/main/assets/whats_new.txt", "app/src/main/assets/whats_new.txt")
            .map(::File).firstOrNull { it.exists() } ?: File("/home/user/Karaba/app/src/main/assets/whats_new.txt")
        WhatsNew.parse(f.readText())
    }

    @Test
    fun versionsCompareByNumber() {
        assertTrue(WhatsNew.compare("1.0.10", "1.1.0") < 0)
        assertTrue(WhatsNew.compare("1.1.04", "1.2.0") < 0)
        assertTrue(WhatsNew.compare("1.2.0", "1.2.01") < 0)
        assertEquals(0, WhatsNew.compare("1.2.01", "1.2.1"))
        assertEquals(0, WhatsNew.compare("1.5.0 test 212", "1.5.0"))
        assertTrue(WhatsNew.compare("1.4.10 test 212", "1.4.11") < 0)
    }

    @Test
    fun showsWhatCameAfterTheOldVersion() {
        assertEquals(listOf("1.2.01"), WhatsNew.since(notes, "1.2.0", "1.2.01").map { it.version })
        assertEquals(listOf("1.2.01", "1.2.0"), WhatsNew.since(notes, "1.1.04", "1.2.01").map { it.version })
        // Long skipped: the latest three.
        assertEquals(3, WhatsNew.since(notes, "1.0.0", "1.2.01").size)
        assertTrue(WhatsNew.since(notes, "1.2.01", "1.2.01").isEmpty())
    }

    @Test
    fun theCurrentVersionHasNotes() {
        val version = listOf("gradle.properties", "../gradle.properties", "/home/user/Karaba/gradle.properties")
            .map(::File).filter { it.exists() }.flatMap { it.readLines() }
            .first { it.startsWith("appVersion=") }.substringAfter("=").trim()
        assertTrue("whats_new.txt needs a \"## $version\" section", notes.any { it.version == version && it.items.isNotEmpty() })
    }
}
