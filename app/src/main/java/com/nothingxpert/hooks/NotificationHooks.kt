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
                        val undismissablePackages = getPreferenceStringSet("pref_undismissable_packages", emptySet())
                        if (undismissablePackages.isEmpty()) return
                        
                        val sbn = param.thisObject as StatusBarNotification
                        val pkg = sbn.packageName
                        
                        if (undismissablePackages.contains(pkg)) {
                            param.result = true
                        }
                    }
                }
            )
        }
    }
}
