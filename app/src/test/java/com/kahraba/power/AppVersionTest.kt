package com.kahraba.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    @Test
    fun comparesVersions() {
        assertTrue(AppVersion.isNewer("1.5.13", "1.5.12"))
        assertTrue(AppVersion.isNewer("1.6.0", "1.5.50"))
        assertTrue(AppVersion.isNewer("1.5.01", "1.5.0"))
        assertFalse(AppVersion.isNewer("1.5.12", "1.5.12"))
        assertFalse(AppVersion.isNewer("1.5.11", "1.5.12"))
        // Test builds ("1.5.12 test 3") never update themselves.
        assertFalse(AppVersion.isNewer("1.5.13", "1.5.12 test 3"))
    }

    @Test
    fun readsTheApkNameAndTheNotes() {
        assertEquals("1.5.13", AppVersion.fromApkName("KahrabaOrNot-1.5.13.apk"))
        assertNull(AppVersion.fromApkName("KahrabaOrNot-1.5.13.apk.sha256"))
        val body = "## What's new in 1.5.13\n\n- Update from inside the app.\n- A fix.\n\n## Install\n\nOn the Android phone…"
        assertEquals("- Update from inside the app.\n- A fix.", AppVersion.notesFrom(body))
    }
}
