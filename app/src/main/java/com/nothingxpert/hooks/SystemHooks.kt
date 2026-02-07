package com.nothingxpert.hooks

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.nothingxpert.util.RootShell

class SystemHooks : BaseHook() {
    override val tag = "System"
    
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    
    override fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "android") {
            startCpuReporter(lpparam)
        }
        
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
                            registerShakeTorch(ctx.applicationContext)
                        }
                    }
                }
            )
        }
        
        if (getPreferenceBoolean("pref_status_bar_double_tap_sleep", false)) {
            installStatusBarDoubleTapHook(lpparam)
        }
    }
    
    fun registerShakeTorch(context: Context) {
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
                    if (!getPreferenceBoolean(PREF_SHAKE_TORCH, false)) return
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
                        VolumeHooks.toggleFlashlight(context)
                    }
                }
            }
            sensorManager.registerListener(
                listener,
                accel,
                android.hardware.SensorManager.SENSOR_DELAY_NORMAL
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
            log("shake torch listener registered")
        } catch (t: Throwable) {
            log("failed to register shake listener: $t")
        }
    }
    
    private fun startCpuReporter(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val at = Class.forName("android.app.ActivityThread")
            val thread = XposedHelpers.callStaticMethod(at, "currentActivityThread")
            val sysContext = XposedHelpers.callMethod(thread, "getSystemContext") as? Context ?: return
            if (cpuReporterStarted) return
            cpuReporterStarted = true
            log("starting CPU reporter")
            val cpuHandler = Handler(Looper.getMainLooper())
            val runnable = object : Runnable {
                override fun run() {
                    try {
                        val usage = sampleCpuUsage()
                        if (usage != null) {
                            android.provider.Settings.Global.putString(sysContext.contentResolver, "nothingxpert_cpu_usage", usage.toString())
                        }
                        val temp = readCpuTemp()
                        if (temp != null) {
                            android.provider.Settings.Global.putString(sysContext.contentResolver, "nothingxpert_cpu_temp", temp.toString())
                        }
                    } catch (_: Throwable) { }
                    cpuHandler.postDelayed(this, 2000)
                }
            }
            cpuHandler.post(runnable)
        } catch (t: Throwable) {
            log("CPU reporter init failed: $t")
        }
    }
    
    private fun sampleCpuUsage(): Int? {
        val stat = readFile("/proc/stat") ?: return null
        val line = stat.lineSequence().firstOrNull { it.startsWith("cpu ") } ?: return null
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
    
    private fun readCpuTemp(): Int? {
        val cached = cachedCpuThermalZone
        if (cached >= 0) {
            val raw = readFile("/sys/class/thermal/thermal_zone$cached/temp")?.trim()
            val v = raw?.toIntOrNull()
            if (v != null) {
                val c = if (v > 1000 || v < -1000) v / 1000 else v
                if (c in 0..120) return c
            }
            cachedCpuThermalZone = -1
        }

        for (i in 0..120) {
            val type = readFile("/sys/class/thermal/thermal_zone$i/type")
                ?.trim()
                ?.lowercase()
                ?: continue
            if (type.contains("cpu") || type.contains("cpuss") || type.contains("soc")) {
                val raw = readFile("/sys/class/thermal/thermal_zone$i/temp")?.trim() ?: continue
                val v = raw.toIntOrNull() ?: continue
                val c = if (v > 1000 || v < -1000) v / 1000 else v
                if (c in 0..120) {
                    cachedCpuThermalZone = i
                    return c
                }
            }
        }
        return null
    }

    private fun readFile(path: String): String? {
        // Prefer root so we don't depend on the hooked process' (android/SystemUI) file access.
        RootShell.cat(path)?.let { return it }
        return try {
            java.io.File(path).takeIf { it.exists() && it.canRead() }?.readText()
        } catch (_: Throwable) {
            null
        }
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
        private const val PREF_SHAKE_TORCH = "pref_shake_torch"
        private const val SHAKE_THRESHOLD = 19.5f
        private const val SHAKE_COOLDOWN_MS = 1500L
        
        @Volatile private var shakeListenerRegistered = false
        @Volatile private var lastShakeTs = 0L
        @Volatile private var isProximityNear = false
        @Volatile private var cpuReporterStarted = false
        @Volatile private var lastCpuTotal = -1L
        @Volatile private var lastCpuIdle = -1L
        @Volatile private var cachedCpuThermalZone = -1
    }
}
