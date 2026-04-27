package com.nothingxpert

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.nothingxpert.hooks.LauncherHooks

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
            if (isAdded) {
                PrefsUtil.ensurePrefsAccessible(requireContext())
                PreferenceUtils.fixPermissions(requireContext())
            }
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
        preferenceManager.sharedPreferencesMode = Context.MODE_PRIVATE
        setPreferencesFromResource(R.xml.misc_preferences, rootKey)
        configureMaxRefreshRatePreference()
        configureCommunityWidgetLimitPreference()
        PreferenceUtils.fixPermissions(requireContext())
        PrefsUtil.ensurePrefsAccessible(requireContext())
    }

    private fun configureMaxRefreshRatePreference() {
        val pref = findPreference<ListPreference>(RefreshRateController.PREF_MAX_REFRESH_RATE) ?: return
        pref.setOnPreferenceChangeListener { _, newValue ->
            val value = RefreshRateController.normalize(newValue as? String)
            val prefs = preferenceManager.sharedPreferences ?: return@setOnPreferenceChangeListener false
            val saved = prefs.edit()
                .putString(RefreshRateController.PREF_MAX_REFRESH_RATE, value)
                .commit()
            if (!saved) return@setOnPreferenceChangeListener false

            if (isAdded) {
                PrefsUtil.ensurePrefsAccessible(requireContext())
                PreferenceUtils.fixPermissions(requireContext())
            }

            val appContext = requireContext().applicationContext
            Thread {
                val applied = RefreshRateController.apply(appContext, value, allowRoot = true)
                if (!applied) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (isAdded) {
                            Toast.makeText(
                                requireContext(),
                                R.string.toast_max_refresh_rate_failed,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }.apply {
                name = "NothingXpert-RefreshRateApply"
                isDaemon = true
                start()
            }
            true
        }
    }

    private fun configureCommunityWidgetLimitPreference() {
        val pref = findPreference<SwitchPreferenceCompat>(LauncherHooks.PREF_EXPAND_COMMUNITY_WIDGET_LIMIT) ?: return
        pref.setOnPreferenceChangeListener { _, newValue ->
            val value = newValue as? Boolean ?: return@setOnPreferenceChangeListener false
            val prefs = preferenceManager.sharedPreferences ?: return@setOnPreferenceChangeListener false
            val saved = prefs.edit().putBoolean(LauncherHooks.PREF_EXPAND_COMMUNITY_WIDGET_LIMIT, value).commit()
            if (saved && isAdded) {
                PrefsUtil.ensurePrefsAccessible(requireContext())
                PreferenceUtils.fixPermissions(requireContext())
            }
            saved
        }
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
