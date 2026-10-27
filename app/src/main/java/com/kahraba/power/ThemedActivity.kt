package com.kahraba.power

import android.os.Bundle
import android.util.TypedValue
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowCompat

/** Base for every screen: applies the chosen theme and re-applies it if it changes. */
open class ThemedActivity : AppCompatActivity() {
    private var appliedTheme = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        KahrabaApp.useEnglishNumbers()
        appliedTheme = Prefs.colorTheme(this)
        theme.applyStyle(Prefs.paletteStyle(this), true)
        super.onCreate(savedInstanceState)
        val bg = TypedValue().also { theme.resolveAttribute(R.attr.appBg, it, true) }.data
        val light = Themes.byKey(appliedTheme).light
        window.statusBarColor = bg
        window.navigationBarColor = bg
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    /** Fills in the shared header (screen_header.xml): the title, and Close goes back. */
    protected fun setupHeader(title: String) {
        findViewById<android.widget.TextView>(R.id.screenTitle).text = title
        findViewById<android.view.View>(R.id.screenClose).setOnClickListener { finish() }
    }

    /** Starts another app's screen (file picker, share, settings), with a message if the phone has none. */
    protected fun launchSafely(start: () -> Unit) {
        runCatching(start).onFailure {
            android.widget.Toast.makeText(this, "This phone has no app for that", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.colorTheme(this) != appliedTheme) {
            AppCompatDelegate.setDefaultNightMode(Prefs.nightMode(this))
            recreate()
        }
    }
}
