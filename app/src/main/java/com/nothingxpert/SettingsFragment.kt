package com.nothingxpert

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle

class SettingsFragment : BasePreferenceFragment(), SharedPreferences.OnSharedPreferenceChangeListener {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesMode = Context.MODE_PRIVATE
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
        // Delay to allow async apply() to write file, then fix permissions
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (isAdded) makePrefsReadable(requireContext())
        }, 100)
    }

    private fun makePrefsReadable(context: Context) {
        PreferenceUtils.fixPermissions(context)
        PrefsUtil.ensurePrefsAccessible(context)
    }
}
