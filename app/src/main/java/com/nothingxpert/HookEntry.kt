package com.nothingxpert

import android.app.Activity
import android.os.Bundle
import android.app.admin.DevicePolicyManager
import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.content.Intent
import android.content.ComponentName
import android.media.AudioManager
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.TextView
import android.view.Gravity
import android.graphics.Color
import android.widget.FrameLayout
import android.view.inputmethod.EditorInfo
import java.util.concurrent.atomic.AtomicBoolean
import de.robv.android.xposed.XSharedPreferences
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt
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

    private fun registerShakeTorch(context: Context) {
        if (shakeListenerRegistered) return
        try {
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager ?: return
            val accel = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) ?: return
            val prox = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_PROXIMITY)
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return

            val listener = object : android.hardware.SensorEventListener {
                private val gravity = FloatArray(3)
                override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
                override fun onSensorChanged(event: android.hardware.SensorEvent) {
                    if (!isShakeTorchEnabled()) return
                    if (pm.isInteractive) return // only when screen off
                    if (isProximityNear) return // avoid in pocket or face-down close
                    val alpha = 0.8f
                    gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0]
                    gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1]
                    gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2]

                    val linearX = event.values[0] - gravity[0]
                    val linearY = event.values[1] - gravity[1]
                    val linearZ = event.values[2] - gravity[2]

                    val magnitude = kotlin.math.sqrt(
                        linearX * linearX + linearY * linearY + linearZ * linearZ
                    )
                    val now = SystemClock.uptimeMillis()
                    if (magnitude > SHAKE_THRESHOLD && now - lastShakeTs > SHAKE_COOLDOWN_MS) {
                        lastShakeTs = now
                        toggleFlashlight(context)
                    }
                }
            }
            sensorManager.registerListener(
                listener,
                accel,
                android.hardware.SensorManager.SENSOR_DELAY_GAME
            )
            if (prox != null) {
                sensorManager.registerListener(
                    object : android.hardware.SensorEventListener {
                        override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
                        override fun onSensorChanged(event: android.hardware.SensorEvent) {
                            val v = event.values.firstOrNull() ?: return
                            isProximityNear = v < prox.maximumRange
                        }
                    },
                    prox,
                    android.hardware.SensorManager.SENSOR_DELAY_NORMAL
                )
            }
            shakeListenerRegistered = true
            XposedBridge.log("NothingXpert: shake torch listener registered")
        } catch (t: Throwable) {
            XposedBridge.log("NothingXpert: failed to register shake listener: $t")
        }
    }

    private fun startCpuReporter(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val at = Class.forName("android.app.ActivityThread")
            val thread = XposedHelpers.callStaticMethod(at, "currentActivityThread")
            val sysContext = XposedHelpers.callMethod(thread, "getSystemContext") as? Context ?: return
            if (cpuReporterStarted) return
            cpuReporterStarted = true
            XposedBridge.log("NothingXpert: starting CPU reporter")
            val handler = Handler(Looper.getMainLooper())
            val runnable = object : Runnable {
                override fun run() {
                    try {
                        val usage = sampleCpuUsage()
                        if (usage != null) {
                            android.provider.Settings.Global.putString(sysContext.contentResolver, "nothingxpert_cpu_usage", usage.toString())
                        }
                        val temp = readCpuTemp(sysContext)
                        if (temp != null) {
                            android.provider.Settings.Global.putString(sysContext.contentResolver, "nothingxpert_cpu_temp", temp.toString())
                        }
                    } catch (_: Throwable) { }
                    handler.postDelayed(this, 2000)
                }
            }
            handler.post(runnable)
        } catch (t: Throwable) {
            XposedBridge.log("NothingXpert: CPU reporter init failed: $t")
        }
    }

    private fun sampleCpuUsage(): Int? {
        val line = try {
            java.io.File("/proc/stat").bufferedReader().useLines { seq ->
                seq.firstOrNull { it.startsWith("cpu ") }
            }
        } catch (_: Throwable) { null } ?: return null
        val parts = line.trim().split("\\s+".toRegex())
        if (parts.size < 8) return null
        val user = parts[1].toLongOrNull() ?: return null
        val nice = parts[2].toLongOrNull() ?: return null
        val system = parts[3].toLongOrNull() ?: return null
        val idle = parts[4].toLongOrNull() ?: return null
        val iowait = parts.getOrNull(5)?.toLongOrNull() ?: 0
        val irq = parts.getOrNull(6)?.toLongOrNull() ?: 0
        val softirq = parts.getOrNull(7)?.toLongOrNull() ?: 0
        val steal = parts.getOrNull(8)?.toLongOrNull() ?: 0

        val idleAll = idle + iowait
        val nonIdle = user + nice + system + irq + softirq + steal
        val total = idleAll + nonIdle

        if (lastCpuTotal < 0 || lastCpuIdle < 0) {
            lastCpuTotal = total
            lastCpuIdle = idleAll
            return null
        }
        val totalDiff = total - lastCpuTotal
        val idleDiff = idleAll - lastCpuIdle
        lastCpuTotal = total
        lastCpuIdle = idleAll
        if (totalDiff <= 0 || idleDiff < 0) return null
        return (((totalDiff - idleDiff).toDouble() / totalDiff.toDouble()) * 100.0).toInt().coerceIn(0, 100)
    }

    private fun readCpuTemp(context: Context): Int? {
        // try standard thermal zones
        for (i in 0..120) {
            val type = try {
                java.io.File("/sys/class/thermal/thermal_zone$i/type").readText().trim().lowercase()
            } catch (_: Throwable) { continue }
            if (type.contains("cpu") || type.contains("cpuss") || type.contains("soc")) {
                val raw = try {
                    java.io.File("/sys/class/thermal/thermal_zone$i/temp").readText().trim()
                } catch (_: Throwable) { continue }
                val v = raw.toIntOrNull() ?: continue
                val c = if (v > 1000 || v < -1000) v / 1000 else v
                if (c in 0..120) return c
            }
        }
        return null
    }

    private fun registerScreenOffReset() {
        if (screenOffReceiverRegistered) return
        val ctx = currentApplication() ?: return
        try {
            val filter = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    lastWakeTapTs = 0L
                    lastWakeTapX = -1
                    lastWakeTapY = -1
                    if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                        lastScreenOffTs = SystemClock.uptimeMillis()
                    }
                    unlockedPackages.clear()
                    XposedBridge.log("NothingXpert: Cleared app-lock cache on screen off")
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                ctx.registerReceiver(receiver, filter)
            }
            screenOffReceiverRegistered = true
        } catch (t: Throwable) {
            XposedBridge.log("NothingXpert: failed to register screen-off receiver: $t")
        }
    }

    private fun registerImeToggleReceiver() {
        if (imeReceiverRegistered) return
        val ctx = getSystemContext() ?: return
        try {
            val filter = android.content.IntentFilter(ACTION_IME_BAR_TOGGLED)
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action != ACTION_IME_BAR_TOGGLED) return
                    forceStopPackage(ctx, GBOARD_PKG)
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                ctx.registerReceiver(receiver, filter)
            }
            imeReceiverRegistered = true
            XposedBridge.log("NothingXpert: IME toggle receiver registered")
        } catch (t: Throwable) {
            XposedBridge.log("NothingXpert: Failed to register IME toggle receiver: $t")
        }
    }

    private fun getSystemContext(): Context? {
        return try {
            val at = Class.forName("android.app.ActivityThread")
            val thread = XposedHelpers.callStaticMethod(at, "currentActivityThread")
            XposedHelpers.callMethod(thread, "getSystemContext") as? Context
        } catch (_: Throwable) {
            null
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
            installInputManagerVolumeHook(lpparam)
            startCpuReporter(lpparam)
            registerImeToggleReceiver()
            return
        }

        // Optional global hook: remove FLAG_SECURE when enabled.
        if (isAllowSecureScreenshots()) {
            installSecureFlagBypass(lpparam)
        }

        // Ensure app-lock state resets on screen off.
        registerScreenOffReset()

        // Install App Lock Hook for all apps
        installAppLockHook(lpparam)

        if (lpparam.packageName == GBOARD_PKG) {
            installHideImeBarHook(lpparam)
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

        installHideNavbarHook(lpparam)
        installTapToWakeRemapHook(lpparam)

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
                        (param.thisObject as? Context)?.let { ctx ->
                            runnableContext = ctx.applicationContext
                            registerShakeTorch(ctx.applicationContext)
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("NothingXpert: SystemUIApplication hook failed: $t")
        }

        if (ENABLE_DOUBLE_TAP || isSingleTapEnabled()) {
            try {
                // Ensure double-tap detection is enabled on the lockscreen TouchHandlingView only when needed.
                XposedHelpers.findAndHookMethod(
                    TOUCH_HANDLING_VIEW_CLASS,
                    lpparam.classLoader,
                    "setDoublePressHandlingEnabled",
                    Boolean::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            // Only enforce while screen is ON and we're on keyguard.
                            val ctx = (param.thisObject as? View)?.context
                            val pm = ctx?.getSystemService(Context.POWER_SERVICE) as? PowerManager
                            val km = ctx?.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                            val screenOn = pm?.isInteractive == true
                            val onKeyguard = km?.isKeyguardLocked == true
                            if (!(screenOn && onKeyguard)) return

                            if (param.args.isNotEmpty() && param.args[0] is Boolean) {
                                val enabled = param.args[0] as Boolean
                                if (!enabled) {
                                    param.args[0] = true
                                }
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                XposedBridge.log("NothingXpert: TouchHandlingView hook failed: $t")
            }
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

        // Install status bar double-tap-to-sleep hook only if enabled
        if (isStatusBarDoubleTapEnabled()) {
            installStatusBarDoubleTapHook(lpparam)
        }

        if (ENABLE_DOUBLE_TAP) {
            try {
                // Directly hook the double-tap listener on the lockscreen view.
                XposedHelpers.findAndHookMethod(
                    KEYGUARD_TOUCH_LISTENER_CLASS,
                    lpparam.classLoader,
                    "onDoubleTapDetected",
                    android.view.View::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val view = param.args.getOrNull(0) as? android.view.View ?: return
                            val pm = view.context.getSystemService(PowerManager::class.java) ?: return
                            val km = view.context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                            if (pm.isInteractive.not() || km?.isKeyguardLocked != true) return
                            val uptime = SystemClock.uptimeMillis()
                            if (tryGoToSleep(pm, uptime)) {
                                param.result = null
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                XposedBridge.log("NothingXpert: KeyguardTouchViewBinder listener hook failed: $t")
            }
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
        if (isSingleTapEnabled()) {
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
                            if (!pm.isInteractive) return
                            val km = app.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                            if (km?.isKeyguardLocked != true) return

                            val x = param.args.getOrNull(0) as? Int ?: 0
                            val y = param.args.getOrNull(1) as? Int ?: 0
                            val uptime = SystemClock.uptimeMillis()
                            setTapPosition(x, y)
                            if (tryGoToSleep(pm, uptime)) {
                                blockTouchesUntil = SystemClock.uptimeMillis() + TOUCH_BLOCK_MS
                                param.result = null
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                XposedBridge.log("NothingXpert: dispatchSingleTap hook failed: $t")
            }
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

        if (ENABLE_DOUBLE_TAP) {
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
                                if (powerManager?.isInteractive != true) return

                                val uptime = SystemClock.uptimeMillis()
                                if (tryGoToSleep(powerManager, uptime)) {
                                    blockTouchesUntil = SystemClock.uptimeMillis() + TOUCH_BLOCK_MS
                                    param.result = null
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

        // Dispatch-stage hook: try window-state signature first, then fallback to (KeyEvent,int)
        var dispatchHooked = false
        try {
            val wsClass = Class.forName("android.view.WindowManagerPolicy\$WindowState", false, lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                PHONE_WINDOW_MANAGER_CLASS,
                lpparam.classLoader,
                "interceptKeyBeforeDispatching",
                wsClass,
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
                            XposedBridge.log("NothingXpert: interceptBeforeDispatch consumed (WS)")
                            param.result = 0L
                        }
                    }
                }
            )
            dispatchHooked = true
        } catch (t: Throwable) {
            XposedBridge.log("NothingXpert: interceptKeyBeforeDispatching WS signature failed: $t")
        }

        if (!dispatchHooked) {
            try {
                XposedHelpers.findAndHookMethod(
                    PHONE_WINDOW_MANAGER_CLASS,
                    lpparam.classLoader,
                    "interceptKeyBeforeDispatching",
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
                                XposedBridge.log("NothingXpert: interceptBeforeDispatch consumed (fallback)")
                                param.result = 0L
                            }
                        }
                    }
                )
                dispatchHooked = true
            } catch (t: Throwable) {
                XposedBridge.log("NothingXpert: interceptKeyBeforeDispatching fallback failed: $t")
            }
        }

        if (dispatchHooked) policyVolumeInstalled = true
    }

    private fun installInputManagerVolumeHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.server.wm.InputManagerCallback",
                lpparam.classLoader,
                "interceptKeyBeforeQueueing",
                KeyEvent::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val event = param.args.getOrNull(0) as? KeyEvent ?: return
                        val ctx = getSystemContext() ?: return
                        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
                        runnableContext = ctx
                        if (handleVolumeForTracks(event, pm, ctx)) {
                            XposedBridge.log("NothingXpert: InputManagerCallback consumed")
                            param.result = 0
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("NothingXpert: InputManagerCallback hook failed: $t")
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

    private val unlockedPackages = java.util.Collections.synchronizedSet(HashSet<String>())
    private fun isAppUnlocked(packageName: String): Boolean {
        return unlockedPackages.contains(packageName)
    }
    
    private fun installAppLockHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName
        if (pkg == "android" || pkg == SYSTEMUI_PKG || pkg == "com.android.launcher3" || pkg == MODULE_PKG) return
        
        try {
            XposedHelpers.findAndHookMethod(
                android.app.Instrumentation::class.java,
                "callActivityOnCreate",
                Activity::class.java,
                Bundle::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = param.args[0] as Activity
                        if (isAppLocked(activity.packageName)) {
                            Log.i(LOG_TAG, "Locked app launched: ${activity.packageName}")
                            XposedBridge.log("NothingXpert: Locked app launched: ${activity.packageName}")
                            // We can't block onCreate easily without crashing, but we can overlay immediately
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
        } catch (t: Throwable) {
            XposedBridge.log("NothingXpert: App Lock hook failed: $t")
        }
    }

    private fun isAppLocked(packageName: String): Boolean {
        // Try device-encrypted storage first
        try {
            val deFile = java.io.File("/data/user_de/0/$MODULE_PKG/shared_prefs/${MODULE_PKG}_preferences.xml")
            if (deFile.exists() && deFile.canRead()) {
                val xsp = XSharedPreferences(deFile)
                xsp.makeWorldReadable()
                if (xsp.hasFileChanged()) xsp.reload()
                val lockedSet = xsp.getStringSet("pref_locked_packages", emptySet())
                if (lockedSet?.contains(packageName) == true) return true
            }
        } catch (_: Throwable) {
        }

        // Try credential-encrypted storage
        try {
            val ceFile = java.io.File("/data/data/$MODULE_PKG/shared_prefs/${MODULE_PKG}_preferences.xml")
            if (ceFile.exists() && ceFile.canRead()) {
                val xsp = XSharedPreferences(ceFile)
                xsp.makeWorldReadable()
                if (xsp.hasFileChanged()) xsp.reload()
                val lockedSet = xsp.getStringSet("pref_locked_packages", emptySet())
                if (lockedSet?.contains(packageName) == true) return true
            }
        } catch (_: Throwable) {
        }

        // Fallback to standard XSharedPreferences
        try {
            val xsp = XSharedPreferences(MODULE_PKG, "${MODULE_PKG}_preferences")
            xsp.makeWorldReadable()
            if (xsp.hasFileChanged()) xsp.reload()
            val lockedSet = xsp.getStringSet("pref_locked_packages", emptySet())
            return lockedSet?.contains(packageName) == true
        } catch (_: Throwable) {
            return false
        }
    }



    private fun showLockOverlay(activity: Activity) {
        val frameLayout = FrameLayout(activity)
        frameLayout.setBackgroundColor(Color.BLACK)
        frameLayout.isClickable = true
        frameLayout.isFocusable = true
        
        val textView = TextView(activity)
        textView.text = "Locked by Nothing Xpert"
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
        
        // Register receiver for unlock
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
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            activity.registerReceiver(receiver, filter)
        }

        // Launch Lock Activity from NothingXpert
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
            XposedBridge.log("NothingXpert: Failed to launch lock screen: $e")
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
        return getPreferenceBoolean(PREF_SINGLE_TAP, false)
    }

    private fun isAllowSecureScreenshots(): Boolean {
        return getPreferenceBoolean(PREF_ALLOW_SECURE, false)
    }

    private fun isShufflePinEnabled(): Boolean {
        return getPreferenceBoolean(PREF_SHUFFLE_PIN, false)
    }

    private fun isShakeTorchEnabled(): Boolean {
        return getPreferenceBoolean(PREF_SHAKE_TORCH, false)
    }

    private fun isHideImeBarEnabled(): Boolean {
        return getPreferenceBoolean(PREF_HIDE_IME_BAR, false)
    }

    private fun isDoubleTapWakeEnabled(): Boolean {
        return getPreferenceBoolean(PREF_DOUBLE_TAP_WAKE, false)
    }

    private fun xlog(message: String) {
        XposedBridge.log(message)
    }
    private fun isHideNavbarEnabled(): Boolean {
        return ENABLE_HIDE_NAVBAR
    }

    private fun getVolumeUpAction(): Int {
        return getPreferenceString(PREF_VOLUME_UP_ACTION, "0").toIntOrNull() ?: ACTION_DEFAULT
    }

    private fun getVolumeDownAction(): Int {
        return getPreferenceString(PREF_VOLUME_DOWN_ACTION, "0").toIntOrNull() ?: ACTION_DEFAULT
    }

    private fun getPreferenceString(key: String, defValue: String): String {
        // Try device-encrypted storage first (accessible before unlock)
        try {
            val deFile = java.io.File("/data/user_de/0/$MODULE_PKG/shared_prefs/${MODULE_PKG}_preferences.xml")
            if (deFile.exists() && deFile.canRead()) {
                val xsp = XSharedPreferences(deFile)
                xsp.makeWorldReadable()
                if (xsp.hasFileChanged()) xsp.reload()
                val value = xsp.getString(key, null)
                if (value != null) {
                    return value
                }
            }
        } catch (t: Throwable) {
            XposedBridge.log("NothingXpert: getPreferenceString DE failed for $key: $t")
        }

        // Try credential-encrypted storage
        try {
            val ceFile = java.io.File("/data/data/$MODULE_PKG/shared_prefs/${MODULE_PKG}_preferences.xml")
            if (ceFile.exists() && ceFile.canRead()) {
                val xsp = XSharedPreferences(ceFile)
                xsp.makeWorldReadable()
                if (xsp.hasFileChanged()) xsp.reload()
                val value = xsp.getString(key, null)
                if (value != null) {
                    return value
                }
            }
        } catch (t: Throwable) {
            XposedBridge.log("NothingXpert: getPreferenceString CE failed for $key: $t")
        }

        // Fallback to standard XSharedPreferences with package name
        try {
            val xsp = XSharedPreferences(MODULE_PKG, "${MODULE_PKG}_preferences")
            xsp.makeWorldReadable()
            if (xsp.hasFileChanged()) xsp.reload()
            val value = xsp.getString(key, null)
            if (value != null) {
                return value
            }
        } catch (t: Throwable) {
            XposedBridge.log("NothingXpert: getPreferenceString XSP failed for $key: $t")
        }

        return defValue
    }

    private fun sendMediaCommand(keyCode: Int, context: Context?): Boolean {
        val ctx = context ?: runnableContext ?: getSystemContext()
        val audio = ctx?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audio == null) {
            XposedBridge.log("NothingXpert: sendMediaCommand AudioManager is null, context=$ctx")
            return false
        }
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
        val ctx = context ?: runnableContext ?: getSystemContext()
        return try {
            when (action) {
                ACTION_NONE -> true // do nothing, but consume
                ACTION_TORCH -> {
                    toggleFlashlight(ctx)
                    true
                }
                ACTION_PLAY_PAUSE -> sendMediaCommand(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, ctx)
                ACTION_NEXT -> sendMediaCommand(KeyEvent.KEYCODE_MEDIA_NEXT, ctx)
                ACTION_PREV -> sendMediaCommand(KeyEvent.KEYCODE_MEDIA_PREVIOUS, ctx)
                else -> false
            }
        } catch (t: Throwable) {
            XposedBridge.log("NothingXpert: executeVolumeAction failed: $t")
            false
        }
    }

    private fun toggleFlashlight(context: Context?) {
        try {
            val ctx = context ?: runnableContext ?: getSystemContext()
            val cameraManager = ctx?.getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
            if (cameraManager == null) {
                XposedBridge.log("NothingXpert: CameraManager is null, context=$ctx")
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

        if (isVolumeEventHandled(event)) return true
        markVolumeEventHandled(event)

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
                return true // consume to stop volume change on long-press candidates
            }
            KeyEvent.ACTION_UP -> {
                val longPressFired = if (code == KeyEvent.KEYCODE_VOLUME_UP) {
                    handler.removeCallbacks(skipUpRunnable)
                    val fired = skipUpSent
                    skipUpSent = false
                    fired
                } else {
                    handler.removeCallbacks(skipDownRunnable)
                    val fired = skipDownSent
                    skipDownSent = false
                    fired
                }
                if (!longPressFired) {
                    val ctx = context ?: getSystemContext()
                    if (ctx != null) {
                        adjustShortPressVolume(ctx, code)
                    }
                }
                XposedBridge.log("NothingXpert: vol up reset code=$code longPress=$longPressFired")
                return true
            }
        }
        return true
    }

    private fun adjustShortPressVolume(ctx: Context, code: Int) {
        try {
            val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val direction = if (code == KeyEvent.KEYCODE_VOLUME_UP) {
                AudioManager.ADJUST_RAISE
            } else {
                AudioManager.ADJUST_LOWER
            }
            audio.adjustSuggestedStreamVolume(
                direction,
                AudioManager.USE_DEFAULT_STREAM_TYPE,
                0
            )
        } catch (t: Throwable) {
            XposedBridge.log("NothingXpert: adjustShortPressVolume failed: $t")
        }
    }

    private fun isVolumeEventHandled(event: KeyEvent): Boolean {
        return event.eventTime == lastHandledVolumeEventTime &&
            event.action == lastHandledVolumeEventAction &&
            event.keyCode == lastHandledVolumeEventCode
    }

    private fun markVolumeEventHandled(event: KeyEvent) {
        lastHandledVolumeEventTime = event.eventTime
        lastHandledVolumeEventAction = event.action
        lastHandledVolumeEventCode = event.keyCode
    }

    companion object {
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
        const val PREF_SHAKE_TORCH = "pref_shake_torch"
        const val PREF_HIDE_IME_BAR = "pref_hide_ime_bar"
        const val PREF_DOUBLE_TAP_WAKE = "pref_double_tap_wake"
        const val ACTION_IME_BAR_TOGGLED = "com.nothingxpert.action.IME_BAR_TOGGLED"
        const val EXTRA_IME_BAR_ENABLED = "enabled"
        const val GBOARD_PKG = "com.google.android.inputmethod.latin"
        const val ENABLE_HIDE_NAVBAR = true
        const val ENABLE_DOUBLE_TAP = false
        const val VOLUME_LONG_PRESS_DELAY_MS = 350L
        const val SHAKE_THRESHOLD = 19.5f
        const val SHAKE_COOLDOWN_MS = 1500L
        const val DOUBLE_TAP_WAKE_WINDOW_MS = 350L
        const val DOUBLE_TAP_WAKE_ARM_DELAY_MS = 900L
        const val DOUBLE_TAP_WAKE_SLOP_PX = 120
        private const val PREF_CACHE_MS = 5_000L
        private val prefCache = HashMap<String, Pair<Long, Boolean>>()
        private val imeDumpOnce = AtomicBoolean(false)
        @Volatile private var imeReceiverRegistered = false
        
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
        private var lastWakeTapTs: Long = 0L

        @Volatile
        private var lastScreenOffTs: Long = 0L

        @Volatile
        private var lastWakeTapX: Int = -1

        @Volatile
        private var lastWakeTapY: Int = -1


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

        @Volatile
        private var shakeListenerRegistered: Boolean = false

        @Volatile
        private var lastShakeTs: Long = 0L

        @Volatile
        private var isProximityNear: Boolean = false

        @Volatile
        private var screenOffReceiverRegistered: Boolean = false

        @Volatile
        private var cpuReporterStarted: Boolean = false

        @Volatile
        private var lastCpuTotal: Long = -1

        @Volatile
        private var lastCpuIdle: Long = -1

        @Volatile
        private var lastHandledVolumeEventTime: Long = -1L

        @Volatile
        private var lastHandledVolumeEventAction: Int = -1

        @Volatile
        private var lastHandledVolumeEventCode: Int = -1

    }

    private fun installStatusBarDoubleTapHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // Try to find the PhoneStatusBarView class
            val statusBarViewClass = try {
                XposedHelpers.findClass(
                    "com.android.systemui.statusbar.phone.PhoneStatusBarView",
                    lpparam.classLoader
                )
            } catch (e: Throwable) {
                // Fallback for different Android versions
                try {
                    XposedHelpers.findClass(
                        "com.android.systemui.statusbar.phone.NotificationPanelView",
                        lpparam.classLoader
                    )
                } catch (e2: Throwable) {
                    XposedBridge.log("NothingXpert: Could not find status bar view class: $e")
                    return
                }
            }

            XposedBridge.log("NothingXpert: Hooking status bar view: ${statusBarViewClass.name}")

            XposedHelpers.findAndHookMethod(
                statusBarViewClass,
                "onFinishInflate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val statusBarView = param.thisObject as android.view.View
                        val context = statusBarView.context

                        // Create gesture detector for double-tap
                        val gestureDetector = android.view.GestureDetector(
                            context,
                            object : android.view.GestureDetector.SimpleOnGestureListener() {
                                override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                                    if (isStatusBarDoubleTapEnabled()) {
                                        XposedBridge.log("NothingXpert: Double tap detected on status bar")
                                        
                                        // Get tap position for fade animation
                                        val x = e.x.toInt()
                                        val y = e.y.toInt()
                                        setTapPosition(x, y)
                                        
                                        // Sleep the device
                                        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                                        val uptime = android.os.SystemClock.uptimeMillis()
                                        if (powerManager != null) {
                                            tryGoToSleep(powerManager, uptime)
                                        }
                                        return true
                                    }
                                    return false
                                }
                            }
                        )

                        // Set touch listener for gesture detection
                        statusBarView.setOnTouchListener { v, event ->
                            gestureDetector.onTouchEvent(event)
                            false  // Don't consume the event, let it propagate
                        }

                        XposedBridge.log("NothingXpert: Status bar double-tap hook installed")
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("NothingXpert: Error installing status bar double-tap hook: $t")
        }
    }

    private fun installHideImeBarHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val imsClass = XposedHelpers.findClass(
                "android.inputmethodservice.InputMethodService",
                lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                imsClass,
                "onWindowShown",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isHideImeBarEnabled()) return
                        val ims = param.thisObject
                        handler.post { hideImeSwitcher(ims) }
                    }
                }
            )
            XposedHelpers.findAndHookMethod(
                imsClass,
                "onStartInputView",
                EditorInfo::class.java,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isHideImeBarEnabled()) return
                        val ims = param.thisObject
                        handler.post { hideImeSwitcher(ims) }
                    }
                }
            )
            XposedBridge.log("NothingXpert: IME bar hider installed")
        } catch (t: Throwable) {
            XposedBridge.log("NothingXpert: Failed to install IME bar hider: $t")
        }
    }

    private fun hideImeSwitcher(ims: Any) {
        try {
            val dialog = XposedHelpers.callMethod(ims, "getWindow") as? android.app.Dialog ?: return
            val window = dialog.window ?: return
            val decor = window.decorView ?: return
            val ids = listOf("input_method_nav_bar", "input_method_nav_back", "input_method_nav_ime_switcher")
            var hiddenAny = false
            for (name in ids) {
                val id = decor.resources.getIdentifier(name, "id", "android")
                if (id == 0) continue
                val v = decor.findViewById<View>(id) ?: continue
                v.visibility = View.GONE
                v.alpha = 0f
                v.layoutParams = v.layoutParams?.apply { height = 0 }
                (v.parent as? ViewGroup)?.requestLayout()
                zeroBottomPaddingUp(v)
                hiddenAny = true
            }
            if (hiddenAny) {
                XposedBridge.log("NothingXpert: IME nav bar hidden")
            }
            zeroBottomPaddingUp(decor, 6)
            adjustImeInputViewPadding(decor)
            decor.post {
                adjustImeInputViewPadding(decor)
                stripImeBottomInset(decor)
            }
            if (imeDumpOnce.compareAndSet(false, true)) {
                decor.postDelayed({ dumpImeBottomViews(decor) }, 250)
            }
            decor.requestLayout()
        } catch (t: Throwable) {
            XposedBridge.log("NothingXpert: Failed to hide IME nav bar: $t")
        }
    }

    private fun installImeInsetsOverride(root: View) {
        try {
            if (root.getTag() == "nx_ime_insets") return
            root.setTag("nx_ime_insets")
            root.setOnApplyWindowInsetsListener { _, insets ->
                if (!isHideImeBarEnabled()) return@setOnApplyWindowInsetsListener insets
                val builder = WindowInsets.Builder(insets)
                val zero = android.graphics.Insets.of(0, 0, 0, 0)
                builder.setInsets(WindowInsets.Type.navigationBars(), zero)
                builder.setInsets(WindowInsets.Type.systemBars(), zero)
                builder.setInsets(WindowInsets.Type.systemGestures(), zero)
                builder.setInsets(WindowInsets.Type.mandatorySystemGestures(), zero)
                builder.setInsets(WindowInsets.Type.tappableElement(), zero)
                try {
                    builder.setInsetsIgnoringVisibility(WindowInsets.Type.navigationBars(), zero)
                    builder.setInsetsIgnoringVisibility(WindowInsets.Type.systemBars(), zero)
                    builder.setInsetsIgnoringVisibility(WindowInsets.Type.systemGestures(), zero)
                    builder.setInsetsIgnoringVisibility(WindowInsets.Type.mandatorySystemGestures(), zero)
                    builder.setInsetsIgnoringVisibility(WindowInsets.Type.tappableElement(), zero)
                } catch (_: Throwable) {
                }
                builder.build()
            }
            if (root.paddingBottom != 0) {
                root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, 0)
            }
            root.requestApplyInsets()
            XposedBridge.log("NothingXpert: IME insets override installed")
        } catch (t: Throwable) {
            XposedBridge.log("NothingXpert: Failed to install IME insets override: $t")
        }
    }

    private fun installHideNavbarHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!isHideNavbarEnabled()) return
        try {
            val navBarViewClass = try {
                XposedHelpers.findClass("com.android.systemui.navigationbar.views.NavigationBarView", lpparam.classLoader)
            } catch (_: Throwable) {
                try {
                    XposedHelpers.findClass("com.android.systemui.navigationbar.NavigationBarView", lpparam.classLoader)
                } catch (_: Throwable) {
                    XposedHelpers.findClass("com.android.systemui.statusbar.phone.NavigationBarView", lpparam.classLoader)
                }
            }

            // Re-hide whenever nav buttons get updated
            XposedHelpers.findAndHookMethod(
                navBarViewClass,
                "updateNavButtonIcons",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isHideNavbarEnabled()) return
                        val view = param.thisObject as? ViewGroup ?: return
                        hideHomeHandle(view, "updateNavButtonIcons")
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                navBarViewClass,
                "updateStates",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isHideNavbarEnabled()) return
                        val view = param.thisObject as? ViewGroup ?: return
                        hideHomeHandle(view, "updateStates")
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                navBarViewClass,
                "onLayout",
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isHideNavbarEnabled()) return
                        val view = param.thisObject as? ViewGroup ?: return
                        hideHomeHandle(view, "onLayout")
                    }
                }
            )

        } catch (t: Throwable) {
            XposedBridge.log("NothingXpert: Failed to install hide navbar hook: $t")
        }
    }

    private fun installTapToWakeRemapHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val hostClass = "com.nothing.systemui.statusbar.phone.DozeServiceHostEx"
            XposedBridge.log("NothingXpert: Installing tap-to-wake remap hook (DozeServiceHostEx.fireSingleTap)")
            XposedHelpers.findAndHookMethod(
                hostClass,
                lpparam.classLoader,
                "fireSingleTap",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isDoubleTapWakeEnabled()) return
                        val ctx = getSystemContext()
                        val pm = ctx?.getSystemService(Context.POWER_SERVICE) as? PowerManager
                        if (pm?.isInteractive == true) return
                        val sinceOff = SystemClock.uptimeMillis() - lastScreenOffTs
                        if (sinceOff in 0..DOUBLE_TAP_WAKE_ARM_DELAY_MS) return
                        val now = SystemClock.uptimeMillis()
                        val delta = now - lastWakeTapTs
                        if (delta in 1..DOUBLE_TAP_WAKE_WINDOW_MS) {
                            if (!isSameTapArea()) {
                                lastWakeTapTs = now
                                captureLastTapPos()
                                xlog("NothingXpert: tap too far apart; resetting window")
                                param.result = null
                                return
                            }
                            lastWakeTapTs = 0L
                            try {
                                XposedHelpers.callMethod(param.thisObject, "fireDoubleTap")
                                xlog("NothingXpert: double-tap wake fired (dt=$delta)")
                            } catch (t: Throwable) {
                                xlog("NothingXpert: fireDoubleTap via host failed: $t")
                            }
                        } else {
                            lastWakeTapTs = now
                            captureLastTapPos()
                            xlog("NothingXpert: single tap swallowed, waiting for double-tap")
                        }
                        param.result = null
                    }
                }
            )

            val kvmClass = "com.nothing.systemui.keyguard.KeyguardViewMediatorEx"
            XposedBridge.log("NothingXpert: Installing tap-to-wake remap hook (KeyguardViewMediatorEx)")
            XposedHelpers.findAndHookMethod(
                kvmClass,
                lpparam.classLoader,
                "handleKeyGestureSingleTap",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isDoubleTapWakeEnabled()) return
                        val ctx = getSystemContext()
                        val pm = ctx?.getSystemService(Context.POWER_SERVICE) as? PowerManager
                        if (pm?.isInteractive == true) return
                        val sinceOff = SystemClock.uptimeMillis() - lastScreenOffTs
                        if (sinceOff in 0..DOUBLE_TAP_WAKE_ARM_DELAY_MS) return
                        val now = SystemClock.uptimeMillis()
                        val delta = now - lastWakeTapTs
                        if (delta in 1..DOUBLE_TAP_WAKE_WINDOW_MS) {
                            if (!isSameTapArea()) {
                                lastWakeTapTs = now
                                captureLastTapPos()
                                xlog("NothingXpert: KVM tap too far apart; resetting window")
                                param.result = null
                                return
                            }
                            lastWakeTapTs = 0L
                            try {
                                XposedHelpers.callMethod(param.thisObject, "handleKeyGestureDoubleTap")
                                xlog("NothingXpert: double-tap wake fired (KVM dt=$delta)")
                            } catch (t: Throwable) {
                                xlog("NothingXpert: handleKeyGestureDoubleTap failed: $t")
                            }
                        } else {
                            lastWakeTapTs = now
                            captureLastTapPos()
                            xlog("NothingXpert: KVM single tap swallowed, waiting for double-tap")
                        }
                        param.result = null
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                kvmClass,
                lpparam.classLoader,
                "handleKeyGestureDoubleTap",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isDoubleTapWakeEnabled()) return
                        lastWakeTapTs = 0L
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("NothingXpert: Tap-to-wake remap hook failed: $t")
            xlog("NothingXpert: Tap-to-wake remap hook failed: $t")
        }
    }

    private fun captureLastTapPos() {
        try {
            val point = readTapPos() ?: return
            lastWakeTapX = point.x
            lastWakeTapY = point.y
            xlog("NothingXpert: captured tap pos x=${point.x} y=${point.y}")
        } catch (t: Throwable) {
            xlog("NothingXpert: capture tap pos failed: $t")
        }
    }

    private fun isSameTapArea(): Boolean {
        if (lastWakeTapX < 0 || lastWakeTapY < 0) return true
        val point = readTapPos() ?: return false
        val dx = point.x - lastWakeTapX
        val dy = point.y - lastWakeTapY
        val slop = getDoubleTapWakeSlopPx()
        val ok = (dx * dx + dy * dy) <= (slop * slop)
        xlog("NothingXpert: tap delta dx=$dx dy=$dy slop=$slop ok=$ok")
        return ok
    }

    private fun readTapPos(): android.graphics.Point? {
        return try {
            val loader = systemUiClassLoader ?: return null
            val depClass = XposedHelpers.findClass("com.nothing.systemui.NTDependencyEx", loader)
            val centralClass = XposedHelpers.findClass(
                "com.nothing.systemui.statusbar.phone.CentralSurfacesImplEx",
                loader
            )
            val central = XposedHelpers.callStaticMethod(depClass, "get", centralClass)
            val point = try {
                XposedHelpers.callMethod(central, "getTapPos") as? android.graphics.Point
            } catch (_: Throwable) {
                null
            }
            point ?: run {
                val utilClass = XposedHelpers.findClass("com.nothing.systemui.statusbar.phone.TapPositionUtil", loader)
                val ctx = getSystemContext() ?: return null
                XposedHelpers.callStaticMethod(utilClass, "getTapPos", ctx) as? android.graphics.Point
            }
        } catch (t: Throwable) {
            xlog("NothingXpert: readTapPos failed: $t")
            null
        }
    }

    private fun getDoubleTapWakeSlopPx(): Int {
        val ctx = getSystemContext() ?: return DOUBLE_TAP_WAKE_SLOP_PX
        return try {
            val base = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
            (base * 3).coerceAtLeast(DOUBLE_TAP_WAKE_SLOP_PX)
        } catch (_: Throwable) {
            DOUBLE_TAP_WAKE_SLOP_PX
        }
    }



    private fun hideHomeHandle(navBarView: ViewGroup, source: String) {
        val handle = navBarView.findViewById<View?>(navBarView.resources.getIdentifier("home_handle", "id", "com.android.systemui"))
            ?: navBarView.findViewById<View?>(navBarView.resources.getIdentifier("home_handle", "id", "com.android.systemui.res"))
        if (handle != null) {
            var changed = false
            if (handle.visibility != View.GONE) {
                handle.visibility = View.GONE
                changed = true
            }
            if (handle.alpha != 0f) {
                handle.alpha = 0f
                changed = true
            }
            val lp = handle.layoutParams
            if (lp != null && lp.height != 0) {
                lp.height = 0
                handle.layoutParams = lp
                changed = true
            }
            if (changed) {
                (handle.parent as? ViewGroup)?.requestLayout()
                XposedBridge.log("NothingXpert: Hiding home_handle from $source")
            }
        }
    }

    private fun disableDecorFitsSystemWindows(window: android.view.Window) {
        try {
            val method = window.javaClass.getMethod("setDecorFitsSystemWindows", Boolean::class.javaPrimitiveType)
            method.invoke(window, false)
        } catch (_: Throwable) {
        }
    }

    private fun zeroBottomPaddingUp(view: View?, depth: Int = 3) {
        var current: Any? = view
        repeat(depth) {
            val v = current as? View ?: return
            if (v.paddingBottom != 0) {
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, 0)
            }
            current = v.parent
        }
    }

    private fun stripImeBottomInset(root: View) {
        val navBarHeight = getNavBarHeight(root) ?: return
        if (root.height <= 0) return
        var changed = false
        fun visit(v: View) {
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    visit(v.getChildAt(i))
                }
            }
            val name = runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()?.lowercase()
            val lp = v.layoutParams
            val heightMatches = v.height == navBarHeight || lp?.height == navBarHeight
            val nearBottom = v.bottom >= (root.height - navBarHeight - 4)
            val nameMatches = name?.contains("nav") == true ||
                name?.contains("gesture") == true ||
                name?.contains("ime") == true ||
                name?.contains("inset") == true ||
                name?.contains("bar") == true
            val isSpacer = v.javaClass.name.endsWith("Space")
            val isInputView = v.javaClass.name.contains("inputview.InputView")
            if (isInputView && v.paddingBottom != 0) {
                if (adjustImeInputViewPadding(v)) {
                    changed = true
                }
                return
            }
            if (nearBottom && (heightMatches || nameMatches || isSpacer)) {
                v.visibility = View.GONE
                v.alpha = 0f
                if (lp != null && lp.height != 0) {
                    lp.height = 0
                    v.layoutParams = lp
                }
                if (lp is ViewGroup.MarginLayoutParams && lp.bottomMargin != 0) {
                    lp.bottomMargin = 0
                    v.layoutParams = lp
                }
                if (v.paddingBottom != 0) {
                    v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, 0)
                }
                changed = true
            }
        }
        visit(root)
        if (root.paddingBottom != 0) {
            root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, 0)
            changed = true
        }
        if (changed) {
            root.requestLayout()
        }
    }

    private fun adjustImeInputViewPadding(root: View): Boolean {
        var changed = false
        val navBarHeight = getNavBarHeight(root) ?: 0
        val minPad = dpToPx(root, 8)
        val target = if (navBarHeight > 0) {
            kotlin.math.max(minPad, navBarHeight / 4)
        } else {
            minPad
        }
        fun visit(v: View) {
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    visit(v.getChildAt(i))
                }
            }
            val className = v.javaClass.name
            val isInputView = className.contains("inputview.InputView")
            if (isInputView && v.paddingBottom > target) {
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, target)
                changed = true
            }
        }
        visit(root)
        if (changed) {
            root.requestLayout()
        }
        return changed
    }

    private fun dpToPx(view: View, dp: Int): Int {
        val density = view.resources.displayMetrics.density
        return (dp * density).toInt().coerceAtLeast(1)
    }

    private fun dumpImeBottomViews(root: View) {
        val navBarHeight = getNavBarHeight(root) ?: return
        if (root.height <= 0) return
        val maxLogs = 30
        var logs = 0
        fun visit(v: View) {
            if (logs >= maxLogs) return
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    visit(v.getChildAt(i))
                    if (logs >= maxLogs) return
                }
            }
            val name = runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()
            val lp = v.layoutParams
            val bottomMargin = (lp as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
            val nearBottom = v.bottom >= (root.height - navBarHeight - 4)
            val heightMatches = v.height == navBarHeight || lp?.height == navBarHeight
            val paddingBottom = v.paddingBottom
            if (nearBottom && (heightMatches || bottomMargin > 0 || paddingBottom > 0)) {
                XposedBridge.log(
                    "NothingXpert: IME bottom view " +
                        "name=$name class=${v.javaClass.name} " +
                        "h=${v.height} lpH=${lp?.height} bottom=${v.bottom} " +
                        "padB=$paddingBottom marginB=$bottomMargin"
                )
                logs++
            }
        }
        visit(root)
        XposedBridge.log("NothingXpert: IME bottom view dump done (count=$logs)")
    }

    private fun getNavBarHeight(view: View): Int? {
        val res = view.resources
        val id = res.getIdentifier("navigation_bar_height", "dimen", "android")
        if (id == 0) return null
        return runCatching { res.getDimensionPixelSize(id) }.getOrNull()
    }

    private fun isStatusBarDoubleTapEnabled(): Boolean {
        return getPreferenceBoolean("pref_status_bar_double_tap_sleep", false)
    }

    private fun getPreferenceBoolean(key: String, defValue: Boolean): Boolean {
        val now = SystemClock.uptimeMillis()
        prefCache[key]?.let { (ts, v) ->
            if (now - ts < PREF_CACHE_MS) return v
        }

        // Try device-encrypted storage first (accessible before unlock)
        try {
            val deFile = java.io.File("/data/user_de/0/$MODULE_PKG/shared_prefs/${MODULE_PKG}_preferences.xml")
            if (deFile.exists() && deFile.canRead()) {
                val xsp = XSharedPreferences(deFile)
                xsp.makeWorldReadable()
                if (xsp.hasFileChanged()) xsp.reload()
                if (xsp.contains(key)) {
                    val v = xsp.getBoolean(key, defValue)
                    prefCache[key] = now to v
                    return v
                }
            }
        } catch (_: Throwable) {
        }

        // Try credential-encrypted storage
        try {
            val ceFile = java.io.File("/data/data/$MODULE_PKG/shared_prefs/${MODULE_PKG}_preferences.xml")
            if (ceFile.exists() && ceFile.canRead()) {
                val xsp = XSharedPreferences(ceFile)
                xsp.makeWorldReadable()
                if (xsp.hasFileChanged()) xsp.reload()
                if (xsp.contains(key)) {
                    val v = xsp.getBoolean(key, defValue)
                    prefCache[key] = now to v
                    return v
                }
            }
        } catch (_: Throwable) {
        }

        // Fallback to standard XSharedPreferences with package name
        try {
            val prefs = XSharedPreferences(MODULE_PKG, "${MODULE_PKG}_preferences")
            prefs.makeWorldReadable()
            if (prefs.hasFileChanged()) prefs.reload()
            if (prefs.contains(key)) {
                val v = prefs.getBoolean(key, defValue)
                prefCache[key] = now to v
                return v
            }
        } catch (_: Throwable) {
        }

        prefCache[key] = now to defValue
        return defValue
    }
}
