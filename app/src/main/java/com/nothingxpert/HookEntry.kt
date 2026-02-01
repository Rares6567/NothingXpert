package com.nothingxpert

import android.app.admin.DevicePolicyManager
import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.media.AudioManager
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import de.robv.android.xposed.XSharedPreferences
import kotlin.math.abs
import kotlin.math.min
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookEntry : IXposedHookLoadPackage {
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    private val skipUpRunnable = Runnable {
        if (!skipUpSent) {
            val action = getVolumeUpAction()
            val ok = executeVolumeAction(action, runnableContext)
            XposedBridge.log("NothingXpert: skipUpRunnable fired action=$action ok=$ok")
            if (ok) skipUpSent = true
        }
    }

    private val skipDownRunnable = Runnable {
        if (!skipDownSent) {
            val action = getVolumeDownAction()
            val ok = executeVolumeAction(action, runnableContext)
            XposedBridge.log("NothingXpert: skipDownRunnable fired action=$action ok=$ok")
            if (ok) skipDownSent = true
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Policy-level hooks live in system_server ("android" package).
        if (lpparam.packageName == "android") {
            installPolicyVolumeHooks(lpparam)
            return
        }

        // Optional global hook: remove FLAG_SECURE when enabled.
        if (isAllowSecureScreenshots()) {
            installSecureFlagBypass(lpparam)
        }

        if (lpparam.packageName != SYSTEMUI_PKG) return

        try {
            systemUiClassLoader = lpparam.classLoader
            Log.i(LOG_TAG, "SystemUI loaded, installing hooks")
            XposedBridge.log("NothingXpert: SystemUI loaded, installing hooks")
            XposedHelpers.findAndHookMethod(
                KEYGUARD_TOUCH_CLASS,
                lpparam.classLoader,
                "onDoubleClick",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!ENABLE_DOUBLE_TAP) return
                        val instance = param.thisObject ?: return
                        if (goToSleepFromInteractor(instance)) {
                            // Skip original double-tap handling when we successfully sleep.
                            param.result = null
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("NothingXpert: interactor hook setup failed: $t")
        }

        try {
            // Also hook the ViewModel as a safety net in case the interactor path changes.
            XposedHelpers.findAndHookMethod(
                KEYGUARD_TOUCH_VIEWMODEL_CLASS,
                lpparam.classLoader,
                "onDoubleClick",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!ENABLE_DOUBLE_TAP) return
                        val viewModel = param.thisObject ?: return
                        try {
                            val interactor =
                                XposedHelpers.getObjectField(viewModel, "interactor") ?: return
                            if (goToSleepFromInteractor(interactor)) {
                                param.result = null
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log("NothingXpert: viewmodel hook failed: $t")
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("NothingXpert: viewmodel hook setup failed: $t")
        }

        try {
            // Single tap to sleep on lockscreen.
            XposedHelpers.findAndHookMethod(
                KEYGUARD_TOUCH_CLASS,
                lpparam.classLoader,
                "onClick",
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val instance = param.thisObject ?: return
                        if (isSingleTapEnabled() && goToSleepFromInteractor(instance)) {
                            blockTouchesUntil = SystemClock.uptimeMillis() + TOUCH_BLOCK_MS
                            param.result = null
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("NothingXpert: single-tap hook failed: $t")
        }

        try {
            XposedHelpers.findAndHookMethod(
                SYSTEMUI_APP_CLASS,
                lpparam.classLoader,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        Log.i(LOG_TAG, "SystemUIApplication.onCreate hooked")
                        XposedBridge.log("NothingXpert: SystemUIApplication.onCreate hooked")
                        (param.thisObject as? Context)?.let { runnableContext = it.applicationContext }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("NothingXpert: SystemUIApplication hook failed: $t")
        }

        try {
            // Ensure double-tap detection is enabled on the lockscreen TouchHandlingView.
            XposedHelpers.findAndHookMethod(
                TOUCH_HANDLING_VIEW_CLASS,
                lpparam.classLoader,
                "setDoublePressHandlingEnabled",
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.args.isNotEmpty() && param.args[0] is Boolean) {
                            val enabled = param.args[0] as Boolean
                            if (!enabled) {
                                param.args[0] = true
                                Log.i(LOG_TAG, "Forcing double-press handling enabled")
                                XposedBridge.log("NothingXpert: Forcing double-press handling enabled")
                            }
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("NothingXpert: TouchHandlingView hook failed: $t")
        }

        try {
            XposedHelpers.findAndHookMethod(
                "com.android.keyguard.KeyguardPinBasedInputView",
                lpparam.classLoader,
                "onFinishInflate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isShufflePinEnabled()) return
                        shufflePinPad(param.thisObject)
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("NothingXpert: shuffle PIN hook failed: $t")
        }

        try {
            // Directly hook the double-tap listener on the lockscreen view.
            XposedHelpers.findAndHookMethod(
                KEYGUARD_TOUCH_LISTENER_CLASS,
                lpparam.classLoader,
                "onDoubleTapDetected",
                android.view.View::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!ENABLE_DOUBLE_TAP) return
                        val view = param.args.getOrNull(0) as? android.view.View ?: return
                        val pm = view.context.getSystemService(PowerManager::class.java) ?: return
                        val uptime = SystemClock.uptimeMillis()
                        Log.i(LOG_TAG, "onDoubleTapDetected hook fired, sleeping")
                        XposedBridge.log("NothingXpert: onDoubleTapDetected hook fired, sleeping")
                        if (tryGoToSleep(pm, uptime)) {
                            param.result = null
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("NothingXpert: KeyguardTouchViewBinder listener hook failed: $t")
        }

        try {
            // Single-tap listener from TouchHandlingView into keyguard viewmodel.
            XposedHelpers.findAndHookMethod(
                KEYGUARD_TOUCH_LISTENER_CLASS,
                lpparam.classLoader,
                "onSingleTapDetected",
                android.view.View::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isSingleTapEnabled()) return
                        val view = param.args.getOrNull(0) as? android.view.View ?: return
                        val pm = view.context.getSystemService(PowerManager::class.java) ?: return
                        val x = param.args.getOrNull(1) as? Int ?: 0
                        val y = param.args.getOrNull(2) as? Int ?: 0
                        val uptime = SystemClock.uptimeMillis()
                        Log.i(LOG_TAG, "onSingleTapDetected hook fired, sleeping")
                        XposedBridge.log("NothingXpert: onSingleTapDetected hook fired, sleeping")
                        setTapPosition(x, y)
                        if (tryGoToSleep(pm, uptime)) {
                            blockTouchesUntil = SystemClock.uptimeMillis() + TOUCH_BLOCK_MS
                            param.result = null
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("NothingXpert: KeyguardTouchViewBinder single-tap hook failed: $t")
        }

        try {
            // Lower-level gesture detector hook as a last resort.
            XposedHelpers.findAndHookMethod(
                TOUCH_INTERACTION_GESTURE_CLASS,
                lpparam.classLoader,
                "onDoubleTap",
                MotionEvent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!ENABLE_DOUBLE_TAP) return
                        val app = currentApplication() ?: return
                        val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
                        val uptime = SystemClock.uptimeMillis()
                        Log.i(LOG_TAG, "gestureDetector onDoubleTap hook fired, sleeping")
                        XposedBridge.log("NothingXpert: gestureDetector onDoubleTap hook fired, sleeping")
                        if (tryGoToSleep(pm, uptime)) {
                            blockTouchesUntil = SystemClock.uptimeMillis() + TOUCH_BLOCK_MS
                            param.result = true
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("NothingXpert: TouchHandlingViewInteractionHandler hook failed: $t")
        }
        try {
            // Single tap to sleep via dispatchSingleTap in the interaction handler.
            XposedHelpers.findAndHookMethod(
                TOUCH_INTERACTION_HANDLER_CLASS,
                lpparam.classLoader,
                "dispatchSingleTap",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val app = currentApplication() ?: return
                        val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
                        val x = param.args.getOrNull(0) as? Int ?: 0
                        val y = param.args.getOrNull(1) as? Int ?: 0
                        val uptime = SystemClock.uptimeMillis()
                        Log.i(LOG_TAG, "dispatchSingleTap hook fired, sleeping")
                        XposedBridge.log("NothingXpert: dispatchSingleTap hook fired, sleeping")
                        if (isSingleTapEnabled()) {
                            setTapPosition(x, y)
                        }
                        if (isSingleTapEnabled() && tryGoToSleep(pm, uptime)) {
                            blockTouchesUntil = SystemClock.uptimeMillis() + TOUCH_BLOCK_MS
                            param.result = null
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("NothingXpert: dispatchSingleTap hook failed: $t")
        }

        try {
            // Block doze gesture wake sensors during our post-sleep blackout window.
            XposedHelpers.findAndHookMethod(
                DOZE_TRIGGERS_CLASS,
                lpparam.classLoader,
                "onSensor",
                Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                FloatArray::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val sensorType = param.args.getOrNull(0) as? Int ?: return
                        val now = SystemClock.uptimeMillis()
                        if (now < blockTouchesUntil && sensorType in DOZE_BLOCKED_SENSORS) {
                            Log.i(LOG_TAG, "Blocking doze sensor $sensorType during blackout")
                            XposedBridge.log("NothingXpert: Blocking doze sensor $sensorType during blackout")
                            param.result = null
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("NothingXpert: DozeTriggers onSensor hook failed: $t")
        }

        try {
            // Volume long-press track skip (fallback when policy hook not present).
            if (!policyVolumeInstalled) {
                XposedHelpers.findAndHookMethod(
                    KEYGUARD_TOUCH_VIEWMODEL_CLASS, // close to keyguard path
                    lpparam.classLoader,
                    "onVolumeKeyEvent",
                    KeyEvent::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val event = param.args.getOrNull(0) as? KeyEvent ?: return
                            val app = currentApplication() ?: return
                            val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
                            runnableContext = app
                            if (handleVolumeForTracks(event, pm, app)) {
                                param.result = null
                            }
                        }
                    }
                )
            }
        } catch (t: Throwable) {
            XposedBridge.log("NothingXpert: keyguard volume fallback failed: $t")
        }

        try {
            // Cancel any pending long-press scheduling during blackout to avoid showing the customize button.
            XposedHelpers.findAndHookMethod(
                TOUCH_INTERACTION_HANDLER_CLASS,
                lpparam.classLoader,
                "scheduleLongPress",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (SystemClock.uptimeMillis() < blockTouchesUntil) {
                            param.result = null
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("NothingXpert: scheduleLongPress hook failed: $t")
        }

        try {
            // NothingOS tap handler (power/lockscreen double-tap paths).
            XposedHelpers.findAndHookMethod(
                NT_TAP_HANDLE_CLASS,
                lpparam.classLoader,
                "doubleTapEvent",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!ENABLE_DOUBLE_TAP) return
                        val handler = param.thisObject ?: return
                        try {
                            val keyguard =
                                XposedHelpers.getObjectField(handler, "mKeyguardStateController")
                                    ?: return
                            val isShowing =
                                (XposedHelpers.callMethod(keyguard, "isShowing") as? Boolean)
                                    ?: false
                            if (!isShowing) return

                            val powerManager =
                                XposedHelpers.getObjectField(handler, "mPowerManager") as? PowerManager
                            val uptime = SystemClock.uptimeMillis()

                            if (powerManager != null) {
                                Log.i(LOG_TAG, "NTTapHandle double-tap on keyguard, sleeping")
                                XposedBridge.log("NothingXpert: NTTapHandle double-tap on keyguard, sleeping")
                                if (tryGoToSleep(powerManager, uptime)) {
                                    blockTouchesUntil = SystemClock.uptimeMillis() + TOUCH_BLOCK_MS
                                    param.result = null
                                }
                            }
                        } catch (t: Throwable) {
                            Log.e(LOG_TAG, "NTTapHandle hook failed", t)
                            XposedBridge.log("NothingXpert: NTTapHandle hook failed: $t")
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("NothingXpert: NTTapHandle hook setup failed: $t")
        }

        try {
            // Swallow stray touches for a short window after we put the device to sleep to avoid tap-to-wake rebound.
            XposedHelpers.findAndHookMethod(
                TOUCH_INTERACTION_HANDLER_CLASS,
                lpparam.classLoader,
                "onTouchEvent",
                MotionEvent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val now = SystemClock.uptimeMillis()
                        if (now < blockTouchesUntil) {
                            param.result = true
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("NothingXpert: TouchHandlingViewInteractionHandler onTouchEvent hook failed: $t")
        }

    }

    private fun goToSleepFromInteractor(instance: Any): Boolean {
        return try {
            val powerManager =
                XposedHelpers.getObjectField(instance, "powerManager") as? PowerManager
            val systemClock = XposedHelpers.getObjectField(instance, "systemClock")
            val uptime =
                XposedHelpers.callMethod(systemClock, "uptimeMillis") as? Long
            if (powerManager != null && uptime != null) {
                Log.i(LOG_TAG, "double-tap detected, sleeping (pm field)")
                XposedBridge.log("NothingXpert: double-tap detected, sleeping (pm field)")
                if (tryGoToSleep(powerManager, uptime)) return true
            }

            // Fallback to context -> PowerManager in case fields change.
            val context = XposedHelpers.getObjectField(instance, "context")
            val pm =
                XposedHelpers.callMethod(context, "getSystemService", "power") as? PowerManager
            if (pm != null && uptime != null) {
                Log.i(LOG_TAG, "double-tap detected, sleeping (pm service)")
                XposedBridge.log("NothingXpert: double-tap detected, sleeping (pm service)")
                if (tryGoToSleep(pm, uptime)) return true
            }
            false
        } catch (t: Throwable) {
            Log.e(LOG_TAG, "double-tap sleep failed", t)
            XposedBridge.log("NothingXpert: double-tap sleep failed: $t")
            false
        }
    }

    private fun tryGoToSleep(powerManager: PowerManager, uptime: Long): Boolean {
        // Try DPM lockNow first to mimic launcher double-tap animation.
        try {
            val app = currentApplication()
            val dpm = app?.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            dpm?.lockNow()
            // lockNow() is async but returns immediately; assume success if no exception.
            return true
        } catch (_: Throwable) {
            // ignore and fallback to PowerManager
        }

        return try {
            // Simple signature lets SystemUI decide animation anchor.
            XposedHelpers.callMethod(powerManager, "goToSleep", uptime)
            true
        } catch (_: Throwable) {
            try {
                // Application reason.
                XposedHelpers.callMethod(powerManager, "goToSleep", uptime, 0 /*APPLICATION*/, 0)
                true
            } catch (_: Throwable) {
                try {
                    XposedHelpers.callMethod(powerManager, "goToSleep", uptime, 2 /*TIMEOUT*/, 0)
                    true
                } catch (t: Throwable) {
                    Log.e(LOG_TAG, "goToSleep invocation failed", t)
                    XposedBridge.log("NothingXpert: goToSleep invocation failed: $t")
                    false
                }
            }
        }
    }

    private fun setTapPosition(x: Int, y: Int) {
        try {
            val loader = systemUiClassLoader ?: return
            val depClass = XposedHelpers.findClass("com.nothing.systemui.NTDependencyEx", loader)
            val centralClass = XposedHelpers.findClass(
                "com.nothing.systemui.statusbar.phone.CentralSurfacesImplEx",
                loader
            )
            val central = XposedHelpers.callStaticMethod(depClass, "get", centralClass)
            if (central != null) {
                XposedHelpers.callMethod(central, "setTapPos", x, y)
                XposedBridge.log("NothingXpert: setTapPos to ($x,$y)")
            } else {
                XposedBridge.log("NothingXpert: setTapPos central is null")
            }
        } catch (t: Throwable) {
            XposedBridge.log("NothingXpert: setTapPosition failed: $t")
        }
    }

    private fun installSecureFlagBypass(lpparam: XC_LoadPackage.LoadPackageParam) {
        tryStripFlagSecure(lpparam.classLoader, "android.view.WindowManagerImpl", "addView")
        // WindowManagerGlobal has an extended signature.
        tryStripFlagSecure(
            lpparam.classLoader,
            "android.view.WindowManagerGlobal",
            "addView",
            View::class.java,
            ViewGroup.LayoutParams::class.java,
            Class.forName("android.view.Display", false, lpparam.classLoader),
            Class.forName("android.view.Window", false, lpparam.classLoader)
        )

        try {
            // Also intercept addFlags / setFlags on Window.
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
        } catch (_: Throwable) {
        }

        try {
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
                            flags and WindowManager.LayoutParams.FLAG_SECURE != 0
                        ) {
                            flags = flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
                            param.args[0] = flags
                        }
                    }
                }
            )
        } catch (_: Throwable) {
        }

        try {
            // Strip FLAG_SECURE from LayoutParams after creation (constructor hook).
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
        } catch (_: Throwable) {
        }

        try {
            // Block secure surfaces (Chrome Incognito etc.).
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
        } catch (_: Throwable) {
        }

        try {
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
        } catch (_: Throwable) {
        }
    }

    private fun installPolicyVolumeHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                PHONE_WINDOW_MANAGER_CLASS,
                lpparam.classLoader,
                "interceptKeyBeforeQueueing",
                KeyEvent::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val event = param.args.getOrNull(0) as? KeyEvent ?: return
                        val pwm = param.thisObject
                        val pm = XposedHelpers.getObjectField(pwm, "mPowerManager") as? PowerManager ?: return
                        val ctx = XposedHelpers.getObjectField(pwm, "mContext") as? Context
                        runnableContext = ctx
                        if (handleVolumeForTracks(event, pm, ctx)) {
                            XposedBridge.log("NothingXpert: interceptBeforeQueueing consumed")
                            param.result = 0 // drop event from queue
                        }
                    }
                }
            )
            policyVolumeInstalled = true
        } catch (t: Throwable) {
            XposedBridge.log("NothingXpert: interceptKeyBeforeQueueing hook failed: $t")
        }

        try {
            XposedHelpers.findAndHookMethod(
                PHONE_WINDOW_MANAGER_CLASS,
                lpparam.classLoader,
                "interceptKeyBeforeDispatching",
                Class.forName("android.view.WindowManagerPolicy\$WindowState", false, lpparam.classLoader),
                KeyEvent::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val event = param.args.getOrNull(1) as? KeyEvent ?: return
                        val pwm = param.thisObject
                        val pm = XposedHelpers.getObjectField(pwm, "mPowerManager") as? PowerManager ?: return
                        val ctx = XposedHelpers.getObjectField(pwm, "mContext") as? Context
                        runnableContext = ctx
                        if (handleVolumeForTracks(event, pm, ctx)) {
                            XposedBridge.log("NothingXpert: interceptBeforeDispatch consumed")
                            param.result = 0L // consume
                        }
                    }
                }
            )
            policyVolumeInstalled = true
        } catch (t: Throwable) {
            XposedBridge.log("NothingXpert: policy volume hook failed: $t")
        }
    }

    private fun tryStripFlagSecure(cl: ClassLoader, clazz: String, method: String) {
        tryStripFlagSecure(cl, clazz, method, View::class.java, ViewGroup.LayoutParams::class.java)
    }

    private fun tryStripFlagSecure(
        cl: ClassLoader,
        clazz: String,
        method: String,
        vararg sig: Class<*>
    ) {
        try {
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
        } catch (_: Throwable) {
        }
    }

    private fun stripSecure(lp: WindowManager.LayoutParams) {
        if (lp.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) {
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
            XposedBridge.log("NothingXpert: cleared FLAG_SECURE on ${lp.packageName}")
        }
        // Some apps use private flags; attempt to clear the same bit if present.
        try {
            val field = WindowManager.LayoutParams::class.java.getDeclaredField("privateFlags")
            field.isAccessible = true
            val current = field.getInt(lp)
            val secureBit = WindowManager.LayoutParams.FLAG_SECURE
            if (current and secureBit != 0) {
                field.setInt(lp, current and secureBit.inv())
                XposedBridge.log("NothingXpert: cleared private FLAG_SECURE on ${lp.packageName}")
            }
        } catch (_: Throwable) {
        }
    }

    private fun currentApplication(): android.app.Application? {
        return try {
            val at = Class.forName("android.app.ActivityThread")
            val method = at.getMethod("currentApplication")
            method.invoke(null) as? android.app.Application
        } catch (_: Throwable) {
            null
        }
    }

    private fun isSingleTapEnabled(): Boolean {
        // Try module preference first (works even if SystemUI process lacks direct app context).
        try {
            val file = java.io.File("/data/user_de/0/$MODULE_PKG/shared_prefs/${MODULE_PKG}_preferences.xml")
            val xsp = if (file.exists()) XSharedPreferences(file) else XSharedPreferences(MODULE_PKG, "${MODULE_PKG}_preferences")
            xsp.makeWorldReadable()
            if (xsp.hasFileChanged()) xsp.reload()
            if (xsp.contains(PREF_SINGLE_TAP)) {
                return xsp.getBoolean(PREF_SINGLE_TAP, true)
            }
        } catch (_: Throwable) {
            // ignore and fall back
        }

        val app = currentApplication() ?: return true
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(app)
        return prefs.getBoolean(PREF_SINGLE_TAP, true)
    }

    private fun isAllowSecureScreenshots(): Boolean {
        // XSharedPreferences to read across processes
        try {
            val file = java.io.File("/data/user_de/0/$MODULE_PKG/shared_prefs/${MODULE_PKG}_preferences.xml")
            val xsp = if (file.exists()) XSharedPreferences(file) else XSharedPreferences(MODULE_PKG, "${MODULE_PKG}_preferences")
            xsp.makeWorldReadable()
            if (xsp.hasFileChanged()) xsp.reload()
            if (xsp.contains(PREF_ALLOW_SECURE)) {
                return xsp.getBoolean(PREF_ALLOW_SECURE, false)
            }
        } catch (_: Throwable) {
        }
        val app = currentApplication() ?: return false
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(app)
        return prefs.getBoolean(PREF_ALLOW_SECURE, false)
    }

    private fun isShufflePinEnabled(): Boolean {
        try {
            val file = java.io.File("/data/user_de/0/$MODULE_PKG/shared_prefs/${MODULE_PKG}_preferences.xml")
            val xsp = if (file.exists()) XSharedPreferences(file) else XSharedPreferences(MODULE_PKG, "${MODULE_PKG}_preferences")
            xsp.makeWorldReadable()
            if (xsp.hasFileChanged()) xsp.reload()
            if (xsp.contains(PREF_SHUFFLE_PIN)) {
                return xsp.getBoolean(PREF_SHUFFLE_PIN, false)
            }
        } catch (_: Throwable) {
        }
        val app = currentApplication() ?: return false
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(app)
        return prefs.getBoolean(PREF_SHUFFLE_PIN, false)
    }

    private fun getVolumeUpAction(): Int {
        try {
            val file = java.io.File("/data/user_de/0/$MODULE_PKG/shared_prefs/${MODULE_PKG}_preferences.xml")
            val xsp = if (file.exists()) XSharedPreferences(file) else XSharedPreferences(MODULE_PKG, "${MODULE_PKG}_preferences")
            xsp.makeWorldReadable()
            if (xsp.hasFileChanged()) xsp.reload()
            if (xsp.contains(PREF_VOLUME_UP_ACTION)) {
                return xsp.getString(PREF_VOLUME_UP_ACTION, "0")?.toIntOrNull() ?: ACTION_DEFAULT
            }
        } catch (_: Throwable) {
        }
        val app = currentApplication() ?: return ACTION_DEFAULT
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(app)
        return prefs.getString(PREF_VOLUME_UP_ACTION, "0")?.toIntOrNull() ?: ACTION_DEFAULT
    }

    private fun getVolumeDownAction(): Int {
        try {
            val file = java.io.File("/data/user_de/0/$MODULE_PKG/shared_prefs/${MODULE_PKG}_preferences.xml")
            val xsp = if (file.exists()) XSharedPreferences(file) else XSharedPreferences(MODULE_PKG, "${MODULE_PKG}_preferences")
            xsp.makeWorldReadable()
            if (xsp.hasFileChanged()) xsp.reload()
            if (xsp.contains(PREF_VOLUME_DOWN_ACTION)) {
                return xsp.getString(PREF_VOLUME_DOWN_ACTION, "0")?.toIntOrNull() ?: ACTION_DEFAULT
            }
        } catch (_: Throwable) {
        }
        val app = currentApplication() ?: return ACTION_DEFAULT
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(app)
        return prefs.getString(PREF_VOLUME_DOWN_ACTION, "0")?.toIntOrNull() ?: ACTION_DEFAULT
    }

    private fun sendMediaCommand(keyCode: Int, context: Context? = currentApplication()): Boolean {
        val audio = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return try {
            val down = KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, keyCode, 0)
            val up = KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0)
            audio.dispatchMediaKeyEvent(down)
            audio.dispatchMediaKeyEvent(up)
            true
        } catch (t: Throwable) {
            XposedBridge.log("NothingXpert: sendMediaCommand failed: $t")
            false
        }
    }

    private fun executeVolumeAction(action: Int, context: Context?): Boolean {
        return try {
            when (action) {
                ACTION_NONE -> true // do nothing, but consume
                ACTION_TORCH -> {
                    toggleFlashlight(context)
                    true
                }
                ACTION_PLAY_PAUSE -> sendMediaCommand(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, context)
                ACTION_NEXT -> sendMediaCommand(KeyEvent.KEYCODE_MEDIA_NEXT, context)
                ACTION_PREV -> sendMediaCommand(KeyEvent.KEYCODE_MEDIA_PREVIOUS, context)
                else -> false
            }
        } catch (t: Throwable) {
            XposedBridge.log("NothingXpert: executeVolumeAction failed: $t")
            false
        }
    }

    private fun toggleFlashlight(context: Context?) {
        try {
            val cameraManager = context?.getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
            if (cameraManager == null) {
                XposedBridge.log("NothingXpert: CameraManager is null")
                return
            }
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                characteristics.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (cameraId == null) {
                XposedBridge.log("NothingXpert: No camera with flash found")
                return
            }
            // Toggle state - we need to track current state
            val isOn = flashlightOn
            cameraManager.setTorchMode(cameraId, !isOn)
            flashlightOn = !isOn
            XposedBridge.log("NothingXpert: Flashlight toggled to ${!isOn}")
        } catch (t: Throwable) {
            XposedBridge.log("NothingXpert: toggleFlashlight failed: $t")
        }
    }

    private fun shufflePinPad(pinView: Any) {
        try {
            val buttons = XposedHelpers.getObjectField(pinView, "mButtons") as? Array<*>
            if (buttons == null || buttons.isEmpty()) return
            val digits = (0..9).toMutableList()
            digits.shuffle()
            buttons.forEachIndexed { index, raw ->
                if (index >= digits.size) return@forEachIndexed
                val button = raw ?: return@forEachIndexed
                val newDigit = digits[index]
                XposedHelpers.setIntField(button, "mDigit", newDigit)
                val digitText = XposedHelpers.getObjectField(button, "mDigitText") as? TextView
                digitText?.text = newDigit.toString()
                try {
                    XposedHelpers.callMethod(button, "setContentDescription", newDigit.toString())
                } catch (_: Throwable) {
                }
            }
            XposedBridge.log("NothingXpert: shuffled PIN layout")
        } catch (t: Throwable) {
            XposedBridge.log("NothingXpert: shuffle PIN failed: $t")
        }
    }

    private fun handleVolumeForTracks(
        event: KeyEvent,
        powerManager: PowerManager,
        context: Context?
    ): Boolean {
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return false

        // Check if this specific key has a configured action
        val configuredAction = if (code == KeyEvent.KEYCODE_VOLUME_UP) getVolumeUpAction() else getVolumeDownAction()
        if (configuredAction == ACTION_DEFAULT) return false // let system handle

        val screenOff = !powerManager.isInteractive
        if (!screenOff) return false

        if (context != null) runnableContext = context

        val eventAction = event.action
        val repeat = event.repeatCount
        XposedBridge.log("NothingXpert: vol evt code=$code action=$eventAction repeat=$repeat screenOff=$screenOff configuredAction=$configuredAction")

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    // Fresh press: clear and arm single fire per hold.
                    if (code == KeyEvent.KEYCODE_VOLUME_UP) {
                        skipUpSent = false
                        handler.removeCallbacks(skipUpRunnable)
                        handler.postDelayed(skipUpRunnable, VOLUME_LONG_PRESS_DELAY_MS)
                    } else {
                        skipDownSent = false
                        handler.removeCallbacks(skipDownRunnable)
                        handler.postDelayed(skipDownRunnable, VOLUME_LONG_PRESS_DELAY_MS)
                    }
                }
                return true // always consume to stop volume change
            }
            KeyEvent.ACTION_UP -> {
                if (code == KeyEvent.KEYCODE_VOLUME_UP) {
                    handler.removeCallbacks(skipUpRunnable)
                    skipUpSent = false
                } else {
                    handler.removeCallbacks(skipDownRunnable)
                    skipDownSent = false
                }
                XposedBridge.log("NothingXpert: vol up reset code=$code")
                return true
            }
        }
        return true
    }

    private companion object {
        const val LOG_TAG = "NothingXpert"
        const val SYSTEMUI_PKG = "com.android.systemui"
        const val MODULE_PKG = "com.nothingxpert"
        const val KEYGUARD_TOUCH_CLASS =
            "com.android.systemui.keyguard.domain.interactor.KeyguardTouchHandlingInteractor"
        const val KEYGUARD_TOUCH_VIEWMODEL_CLASS =
            "com.android.systemui.keyguard.ui.viewmodel.KeyguardTouchHandlingViewModel"
        const val SYSTEMUI_APP_CLASS = "com.android.systemui.SystemUIApplication"
        const val TOUCH_HANDLING_VIEW_CLASS = "com.android.systemui.common.ui.view.TouchHandlingView"
        const val KEYGUARD_TOUCH_LISTENER_CLASS =
            "com.android.systemui.keyguard.ui.binder.KeyguardTouchViewBinder\$bind\$1"
        const val TOUCH_INTERACTION_GESTURE_CLASS =
            "com.android.systemui.common.ui.view.TouchHandlingViewInteractionHandler\$gestureDetector\$1"
        const val TOUCH_INTERACTION_HANDLER_CLASS =
            "com.android.systemui.common.ui.view.TouchHandlingViewInteractionHandler"
        const val DOZE_TRIGGERS_CLASS = "com.android.systemui.doze.DozeTriggers"
        const val NT_TAP_HANDLE_CLASS = "com.nothing.systemui.statusbar.phone.NTTapHandle"
        const val PHONE_WINDOW_MANAGER_CLASS = "com.android.server.policy.PhoneWindowManager"
        const val TOUCH_BLOCK_MS = 0L
        const val PREF_SINGLE_TAP = "pref_single_tap_sleep"
        const val PREF_ALLOW_SECURE = "pref_allow_secure_screenshot"
        const val PREF_VOLUME_UP_ACTION = "pref_volume_up_action"
        const val PREF_VOLUME_DOWN_ACTION = "pref_volume_down_action"
        const val PREF_SHUFFLE_PIN = "pref_shuffle_pin"
        const val ENABLE_DOUBLE_TAP = false
        const val VOLUME_LONG_PRESS_DELAY_MS = 350L
        
        // Volume action constants (matching PixelXpert)
        const val ACTION_NONE = -1
        const val ACTION_DEFAULT = 0
        const val ACTION_TORCH = 1
        const val ACTION_PLAY_PAUSE = 5
        const val ACTION_NEXT = 6
        const val ACTION_PREV = 7

        // Doze sensor IDs that can wake the device (tap, double tap, pickups, gestures).
        private val DOZE_BLOCKED_SENSORS = setOf(3, 4, 7, 8, 10, 11, 14)

        @Volatile
        private var blockTouchesUntil: Long = 0L

        @Volatile
        private var systemUiClassLoader: ClassLoader? = null

        @Volatile
        private var skipUpSent: Boolean = false

        @Volatile
        private var skipDownSent: Boolean = false

        @Volatile
        private var runnableContext: Context? = null

        @Volatile
        private var policyVolumeInstalled: Boolean = false

        @Volatile
        private var flashlightOn: Boolean = false

    }
}
