package com.karaba.power

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class IjitApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(Prefs.nightMode(this))
    }
}
