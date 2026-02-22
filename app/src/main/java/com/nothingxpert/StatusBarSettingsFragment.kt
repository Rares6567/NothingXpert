package com.nothingxpert

import android.content.Context
import android.os.Bundle

class StatusBarSettingsFragment : BasePreferenceFragment() {
    
    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        // Delay to allow async apply() to write file, then fix permissions
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (isAdded) {
                PrefsUtil.ensurePrefsAccessible(requireContext())
                PreferenceUtils.fixPermissions(requireContext())
            }
        }, 100)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = "${requireContext().packageName}_preferences"
        preferenceManager.sharedPreferencesMode = Context.MODE_PRIVATE
        setPreferencesFromResource(R.xml.preferences_status_bar, rootKey)
        PreferenceUtils.fixPermissions(requireContext())
        PrefsUtil.ensurePrefsAccessible(requireContext())
    }

    override fun onResume() {
        super.onResume()
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(prefListener)
        PreferenceUtils.fixPermissions(requireContext())
        PrefsUtil.ensurePrefsAccessible(requireContext())
    }

    override fun onPause() {
        super.onPause()
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(prefListener)
    }
}
