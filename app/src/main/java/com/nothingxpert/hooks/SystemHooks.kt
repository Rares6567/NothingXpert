package com.nothingxpert.hooks

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class SystemHooks : BaseHook() {
    override val tag = "System"
    
    override fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == SYSTEMUI_PKG) {
            installSystemUIHooks(lpparam)
        }
    }
    
    private fun installSystemUIHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeHook("SystemUIApplication.onCreate") {
            XposedHelpers.findAndHookMethod(
                "com.android.systemui.SystemUIApplication",
                lpparam.classLoader,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        (param.thisObject as? Context)?.let { ctx ->
                            initializeShakeTorch(ctx.applicationContext)
                        }
                    }
                }
            )
        }
        
        if (getPreferenceBoolean("pref_status_bar_double_tap_sleep", false)) {
            installStatusBarDoubleTapHook(lpparam)
        }
    }
    
    private fun initializeShakeTorch(context: Context) {
        // SystemUI-only: keep a stable app context so we can register/unregister later.
        if (shakeContext == null) shakeContext = context.applicationContext
        refreshFromPrefs()
    }
    
    private fun installStatusBarDoubleTapHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeHook("PhoneStatusBarView.onFinishInflate") {
            val statusBarViewClass = try {
                XposedHelpers.findClass(
                    "com.android.systemui.statusbar.phone.PhoneStatusBarView",
                    lpparam.classLoader
                )
            } catch (e: Throwable) {
                try {
                    XposedHelpers.findClass(
                        "com.android.systemui.statusbar.phone.NotificationPanelView",
                        lpparam.classLoader
                    )
                } catch (e2: Throwable) {
                    log("Could not find status bar view class: $e")
                    return@safeHook
                }
            }

            log("Hooking status bar view: ${statusBarViewClass.name}")

            XposedHelpers.findAndHookMethod(
                statusBarViewClass,
                "onFinishInflate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val statusBarView = param.thisObject as android.view.View
                        val context = statusBarView.context

                        val gestureDetector = android.view.GestureDetector(
                            context,
                            object : android.view.GestureDetector.SimpleOnGestureListener() {
                                override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                                    if (getPreferenceBoolean("pref_status_bar_double_tap_sleep", false)) {
                                        log("Double tap detected on status bar")
                                        
                                        val x = e.x.toInt()
                                        val y = e.y.toInt()
                                        KeyguardHooks.setTapPositionStatic(x, y)
                                        
                                        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                                        val uptime = SystemClock.uptimeMillis()
                                        if (powerManager != null) {
                                            KeyguardHooks.tryGoToSleepStatic(powerManager, uptime)
                                        }
                                        return true
                                    }
                                    return false
                                }
                            }
                        )

                        statusBarView.setOnTouchListener { _, event ->
                            gestureDetector.onTouchEvent(event)
                            false
                        }

                        log("Status bar double-tap hook installed")
                    }
                }
            )
        }
    }
    
    companion object {
        const val PREF_SHAKE_TORCH = "pref_shake_torch"
        private const val SHAKE_THRESHOLD = 19.5f
        private const val SHAKE_COOLDOWN_MS = 1500L
        
        @Volatile private var shakeListenerRegistered = false
        @Volatile private var lastShakeTs = 0L
        @Volatile private var isProximityNear = false

        // SystemUI shake-torch lifecycle
        @Volatile private var shakeContext: Context? = null
        @Volatile private var shakeSensorManager: android.hardware.SensorManager? = null
        @Volatile private var shakeAccelListener: android.hardware.SensorEventListener? = null
        @Volatile private var shakeProxListener: android.hardware.SensorEventListener? = null
        @Volatile private var shakeScreenReceiver: android.content.BroadcastReceiver? = null
        @Volatile private var shakeScreenReceiverRegistered: Boolean = false

        private fun logStatic(msg: String) {
            try {
                de.robv.android.xposed.XposedBridge.log("NothingXpert/System: $msg")
            } catch (_: Throwable) {
            }
        }

        private fun registerShakeTorchSensors(context: Context) {
            if (shakeListenerRegistered) return
            try {
                val sensorManager =
                    context.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager
                        ?: return
                val accel = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) ?: return
                val prox = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_PROXIMITY)
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return

                val accelListener = object : android.hardware.SensorEventListener {
                    private val gravity = FloatArray(3)
                    override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
                    override fun onSensorChanged(event: android.hardware.SensorEvent) {
                        // Sensors should only be registered when enabled + screen off,
                        // but keep these guards as a safety net.
                        if (!BaseHook.getPreferenceBoolean(PREF_SHAKE_TORCH, false)) return
                        if (pm.isInteractive) return
                        if (isProximityNear) return

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
                            BaseHook.toggleFlashlight(context)
                        }
                    }
                }

                val proxListener = if (prox != null) {
                    object : android.hardware.SensorEventListener {
                        override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
                        override fun onSensorChanged(event: android.hardware.SensorEvent) {
                            val v = event.values.firstOrNull() ?: return
                            isProximityNear = v < prox.maximumRange
                        }
                    }
                } else null

                sensorManager.registerListener(
                    accelListener,
                    accel,
                    android.hardware.SensorManager.SENSOR_DELAY_NORMAL
                )
                if (prox != null && proxListener != null) {
                    sensorManager.registerListener(
                        proxListener,
                        prox,
                        android.hardware.SensorManager.SENSOR_DELAY_NORMAL
                    )
                }

                shakeSensorManager = sensorManager
                shakeAccelListener = accelListener
                shakeProxListener = proxListener
                shakeListenerRegistered = true
                logStatic("shake torch sensors registered")
            } catch (t: Throwable) {
                logStatic("failed to register shake sensors: $t")
            }
        }

        private fun unregisterShakeTorchSensors() {
            val sm = shakeSensorManager
            val accelL = shakeAccelListener
            val proxL = shakeProxListener

            if (sm != null) {
                try {
                    if (accelL != null) sm.unregisterListener(accelL)
                } catch (_: Throwable) {}
                try {
                    if (proxL != null) sm.unregisterListener(proxL)
                } catch (_: Throwable) {}
            }

            shakeSensorManager = null
            shakeAccelListener = null
            shakeProxListener = null
            shakeListenerRegistered = false
            isProximityNear = false
            logStatic("shake torch sensors unregistered")
        }

        private fun ensureScreenReceiver(context: Context) {
            if (shakeScreenReceiverRegistered) return
            try {
                val filter = android.content.IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                }
                val receiver = object : android.content.BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: Intent?) {
                        when (intent?.action) {
                            Intent.ACTION_SCREEN_OFF -> refreshFromPrefs()
                            Intent.ACTION_SCREEN_ON -> refreshFromPrefs()
                        }
                    }
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    context.registerReceiver(receiver, filter)
                }
                shakeScreenReceiver = receiver
                shakeScreenReceiverRegistered = true
            } catch (t: Throwable) {
                logStatic("failed to register screen receiver: $t")
            }
        }

        private fun removeScreenReceiver(context: Context) {
            if (!shakeScreenReceiverRegistered) return
            try {
                val r = shakeScreenReceiver
                if (r != null) context.unregisterReceiver(r)
            } catch (_: Throwable) {
            } finally {
                shakeScreenReceiver = null
                shakeScreenReceiverRegistered = false
            }
        }

        fun refreshFromPrefs() {
            val ctx = shakeContext ?: return
            val enabled = BaseHook.getPreferenceBoolean(PREF_SHAKE_TORCH, false)
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val interactive = pm?.isInteractive ?: true

            if (!enabled) {
                // Fully shut down.
                removeScreenReceiver(ctx)
                if (shakeListenerRegistered) {
                    unregisterShakeTorchSensors()
                }
                return
            }

            // Enabled: keep a screen receiver so we can stop sensors when screen turns on.
            ensureScreenReceiver(ctx)

            if (interactive) {
                // Screen on: do not keep sensors registered.
                if (shakeListenerRegistered) unregisterShakeTorchSensors()
            } else {
                // Screen off: start sensors.
                registerShakeTorchSensors(ctx)
            }
        }
    }
}
