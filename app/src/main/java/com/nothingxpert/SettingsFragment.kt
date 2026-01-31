package com.nothingxpert

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import java.io.File

class SettingsFragment : PreferenceFragmentCompat(), SharedPreferences.OnSharedPreferenceChangeListener {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesMode = Context.MODE_WORLD_READABLE
        preferenceManager.sharedPreferencesName = "${requireContext().packageName}_preferences"
        setPreferencesFromResource(R.xml.preferences, rootKey)
        makePrefsReadable(requireContext())
    }

    override fun onResume() {
        super.onResume()
        preferenceScreen.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onPause() {
        preferenceScreen.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        makePrefsReadable(requireContext())
    }

    private fun makePrefsReadable(context: Context) {
        val name = "${context.packageName}_preferences"
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        val prefsFile = File(prefsDir, "$name.xml")
        try {
            prefsDir.setReadable(true, false)
            prefsDir.setExecutable(true, false)
            prefsFile.setReadable(true, false)
        } catch (_: Throwable) {
            // best effort; ignore
        }
    }
}
