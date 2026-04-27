package com.nothingxpert

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.nothingxpert.util.RootShell
import kotlin.math.abs

object RefreshRateController {
    const val PREF_MAX_REFRESH_RATE = "pref_max_refresh_rate"
    const val VALUE_DEFAULT = "default"

    private const val TAG = "NothingXpertRefreshRate"
    private const val SETTING_PEAK_REFRESH_RATE = "peak_refresh_rate"
    private const val SETTING_MIN_REFRESH_RATE = "min_refresh_rate"
    private val SUPPORTED_VALUES = setOf(VALUE_DEFAULT, "60", "90", "120")

    fun apply(context: Context, value: String?, allowRoot: Boolean): Boolean {
        val normalized = normalize(value)
        if (applyViaSettingsProvider(context, normalized)) return true
        return allowRoot && applyViaRoot(normalized)
    }

    fun scheduleRootReapply(value: String?, delaysMs: LongArray): Boolean {
        val normalized = normalize(value)
        val commands = delaysMs
            .filter { it > 0L }
            .joinToString("; ") { delayMs ->
                val delaySeconds = (delayMs / 1_000L).coerceAtLeast(1L)
                "( sleep $delaySeconds; ${rootApplyCommand(normalized)}; ${rootClearMinCommand()} ) >/dev/null 2>&1 &"
            }
        if (commands.isBlank()) return true
        val result = RootShell.exec(commands, timeoutMs = 2_500L)
        if (result?.exitCode != 0) {
            Log.w(TAG, "Root refresh-rate retry scheduling failed: $result")
            return false
        }
        return true
    }

    fun normalize(value: String?): String {
        return value?.takeIf { it in SUPPORTED_VALUES } ?: VALUE_DEFAULT
    }

    private fun applyViaSettingsProvider(context: Context, value: String): Boolean {
        return try {
            val peakOk = if (value == VALUE_DEFAULT) {
                Settings.System.putString(context.contentResolver, SETTING_PEAK_REFRESH_RATE, null)
                Settings.System.getString(context.contentResolver, SETTING_PEAK_REFRESH_RATE).isNullOrBlank()
            } else {
                val requested = value.toFloat()
                if (!Settings.System.putFloat(context.contentResolver, SETTING_PEAK_REFRESH_RATE, requested)) {
                    return false
                }
                val actual = Settings.System.getFloat(context.contentResolver, SETTING_PEAK_REFRESH_RATE, -1f)
                abs(actual - requested) < 0.01f
            }
            if (peakOk) clearMinViaSettingsProvider(context)
            peakOk
        } catch (t: Throwable) {
            Log.w(TAG, "SettingsProvider apply failed: $t")
            false
        }
    }

    private fun clearMinViaSettingsProvider(context: Context) {
        try {
            Settings.System.putString(context.contentResolver, SETTING_MIN_REFRESH_RATE, null)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to clear min_refresh_rate: $t")
        }
    }

    private fun applyViaRoot(value: String): Boolean {
        val command = "${rootApplyCommand(value)}; ${rootClearMinCommand()}"
        val write = RootShell.exec(command, timeoutMs = 2_500L)
        if (write?.exitCode != 0) {
            Log.w(TAG, "Root settings write failed: $write")
            return false
        }

        val read = RootShell.exec("settings get system $SETTING_PEAK_REFRESH_RATE", timeoutMs = 2_500L)
        if (read?.exitCode != 0) {
            Log.w(TAG, "Root settings readback failed: $read")
            return false
        }

        return settingMatches(value, read.stdout.trim()).also { matched ->
            if (!matched) {
                Log.w(TAG, "Refresh rate readback mismatch: requested=$value actual=${read.stdout.trim()}")
            }
        }
    }

    private fun rootApplyCommand(value: String): String {
        return if (value == VALUE_DEFAULT) {
            "settings delete system $SETTING_PEAK_REFRESH_RATE >/dev/null 2>&1 || true"
        } else {
            "settings put system $SETTING_PEAK_REFRESH_RATE ${value}.0"
        }
    }

    private fun rootClearMinCommand(): String {
        return "settings delete system $SETTING_MIN_REFRESH_RATE >/dev/null 2>&1 || true"
    }

    private fun settingMatches(value: String, actual: String): Boolean {
        if (value == VALUE_DEFAULT) {
            return actual.isBlank() || actual == "null"
        }
        return actual.toFloatOrNull()?.let { abs(it - value.toFloat()) < 0.01f } == true
    }
}
