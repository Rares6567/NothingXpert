package com.nothingxpert

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import androidx.preference.PreferenceManager
import java.util.Locale

object LocaleHelper {
    private const val PREF_LANGUAGE = "pref_app_language"
    private const val DEFAULT_LANGUAGE = ""

    fun setLocale(context: Context): Context {
        val language = getLanguage(context)
        return updateResources(context, language)
    }

    fun setNewLocale(context: Context, language: String) {
        persistLanguage(context, language)
        updateResources(context, language)
    }

    fun getLanguage(context: Context): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getString(PREF_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
    }

    private fun persistLanguage(context: Context, language: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putString(PREF_LANGUAGE, language).apply()
    }

    private fun updateResources(context: Context, language: String): Context {
        val locale = when (language) {
            "en" -> Locale("en")
            "tr" -> Locale("tr")
            else -> Resources.getSystem().configuration.locales.get(0)
        }

        Locale.setDefault(locale)

        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)

        return context.createConfigurationContext(configuration)
    }

    fun getLanguageDisplayName(languageCode: String): String {
        return when (languageCode) {
            "en" -> "English"
            "tr" -> "Türkçe"
            else -> "System Default"
        }
    }

    fun getLanguageEntryValues(): Array<String> {
        return arrayOf("", "en", "tr")
    }

    fun getLanguageEntries(): Array<String> {
        return arrayOf("language_system_default", "language_english", "language_turkish")
    }
}
