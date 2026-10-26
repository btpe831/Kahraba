package com.karaba.power

import android.os.Bundle
import android.util.TypedValue
import androidx.appcompat.app.AppCompatActivity

/** Base for every screen: applies the chosen colour theme and re-applies it if it changes. */
open class ThemedActivity : AppCompatActivity() {
    private var appliedTheme = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        appliedTheme = Prefs.colorTheme(this)
        theme.applyStyle(Prefs.paletteStyle(this), true)
        super.onCreate(savedInstanceState)
        val bg = TypedValue().also { theme.resolveAttribute(R.attr.appBg, it, true) }.data
        window.statusBarColor = bg
        window.navigationBarColor = bg
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.colorTheme(this) != appliedTheme) recreate()
    }
}
