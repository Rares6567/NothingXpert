package com.nothingxpert.hooks

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class AppLockHooks : BaseHook() {
    override val tag = "AppLock"
    
    private val unlockedPackages = java.util.Collections.synchronizedSet(HashSet<String>())
    
    override fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName
        if (pkg == "android" || pkg == SYSTEMUI_PKG || pkg == "com.android.launcher3" || pkg == MODULE_PKG) return
        
        safeHook("Instrumentation.callActivityOnCreate") {
            XposedHelpers.findAndHookMethod(
                android.app.Instrumentation::class.java,
                "callActivityOnCreate",
                Activity::class.java,
                Bundle::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = param.args[0] as Activity
                        if (isAppLocked(activity.packageName)) {
                            log("Locked app launched: ${activity.packageName}")
                        }
                    }
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.args[0] as Activity
                        if (isAppLocked(activity.packageName)) {
                            if (!isAppUnlocked(activity.packageName)) {
                                showLockOverlay(activity)
                            }
                        }
                    }
                }
            )
        }

        safeHook("Activity.finish") {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "finish",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity
                        activity?.let {
                            if (isAppLocked(it.packageName)) {
                                cleanupActivity(it)
                            }
                        }
                    }
                }
            )
        }
    }

    fun registerScreenOffReceiver() {
        if (screenOffReceiverRegistered) return
        val ctx = currentApplication() ?: return
        try {
            val filter = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    KeyguardHooks.lastWakeTapTs = 0L
                    KeyguardHooks.lastWakeTapX = -1
                    KeyguardHooks.lastWakeTapY = -1
                    if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                        KeyguardHooks.lastScreenOffTs = android.os.SystemClock.uptimeMillis()
                    }
                    unlockedPackages.clear()
                    log("Cleared app-lock cache on screen off")
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                ctx.registerReceiver(receiver, filter)
            }
            screenOffReceiverRegistered = true
        } catch (t: Throwable) {
            log("failed to register screen-off receiver: $t")
        }
    }
    
    private fun isAppUnlocked(packageName: String): Boolean {
        return unlockedPackages.contains(packageName)
    }
    
    private fun isAppLocked(packageName: String): Boolean {
        return getPreferenceStringSet("pref_locked_packages", emptySet()).contains(packageName)
    }
    
    private fun showLockOverlay(activity: Activity) {
        val frameLayout = FrameLayout(activity)
        frameLayout.setBackgroundColor(Color.BLACK)
        frameLayout.isClickable = true
        frameLayout.isFocusable = true
        
        val textView = TextView(activity)
        textView.text = "Locked"
        textView.setTextColor(Color.WHITE)
        textView.textSize = 20f
        textView.gravity = Gravity.CENTER
        
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, 
            FrameLayout.LayoutParams.WRAP_CONTENT, 
            Gravity.CENTER
        )
        frameLayout.addView(textView, params)
        
        val decorView = activity.window.decorView as ViewGroup
        decorView.addView(frameLayout, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "com.nothingxpert.ACTION_UNLOCK") {
                    val pkg = intent.getStringExtra("extra_package_name")
                    if (pkg == activity.packageName) {
                        unlockedPackages.add(activity.packageName)
                        decorView.removeView(frameLayout)
                        try {
                            activity.unregisterReceiver(this)
                        } catch (_: Throwable) {}
                    }
                }
            }
        }
        val filter = android.content.IntentFilter("com.nothingxpert.ACTION_UNLOCK")
        ContextCompat.registerReceiver(
            activity,
            receiver,
            filter,
            "com.nothingxpert.permission.APP_LOCK",
            null,
            ContextCompat.RECEIVER_EXPORTED
        )

        // Track receiver for cleanup when activity is destroyed
        pendingReceivers[activity.hashCode()] = receiver

        val intent = Intent()
        intent.component = android.content.ComponentName(MODULE_PKG, "$MODULE_PKG.LockScreenActivity")
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TASK or
            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
            Intent.FLAG_ACTIVITY_NO_HISTORY
        )
        intent.putExtra("extra_package_name", activity.packageName)
        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            log("Failed to launch lock screen: $e")
        }
    }

    private fun registerActivityReceiver(activity: Activity, receiver: android.content.BroadcastReceiver) {
        pendingReceivers[activity.hashCode()] = receiver
    }

    private fun unregisterActivityReceiver(activity: Activity) {
        val receiver = pendingReceivers.remove(activity.hashCode()) ?: return
        try {
            activity.unregisterReceiver(receiver)
        } catch (_: Throwable) {}
    }

    fun cleanupActivity(activity: Activity) {
        unregisterActivityReceiver(activity)
    }

    companion object {
        @Volatile var screenOffReceiverRegistered = false
        private val pendingReceivers = mutableMapOf<Int, android.content.BroadcastReceiver>()
    }
}
