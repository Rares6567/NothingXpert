package com.nothingxpert.hooks

import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class SecureFlagHooks : BaseHook() {
    override val tag = "SecureFlag"
    
    override fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!isAllowSecureScreenshots()) return
        
        installWindowManagerHooks(lpparam)
        installWindowHooks(lpparam)
        installSurfaceHooks(lpparam)
        installLayoutParamsHook(lpparam)
    }
    
    private fun installWindowManagerHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        tryStripFlagSecure(lpparam.classLoader, "android.view.WindowManagerImpl", "addView")
        
        safeHook("WindowManagerGlobal.addView") {
            val displayClass = Class.forName("android.view.Display", false, lpparam.classLoader)
            val windowClass = Class.forName("android.view.Window", false, lpparam.classLoader)
            tryStripFlagSecure(
                lpparam.classLoader,
                "android.view.WindowManagerGlobal",
                "addView",
                View::class.java,
                ViewGroup.LayoutParams::class.java,
                displayClass,
                windowClass
            )
        }
    }
    
    private fun installWindowHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeHook("Window.addFlags") {
            XposedHelpers.findAndHookMethod(
                "android.view.Window",
                lpparam.classLoader,
                "addFlags",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        var flags = param.args.getOrNull(0) as? Int ?: return
                        if (flags and WindowManager.LayoutParams.FLAG_SECURE != 0) {
                            flags = flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
                            param.args[0] = flags
                        }
                    }
                }
            )
        }
        
        safeHook("Window.setFlags") {
            XposedHelpers.findAndHookMethod(
                "android.view.Window",
                lpparam.classLoader,
                "setFlags",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        var flags = param.args.getOrNull(0) as? Int ?: return
                        val mask = param.args.getOrNull(1) as? Int ?: return
                        if (mask and WindowManager.LayoutParams.FLAG_SECURE != 0 &&
                            flags and WindowManager.LayoutParams.FLAG_SECURE != 0) {
                            flags = flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
                            param.args[0] = flags
                        }
                    }
                }
            )
        }
    }
    
    private fun installSurfaceHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeHook("SurfaceControl.Builder.setSecure") {
            XposedHelpers.findAndHookMethod(
                "android.view.SurfaceControl\$Builder",
                lpparam.classLoader,
                "setSecure",
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.args[0] = false
                    }
                }
            )
        }
        
        safeHook("SurfaceView.setSecure") {
            XposedHelpers.findAndHookMethod(
                "android.view.SurfaceView",
                lpparam.classLoader,
                "setSecure",
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.args[0] = false
                    }
                }
            )
        }
    }
    
    private fun installLayoutParamsHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeHook("WindowManager.LayoutParams constructor") {
            XposedHelpers.findAndHookConstructor(
                WindowManager.LayoutParams::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val lp = param.thisObject as? WindowManager.LayoutParams ?: return
                        stripSecure(lp)
                    }
                }
            )
        }
    }
    
    private fun tryStripFlagSecure(cl: ClassLoader, clazz: String, method: String) {
        tryStripFlagSecure(cl, clazz, method, View::class.java, ViewGroup.LayoutParams::class.java)
    }
    
    private fun tryStripFlagSecure(cl: ClassLoader, clazz: String, method: String, vararg sig: Class<*>) {
        safeHook("$clazz.$method") {
            XposedHelpers.findAndHookMethod(
                clazz,
                cl,
                method,
                *sig,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val lpIndex = param.args.indexOfFirst { it is WindowManager.LayoutParams }
                        if (lpIndex >= 0) {
                            val lp = param.args[lpIndex] as WindowManager.LayoutParams
                            stripSecure(lp)
                        }
                    }
                }
            )
        }
    }
    
    private fun stripSecure(lp: WindowManager.LayoutParams) {
        if (lp.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) {
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
            log("cleared FLAG_SECURE on ${lp.packageName}")
        }
        try {
            val field = WindowManager.LayoutParams::class.java.getDeclaredField("privateFlags")
            field.isAccessible = true
            val current = field.getInt(lp)
            val secureBit = WindowManager.LayoutParams.FLAG_SECURE
            if (current and secureBit != 0) {
                field.setInt(lp, current and secureBit.inv())
                log("cleared private FLAG_SECURE on ${lp.packageName}")
            }
        } catch (_: Throwable) {}
    }
    
    private fun isAllowSecureScreenshots() = getPreferenceBoolean(PREF_ALLOW_SECURE, false)
    
    companion object {
        private const val PREF_ALLOW_SECURE = "pref_allow_secure_screenshot"
    }
}
