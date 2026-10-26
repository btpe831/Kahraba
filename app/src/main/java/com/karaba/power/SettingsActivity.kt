package com.karaba.power

import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceFragmentCompat

class SettingsActivity : ThemedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            findPreference<androidx.preference.Preference>(Prefs.APPEARANCE)?.setOnPreferenceChangeListener { _, value ->
                // Takes effect once the setting is saved, so post it.
                view?.post { AppCompatDelegate.setDefaultNightMode(Prefs.nightMode(requireContext())) }
                true
            }
            findPreference<androidx.preference.Preference>(Prefs.COLOR_THEME)?.setOnPreferenceChangeListener { _, _ ->
                view?.post { activity?.recreate() }
                true
            }
        }
    }
}
