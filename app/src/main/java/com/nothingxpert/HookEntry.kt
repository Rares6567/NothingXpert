package com.nothingxpert

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.nothingxpert.hooks.*

/**
 * Main entry point for NothingXpert Xposed module.
 * Dispatches hook installation to specialized hook modules.
 */
class HookEntry : IXposedHookLoadPackage, XPrefs.OnPreferenceUpdateListener {
    
    private val hooks = listOf(
        KeyguardHooks(),
        VolumeHooks(),
        SecureFlagHooks(),
        NavbarHooks(),
        AppLockHooks(),
        NotificationHooks(),
        SystemHooks(),
        FloatingWindowHooks(),
        DepthWallpaperHooks(),
        QsBlurHooks()
    )
    
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName
        
        // Initialize module on first load
        if (!initialized) {
            initialized = true
            XposedBridge.log("NothingXpert: Module loaded, installing hooks...")
        }
        
        // Store SystemUI classloader for tap position methods
        if (pkg == SYSTEMUI_PKG) {
            KeyguardHooks.systemUiClassLoader = lpparam.classLoader
            initializeXPrefs(lpparam)
        }
        
        // Install all hooks
        hooks.forEach { hook ->
            try {
                hook.install(lpparam)
            } catch (t: Throwable) {
                XposedBridge.log("NothingXpert: ${hook.tag} installation failed: $t")
            }
        }
        
        // Register IME toggle receiver in system_server
        if (pkg == "android") {
            registerImeToggleReceiver()
        }
    }
    
    private fun initializeXPrefs(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.systemui.SystemUIApplication",
                lpparam.classLoader,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val context = param.thisObject as? Context ?: return
                        try {
                            XPrefs.init(context.applicationContext)
                            XPrefs.addOnPreferenceUpdateListener(this@HookEntry)
                            XPrefs.registerPreferenceChangeListener()
                            BaseHook.useRemotePrefs = true
                        } catch (t: Throwable) {
                            XposedBridge.log("NothingXpert: Failed to initialize XPrefs: $t")
                            BaseHook.useRemotePrefs = false
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("NothingXpert: Failed to hook for XPrefs: $t")
        }
    }
    
    override fun onPreferenceUpdated(key: String?) {
        BaseHook.clearCache(key)
        if (key == SystemHooks.PREF_SHAKE_TORCH) {
            SystemHooks.refreshFromPrefs()
        }
        if (key == FloatingWindowHooks.PREF_FLOATING_WINDOW_ENABLED ||
            key == FloatingWindowHooks.PREF_FLOATING_WINDOW_SIZE) {
            FloatingWindowHooks.refreshFromPrefs()
        }
        if (key == null || key == "pref_locked_packages") {
            AppLockHooks.lockedPackagesSnapshot = null
        }
        if (key == null || key == "pref_undismissable_packages") {
            NotificationHooks.undismissableSnapshot = null
        }
        if (key == DepthWallpaperHooks.PREF_DEPTH_ENABLED ||
            key == DepthWallpaperHooks.PREF_DEPTH_IMAGE ||
            key == DepthWallpaperHooks.PREF_DEPTH_OPACITY) {
            DepthWallpaperHooks.refreshFromPrefs()
        }
        if (key == QsBlurHooks.PREF_QS_BLUR_ENABLED) {
            QsBlurHooks.refreshFromPrefs()
        }
    }
    
    private fun registerImeToggleReceiver() {
        val ctx = BaseHook.getSystemContext() ?: return
        synchronized(imeLock) {
            if (imeReceiverRegistered) return
            try {
                val filter = android.content.IntentFilter(ACTION_IME_BAR_TOGGLED)
                val receiver = object : android.content.BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: android.content.Intent?) {
                        if (intent?.action != ACTION_IME_BAR_TOGGLED) return
                        forceStopPackage(ctx, GBOARD_PKG)
                    }
                }
                ContextCompat.registerReceiver(
                    ctx,
                    receiver,
                    filter,
                    ContextCompat.RECEIVER_EXPORTED
                )
                imeReceiver = receiver
                imeReceiverContext = ctx
                imeReceiverRegistered = true
                XposedBridge.log("NothingXpert: IME toggle receiver registered")
            } catch (t: Throwable) {
                XposedBridge.log("NothingXpert: Failed to register IME toggle receiver: $t")
            }
        }
    }
    
    private fun forceStopPackage(context: Context, pkg: String) {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return
            val method = am.javaClass.getMethod("forceStopPackage", String::class.java)
            method.invoke(am, pkg)
            XposedBridge.log("NothingXpert: force-stopped $pkg")
        } catch (t: Throwable) {
            XposedBridge.log("NothingXpert: force-stop failed for $pkg: $t")
        }
    }
    
    companion object {
        const val MODULE_PKG = "com.nothingxpert"
        const val SYSTEMUI_PKG = "com.android.systemui"
        const val GBOARD_PKG = "com.google.android.inputmethod.latin"
        const val ACTION_IME_BAR_TOGGLED = "com.nothingxpert.action.IME_BAR_TOGGLED"
        const val EXTRA_IME_BAR_ENABLED = "enabled"
        const val PREF_HIDE_IME_BAR = "pref_hide_ime_bar"
        const val PREF_UNDISMISSABLE_NOTIFS = "pref_undismissable_notifs"
        const val UNDISMISSABLE_PACKAGES = "pref_undismissable_packages"

        @Volatile private var initialized = false
        @Volatile private var imeReceiverRegistered = false
        @Volatile private var imeReceiver: android.content.BroadcastReceiver? = null
        @Volatile private var imeReceiverContext: Context? = null

        fun unregisterImeReceiver() {
            synchronized(imeLock) {
                if (!imeReceiverRegistered) return
                try {
                    val ctx = imeReceiverContext ?: return
                    val recv = imeReceiver ?: return
                    ctx.unregisterReceiver(recv)
                    imeReceiver = null
                    imeReceiverContext = null
                    imeReceiverRegistered = false
                } catch (_: Throwable) {}
            }
        }
    }

    private object imeLock
}
