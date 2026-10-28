package com.kahraba.power

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class KahrabaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        useEnglishNumbers()
        Prefs.cleanUpOldDefaults(this)
        AppCompatDelegate.setDefaultNightMode(Prefs.nightMode(this))
    }

    companion object {
        /**
         * The app is in English: show numbers as 3.5, not ٣٫٥ (Arabic) or 3,5 (French), whatever
         * the phone's language. Applied again by each screen, as Android resets it when the
         * phone's language changes.
         */
        fun useEnglishNumbers() {
            if (java.util.Locale.getDefault() != java.util.Locale.US) java.util.Locale.setDefault(java.util.Locale.US)
        }
    }
}
