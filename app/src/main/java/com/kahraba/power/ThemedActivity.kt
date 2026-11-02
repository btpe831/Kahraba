package com.kahraba.power

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.util.TypedValue
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowCompat

/** Base for every screen: applies the chosen theme (its light or dark version) and re-applies it if it changes. */
open class ThemedActivity : AppCompatActivity() {
    private var appliedTheme = ""
    private var appliedDark = false
    private var appliedTextScale = 1f

    /** Text size (Settings › Text size): scales the app's text on top of the phone's own setting. */
    override fun attachBaseContext(newBase: Context) {
        val scale = runCatching { Prefs.textScale(newBase) }.getOrDefault(1f)
        appliedTextScale = scale
        if (scale == 1f) {
            super.attachBaseContext(newBase)
        } else {
            val cfg = Configuration(newBase.resources.configuration)
            cfg.fontScale = cfg.fontScale * scale
            super.attachBaseContext(newBase.createConfigurationContext(cfg))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        KahrabaApp.useEnglishNumbers()
        appliedTheme = Prefs.colorTheme(this)
        appliedDark = Prefs.isDark(this)
        theme.applyStyle(Themes.byKey(appliedTheme).variant(appliedDark).style, true)
        super.onCreate(savedInstanceState)
        val bg = TypedValue().also { theme.resolveAttribute(R.attr.appBg, it, true) }.data
        val light = !appliedDark
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
        // Theme or appearance changed elsewhere (or the phone switched dark mode while away).
        if (Prefs.textScale(this) != appliedTextScale) {
            recreate()
            return
        }
        if (Prefs.colorTheme(this) != appliedTheme || Prefs.isDark(this) != appliedDark) {
            AppCompatDelegate.setDefaultNightMode(Prefs.nightMode(this))
            recreate()
        }
    }
}
