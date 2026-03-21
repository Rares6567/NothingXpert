package com.nothingxpert.hooks

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class LauncherHooks : BaseHook() {
    override val tag = "Launcher"

    override fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != NOTHING_LAUNCHER_PKG) return

        safeHook("AI widget max count override") {
            XposedHelpers.findAndHookMethod(
                AI_WIDGET_CONFIG_CLASS,
                lpparam.classLoader,
                "c",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (getPreferenceBoolean(PREF_EXPAND_COMMUNITY_WIDGET_LIMIT, false)) {
                            param.result = EXPANDED_WIDGET_LIMIT
                        }
                    }
                }
            )
        }
    }

    companion object {
        const val PREF_EXPAND_COMMUNITY_WIDGET_LIMIT = "pref_expand_community_widget_limit"

        const val NOTHING_LAUNCHER_PKG = "com.nothing.launcher"
        private const val AI_WIDGET_CONFIG_CLASS = "c8.c"
        private const val EXPANDED_WIDGET_LIMIT = 20
    }
}
