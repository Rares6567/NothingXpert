package com.nothingxpert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class RefreshRateBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }

        val pendingResult = goAsync()
        Thread {
            try {
                applySavedRefreshRate(context.applicationContext)
            } finally {
                pendingResult.finish()
            }
        }.apply {
            name = "NothingXpert-RefreshRateBoot"
            isDaemon = true
            start()
        }
    }

    private fun applySavedRefreshRate(context: Context) {
        val value = readSavedRefreshRate(context) ?: return
        val applied = RefreshRateController.apply(context, value, allowRoot = true)
        val retriesScheduled = RefreshRateController.scheduleRootReapply(
            value,
            APPLY_RETRY_DELAYS_MS
        )
        Log.i(
            TAG,
            "Boot refresh-rate apply value=$value applied=$applied retriesScheduled=$retriesScheduled"
        )
    }

    private fun readSavedRefreshRate(context: Context): String? {
        readPrefs(context.createDeviceProtectedStorageContext())?.let { return it }
        return readPrefs(context)
    }

    private fun readPrefs(context: Context): String? {
        return try {
            val prefs = context.getSharedPreferences(
                "${context.packageName}_preferences",
                Context.MODE_PRIVATE
            )
            if (!prefs.contains(RefreshRateController.PREF_MAX_REFRESH_RATE)) return null
            RefreshRateController.normalize(
                prefs.getString(
                    RefreshRateController.PREF_MAX_REFRESH_RATE,
                    RefreshRateController.VALUE_DEFAULT
                )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read refresh-rate preference: $t")
            null
        }
    }

    companion object {
        private const val TAG = "NothingXpertRefreshRateBoot"
        private val APPLY_RETRY_DELAYS_MS = longArrayOf(5_000L, 15_000L, 45_000L)
    }
}
