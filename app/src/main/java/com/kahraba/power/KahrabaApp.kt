package com.kahraba.power

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class KahrabaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(Prefs.nightMode(this))
    }
}
