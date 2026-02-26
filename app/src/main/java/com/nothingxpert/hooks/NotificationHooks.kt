package com.nothingxpert.hooks

import android.service.notification.StatusBarNotification
import com.nothingxpert.XPrefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class NotificationHooks : BaseHook() {
    override val tag = "Notification"

    override fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            SYSTEMUI_PKG -> {
                // Always install the hook - check preference inside the hook callback
                installUndismissableNotificationsHook(lpparam.classLoader)
            }
            "android" -> {
                // System server: notification sound/vibration behavior
                installUnobtrusiveNotificationsHook(lpparam)
            }
        }
    }

    private fun installUndismissableNotificationsHook(classLoader: ClassLoader) {
        safeHook("StatusBarNotification.isNonDismissable") {
            try {
                val sbnClass = XposedHelpers.findClass("android.service.notification.StatusBarNotification", classLoader)
                XposedHelpers.findAndHookMethod(
                    sbnClass,
                    "isNonDismissable",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            // Use process-local snapshot to avoid hitting the synchronized
                            // pref cache on every single notification isNonDismissable check.
                            val undismissablePackages = undismissableSnapshot
                                ?: getPreferenceStringSet("pref_undismissable_packages", emptySet())
                                    .also { undismissableSnapshot = it }
                            if (undismissablePackages.isEmpty()) return

                            val sbn = param.thisObject
                            val pkg = XposedHelpers.getObjectField(sbn, "packageName") as? String ?: return

                            if (undismissablePackages.contains(pkg)) {
                                param.result = true
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                log("Failed to hook StatusBarNotification: $t")
            }
        }
    }

    private fun installUnobtrusiveNotificationsHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeHook("NotificationAttentionHelper (unobtrusive)") {
            initRemotePrefsForSystemServer()
            val helperClass = XposedHelpers.findClassIfExists(
                "com.android.server.notification.NotificationAttentionHelper",
                lpparam.classLoader
            )
            if (helperClass == null) {
                log("NotificationAttentionHelper not found; unobtrusive notifications disabled")
                return@safeHook
            }

            // Target the real alerting methods (NothingOS uses NotificationAttentionHelper -> playSound/playVibration/vibrate).
            hookSilenceMethod(helperClass, "playSound", returnsBoolean = true)
            hookSilenceMethod(helperClass, "playVibration", returnsBoolean = true)
            hookSilenceMethod(helperClass, "vibrate", returnsBoolean = false)
        }
    }

    private fun initRemotePrefsForSystemServer() {
        if (remotePrefsInitAttempted) return
        remotePrefsInitAttempted = true
        val ctx = getSystemContext() ?: return
        try {
            XPrefs.init(ctx.applicationContext)
            XPrefs.registerPreferenceChangeListener()
            useRemotePrefs = true
            log("RemotePreferences enabled for system_server prefs")
        } catch (t: Throwable) {
            useRemotePrefs = false
            log("RemotePreferences init failed in system_server: $t")
        }
    }

    private fun hookSilenceMethod(targetClass: Class<*>, methodName: String, returnsBoolean: Boolean) {
        safeHook("${targetClass.name}.$methodName") {
            XposedBridge.hookAllMethods(
                targetClass,
                methodName,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!getPreferenceBoolean(PREF_UNOBTRUSIVE_SCREEN_ON, false)) return
                        if (!isHelperScreenOnAndUserPresent(param.thisObject)) return
                        param.result = if (returnsBoolean) false else null
                    }
                }
            )
        }
    }

    private fun isHelperScreenOnAndUserPresent(helper: Any?): Boolean {
        if (helper == null) return false
        return try {
            val screenOn = XposedHelpers.getBooleanField(helper, "mScreenOn")
            val userPresent = XposedHelpers.getBooleanField(helper, "mUserPresent")
            screenOn && userPresent
        } catch (_: Throwable) {
            false
        }
    }

    companion object {
        private const val PREF_UNOBTRUSIVE_SCREEN_ON = "pref_unobtrusive_notifs_screen_on"
        @Volatile private var remotePrefsInitAttempted: Boolean = false

        // Snapshot of the undismissable packages set. Avoids hitting the synchronized
        // pref cache on every notification event. Cleared by HookEntry on pref change.
        @Volatile var undismissableSnapshot: Set<String>? = null
    }
}
