package com.nothingxpert

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.preference.PreferenceFragmentCompat

class MiscSettingsFragment : PreferenceFragmentCompat() {

    private val logTag = "NothingXpert"

    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        // Delay to allow async apply() to write file, then fix permissions
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (isAdded) PreferenceUtils.fixPermissions(requireContext())
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

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = "${requireContext().packageName}_preferences"
        // usage of MODE_WORLD_READABLE is deprecated and often ignored, so we rely on manual fixPermissions
        preferenceManager.sharedPreferencesMode = Context.MODE_WORLD_READABLE
        setPreferencesFromResource(R.xml.misc_preferences, rootKey)
        PreferenceUtils.fixPermissions(requireContext())
        PrefsUtil.makeWorldReadable(requireContext())
    }

    override fun onResume() {
        super.onResume()
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(prefListener)
        PreferenceUtils.fixPermissions(requireContext())
        PrefsUtil.makeWorldReadable(requireContext())
    }

    override fun onPause() {
        super.onPause()
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(prefListener)
    }
}
