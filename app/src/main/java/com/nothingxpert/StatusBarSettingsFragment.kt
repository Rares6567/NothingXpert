package com.nothingxpert

import android.content.Context
import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat

class StatusBarSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = "${requireContext().packageName}_preferences"
        preferenceManager.sharedPreferencesMode = Context.MODE_WORLD_READABLE
        setPreferencesFromResource(R.xml.preferences_status_bar, rootKey)
    }
}
