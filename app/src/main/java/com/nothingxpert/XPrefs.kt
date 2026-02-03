package com.nothingxpert

import android.content.Context
import android.content.SharedPreferences
import com.crossbowffs.remotepreferences.RemotePreferences
import de.robv.android.xposed.XposedBridge

object XPrefs {
    private const val TAG = "NothingXpert"
    private const val AUTHORITY = "com.nothingxpert"
    private const val PREF_FILE = "com.nothingxpert_preferences"
    
    @Volatile
    var prefs: RemotePreferences? = null
        private set
    
    private var prefsInitialized = false
    private val preferenceChangeListeners = mutableListOf<OnPreferenceUpdateListener>()
    
    private val sharedPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        XposedBridge.log("$TAG: Preference changed: $key")
        notifyPreferenceUpdate(key)
    }
    
    fun init(context: Context) {
        if (prefsInitialized) return
        try {
            prefs = RemotePreferences(context, AUTHORITY, PREF_FILE, true)
            prefsInitialized = true
            XposedBridge.log("$TAG: RemotePreferences initialized")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to init RemotePreferences: $t")
        }
    }
    
    fun registerPreferenceChangeListener() {
        try {
            prefs?.registerOnSharedPreferenceChangeListener(sharedPrefsListener)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to register pref listener: $t")
        }
    }
    
    fun addOnPreferenceUpdateListener(listener: OnPreferenceUpdateListener) {
        if (!preferenceChangeListeners.contains(listener)) {
            preferenceChangeListeners.add(listener)
        }
    }
    
    fun removeOnPreferenceUpdateListener(listener: OnPreferenceUpdateListener) {
        preferenceChangeListeners.remove(listener)
    }
    
    private fun notifyPreferenceUpdate(key: String?) {
        preferenceChangeListeners.forEach { listener ->
            try {
                listener.onPreferenceUpdated(key)
            } catch (t: Throwable) {
                XposedBridge.log("$TAG: Error notifying listener: $t")
            }
        }
    }
    
    fun getBoolean(key: String, defValue: Boolean): Boolean {
        return try {
            prefs?.getBoolean(key, defValue) ?: defValue
        } catch (_: Throwable) {
            defValue
        }
    }
    
    fun getString(key: String, defValue: String): String {
        return try {
            prefs?.getString(key, defValue) ?: defValue
        } catch (_: Throwable) {
            defValue
        }
    }
    
    fun getInt(key: String, defValue: Int): Int {
        return try {
            prefs?.getInt(key, defValue) ?: defValue
        } catch (_: Throwable) {
            defValue
        }
    }
    
    fun getStringSet(key: String, defValue: Set<String>): Set<String> {
        return try {
            prefs?.getStringSet(key, defValue) ?: defValue
        } catch (_: Throwable) {
            defValue
        }
    }
    
    fun interface OnPreferenceUpdateListener {
        fun onPreferenceUpdated(key: String?)
    }
}
