package com.nothingxpert.hooks

import android.service.notification.StatusBarNotification
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class NotificationHooks : BaseHook() {
    override val tag = "Notification"
    
    override fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != SYSTEMUI_PKG) return
        
        // Always install the hook - check preference inside the hook callback
        installUndismissableNotificationsHook(lpparam)
    }
    
    private fun installUndismissableNotificationsHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeHook("StatusBarNotification.isNonDismissable") {
            XposedHelpers.findAndHookMethod(
                StatusBarNotification::class.java,
                "isNonDismissable",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val undismissablePackages = getUndismissablePackages()
                        if (undismissablePackages.isEmpty()) return
                        
                        val sbn = param.thisObject as StatusBarNotification
                        val pkg = sbn.packageName
                        
                        if (undismissablePackages.contains(pkg)) {
                            param.result = true
                            log("Made notification undismissable for: $pkg")
                        }
                    }
                }
            )
        }
    }
    
    private fun getUndismissablePackages(): Set<String> {
        if (useRemotePrefs) {
            try {
                val packages = com.nothingxpert.XPrefs.prefs?.getStringSet("pref_undismissable_packages", emptySet())
                if (packages != null) return packages
            } catch (_: Throwable) {}
        }
        
        return try {
            val xsp = getXsp() ?: return emptySet()
            xsp.getStringSet("pref_undismissable_packages", emptySet()) ?: emptySet()
        } catch (_: Throwable) { emptySet() }
    }
}
