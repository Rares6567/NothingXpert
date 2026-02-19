package com.nothingxpert

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat

class MiscSettingsFragment : BasePreferenceFragment() {

    private val logTag = "NothingXpert"

    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        // Delay to allow async apply() to write file, then fix permissions
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (isAdded) {
                PrefsUtil.ensurePrefsAccessible(requireContext())
                PreferenceUtils.fixPermissions(requireContext())
            }
        }, 100)
        if (key == HookEntry.PREF_HIDE_IME_BAR) {
            val enabled = prefs?.getBoolean(HookEntry.PREF_HIDE_IME_BAR, false) ?: false
            val intent = android.content.Intent(HookEntry.ACTION_IME_BAR_TOGGLED).apply {
                putExtra(HookEntry.EXTRA_IME_BAR_ENABLED, enabled)
            }
            requireContext().sendBroadcast(intent)
            Thread { forceStopGboardRoot() }.start()
        }
    }

    private fun forceStopGboardRoot() {
        val pkg = HookEntry.GBOARD_PKG
        val commands = listOf("am force-stop $pkg", "killall $pkg")
        for (cmd in commands) {
            try {
                val proc = ProcessBuilder("su", "-c", cmd)
                    .redirectErrorStream(true)
                    .start()
                val exit = proc.waitFor()
                if (exit == 0) {
                    Log.i(logTag, "force-stopped $pkg via root ($cmd)")
                    return
                }
            } catch (t: Throwable) {
                Log.w(logTag, "root force-stop failed for $pkg: $t")
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // MODE_WORLD_READABLE is required for Xposed module compatibility
        // We also manually fix permissions via fixPermissions()
        preferenceManager.sharedPreferencesName = "${requireContext().packageName}_preferences"
        preferenceManager.sharedPreferencesMode = Context.MODE_WORLD_READABLE
        setPreferencesFromResource(R.xml.misc_preferences, rootKey)
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

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (preference.key == HookEntry.PREF_UNDISMISSABLE_NOTIFS) {
            startActivity(Intent(requireContext(), UndismissableNotifsSettingsActivity::class.java))
            return true
        }
        return super.onPreferenceTreeClick(preference)
    }
}
