package com.nothingxpert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != "android.intent.action.LOCKED_BOOT_COMPLETED") return
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val launch = prefs.getBoolean("pref_launch_on_boot", false)
        if (!launch) return
        PrefsUtil.ensurePrefsAccessible(context)
        try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent(context, MainActivity::class.java)
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(launchIntent)
            Log.i("NothingXpertBoot", "Launched app on boot")
        } catch (t: Throwable) {
            Log.e("NothingXpertBoot", "Failed to launch on boot", t)
        }
    }
}
