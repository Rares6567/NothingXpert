package com.nothingxpert.hooks

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
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

        safeHook("Instrumentation.callActivityOnActivityResult") {
            XposedHelpers.findAndHookMethod(
                android.app.Instrumentation::class.java,
                "callActivityOnActivityResult",
                Activity::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Intent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = param.args[0] as? Activity ?: return
                        val requestCode = param.args[1] as? Int ?: return
                        if (requestCode != APP_LOCK_REQUEST_CODE) return

                        authInProgress.remove(activity.hashCode())
                        val resultCode = param.args[2] as? Int ?: Activity.RESULT_CANCELED

                        if (resultCode == Activity.RESULT_OK) {
                            unlockedPackages.add(activity.packageName)
                            removeOverlay(activity)
                            log("Credential success for ${activity.packageName}")
                        } else {
                            removeOverlay(activity)
                            log("Credential canceled/failed for ${activity.packageName} (result=$resultCode)")
                            try {
                                activity.finish()
                            } catch (_: Throwable) {}
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
        if (activeOverlays.containsKey(activity.hashCode())) {
            activity.window.decorView.post { maybeStartCredentialPrompt(activity) }
            return
        }

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
        log("Overlay shown for ${activity.packageName}")

        activeOverlays[activity.hashCode()] = frameLayout
        activity.window.decorView.post { maybeStartCredentialPrompt(activity) }
    }

    private fun maybeStartCredentialPrompt(activity: Activity) {
        val key = activity.hashCode()
        if (!authInProgress.add(key)) return
        log("Starting credential prompt for ${activity.packageName}")

        val km = activity.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (km == null) {
            log("KeyguardManager is null for ${activity.packageName}")
            authInProgress.remove(key)
            return
        }

        if (!km.isDeviceSecure) {
            // No secure lock configured on device; don't hard-lock the app forever.
            authInProgress.remove(key)
            unlockedPackages.add(activity.packageName)
            removeOverlay(activity)
            log("Device is not secure; auto-unlocking ${activity.packageName}")
            return
        }

        val promptIntent = try {
            km.createConfirmDeviceCredentialIntent("Unlock app", null)
        } catch (t: Throwable) {
            log("Failed to create credential prompt: $t")
            null
        }

        if (promptIntent == null) {
            authInProgress.remove(key)
            log("createConfirmDeviceCredentialIntent returned null for ${activity.packageName}")
            return
        }

        try {
            activity.startActivityForResult(promptIntent, APP_LOCK_REQUEST_CODE)
            log("Credential prompt launched for ${activity.packageName}")
        } catch (t: Throwable) {
            authInProgress.remove(key)
            log("Failed to start credential prompt: $t")
        }
    }

    private fun removeOverlay(activity: Activity) {
        val overlay = activeOverlays.remove(activity.hashCode()) ?: return
        try {
            val parent = overlay.parent as? ViewGroup
            parent?.removeView(overlay)
        } catch (_: Throwable) {}
    }

    fun cleanupActivity(activity: Activity) {
        authInProgress.remove(activity.hashCode())
        removeOverlay(activity)
    }

    companion object {
        private const val APP_LOCK_REQUEST_CODE = 0x4C4B
        @Volatile var screenOffReceiverRegistered = false
        private val activeOverlays = mutableMapOf<Int, FrameLayout>()
        private val authInProgress = mutableSetOf<Int>()
    }
}
