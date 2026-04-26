package com.nothingxpert.hooks

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
                            initializeLockscreenFlashlightIndication(ctx.applicationContext, lpparam.classLoader)
                        }
                    }
                }
            )
        }

        installLockscreenFlashlightIndicationHook(lpparam)
        installQuickSettingsExpansionHook(lpparam)
        
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

    private fun installLockscreenFlashlightIndicationHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeHook("KeyguardIndicationController.setIndicationArea") {
            XposedHelpers.findAndHookMethod(
                "com.android.systemui.statusbar.KeyguardIndicationController",
                lpparam.classLoader,
                "setIndicationArea",
                ViewGroup::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        lockscreenIndicationController = param.thisObject
                        refreshLockscreenFlashlightIndication()
                    }
                }
            )
        }

        safeHook("KeyguardIndicationController.setVisible") {
            XposedHelpers.findAndHookMethod(
                "com.android.systemui.statusbar.KeyguardIndicationController",
                lpparam.classLoader,
                "setVisible",
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        lockscreenIndicationController = param.thisObject
                        refreshLockscreenFlashlightIndication()
                    }
                }
            )
        }

        safeHook("KeyguardIndicationController.updateDeviceEntryIndication") {
            XposedHelpers.findAndHookMethod(
                "com.android.systemui.statusbar.KeyguardIndicationController",
                lpparam.classLoader,
                "updateDeviceEntryIndication",
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        lockscreenIndicationController = param.thisObject
                        refreshLockscreenFlashlightIndication()
                    }
                }
            )
        }
    }

    private fun installQuickSettingsExpansionHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeHook("ShadeRepositoryImpl.setQsExpansion") {
            XposedHelpers.findAndHookMethod(
                "com.android.systemui.shade.data.repository.ShadeRepositoryImpl",
                lpparam.classLoader,
                "setQsExpansion",
                Float::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val fraction = (param.args.firstOrNull() as? Float) ?: return
                        val expanded = fraction > 0.02f
                        if (quickSettingsExpanded != expanded) {
                            quickSettingsExpanded = expanded
                            refreshLockscreenFlashlightIndication()
                        }
                    }
                }
            )
        }
    }
    
    companion object {
        const val PREF_SHAKE_TORCH = "pref_shake_torch"
        const val PREF_LOCKSCREEN_FLASHLIGHT_TAP = "pref_lockscreen_flashlight_tap"
        private const val FLASHLIGHT_OVERLAY_TAG = "nothingxpert_lockscreen_flashlight_overlay"
        private const val FLASHLIGHT_OVERLAY_X_FRACTION = 0.04f
        private const val FLASHLIGHT_OVERLAY_Y_FRACTION = 0.305f
        private const val FLASHLIGHT_NOTIFICATION_SHIFT_DP = 48f
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

        @Volatile private var lockscreenContext: Context? = null
        @Volatile private var lockscreenClassLoader: ClassLoader? = null
        @Volatile private var lockscreenIndicationController: Any? = null
        @Volatile private var lockscreenRootView: ViewGroup? = null
        @Volatile private var lockscreenOverlayHost: ViewGroup? = null
        @Volatile private var flashlightOverlay: LinearLayout? = null
        @Volatile private var notificationStackView: View? = null
        @Volatile private var notificationStackBaseTranslationY = 0f
        @Volatile private var notificationStackShiftApplied = false
        @Volatile private var quickSettingsExpanded = false
        @Volatile private var torchCallbackRegistered = false
        @Volatile private var torchCameraId: String? = null
        @Volatile private var torchCameraManager: CameraManager? = null
        @Volatile private var torchCallback: CameraManager.TorchCallback? = null
        @Volatile private var mainHandler: Handler? = null

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

                // Use SENSOR_DELAY_NORMAL (~5 Hz) with a 500 ms report latency so the
                // sensor hub can batch samples and avoid waking the AP on every event.
                sensorManager.registerListener(
                    accelListener,
                    accel,
                    android.hardware.SensorManager.SENSOR_DELAY_NORMAL,
                    500_000 // maxReportLatencyUs — let hardware batch for up to 500 ms
                )
                if (prox != null && proxListener != null) {
                    sensorManager.registerListener(
                        proxListener,
                        prox,
                        android.hardware.SensorManager.SENSOR_DELAY_NORMAL,
                        500_000
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

        private fun initializeLockscreenFlashlightIndication(context: Context, classLoader: ClassLoader) {
            lockscreenContext = context.applicationContext
            lockscreenClassLoader = classLoader
            registerTorchCallback(context.applicationContext)
            refreshLockscreenFlashlightIndication()
        }

        fun refreshLockscreenFlashlightIndication() {
            val work = Runnable {
                val controller = lockscreenIndicationController
                val enabled = BaseHook.getPreferenceBoolean(PREF_LOCKSCREEN_FLASHLIGHT_TAP, false)
                val shouldShow = enabled &&
                    BaseHook.isFlashlightOn() &&
                    !quickSettingsExpanded &&
                    isKeyguardIndicationVisible(controller) &&
                    !isKeyguardIndicationDozing(controller)

                if (shouldShow) {
                    hideLockscreenFlashlightIndication(controller)
                    showLockscreenFlashlightOverlay()
                } else {
                    hideLockscreenFlashlightIndication(controller)
                    hideLockscreenFlashlightOverlay()
                }
            }
            val handler = getMainHandler()
            if (handler != null) {
                handler.post(work)
            } else {
                work.run()
            }
        }

        private fun registerTorchCallback(context: Context) {
            if (torchCallbackRegistered) return
            try {
                val handler = getMainHandler(context) ?: return
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
                val cameraId = findFlashCameraId(cameraManager) ?: return
                val callback = object : CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                        if (cameraId != torchCameraId) return
                        BaseHook.setFlashlightState(enabled)
                        refreshLockscreenFlashlightIndication()
                    }

                    override fun onTorchModeUnavailable(cameraId: String) {
                        if (cameraId != torchCameraId) return
                        BaseHook.setFlashlightState(false)
                        refreshLockscreenFlashlightIndication()
                    }
                }

                torchCameraId = cameraId
                torchCameraManager = cameraManager
                torchCallback = callback
                cameraManager.registerTorchCallback(callback, handler)
                torchCallbackRegistered = true
                logStatic("torch callback registered for camera $cameraId")
            } catch (t: Throwable) {
                logStatic("failed to register torch callback: $t")
            }
        }

        private fun getMainHandler(context: Context? = lockscreenContext): Handler? {
            mainHandler?.let { return it }
            return try {
                val looper = context?.mainLooper ?: Looper.getMainLooper() ?: Looper.myLooper() ?: return null
                Handler(looper).also { mainHandler = it }
            } catch (_: Throwable) {
                null
            }
        }

        private fun findFlashCameraId(cameraManager: CameraManager): String? {
            var fallback: String? = null
            return try {
                for (id in cameraManager.cameraIdList) {
                    val characteristics = cameraManager.getCameraCharacteristics(id)
                    val hasFlash = characteristics.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    if (!hasFlash) continue
                    val facing = characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                    if (facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) {
                        return id
                    }
                    if (fallback == null) fallback = id
                }
                fallback
            } catch (_: Throwable) {
                null
            }
        }

        private fun isKeyguardIndicationVisible(controller: Any?): Boolean {
            return try {
                controller != null && XposedHelpers.getBooleanField(controller, "mVisible")
            } catch (_: Throwable) {
                controller != null
            }
        }

        private fun isKeyguardIndicationDozing(controller: Any?): Boolean {
            return try {
                controller != null && XposedHelpers.getBooleanField(controller, "mDozing")
            } catch (_: Throwable) {
                false
            }
        }

        private fun showLockscreenFlashlightOverlay() {
            val root = ensureLockscreenRootView() ?: return
            val host = ensureOverlayHost(root)
            try {
                val overlay = flashlightOverlay?.takeIf { it.parent === host } ?: createFlashlightOverlay(root).also {
                    flashlightOverlay = it
                    (it.parent as? ViewGroup)?.removeView(it)
                    host.addView(
                        it,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    )
                }
                shiftLockscreenNotifications(root, true)
                positionFlashlightOverlay(root, overlay)
                overlay.visibility = View.VISIBLE
                overlay.elevation = dp(root.context, 128f)
                overlay.translationZ = dp(root.context, 128f)
                overlay.bringToFront()
                overlay.alpha = 1f
            } catch (t: Throwable) {
                logStatic("failed to show flashlight overlay: $t")
            }
        }

        private fun hideLockscreenFlashlightIndication(controller: Any?) {
            if (controller == null) return
            try {
                val rotateController = XposedHelpers.getObjectField(controller, "mRotateTextViewController") ?: return
                XposedHelpers.callMethod(rotateController, "hideIndication", 15)
            } catch (t: Throwable) {
                logStatic("failed to hide old flashlight indication: $t")
            }
        }

        private fun hideLockscreenFlashlightOverlay() {
            try {
                flashlightOverlay?.visibility = View.GONE
                shiftLockscreenNotifications(lockscreenRootView, false)
            } catch (_: Throwable) {}
        }

        private fun ensureLockscreenRootView(): ViewGroup? {
            lockscreenRootView?.let { root ->
                if (root.parent != null || root.isAttachedToWindow) return root
            }
            val area = try {
                XposedHelpers.getObjectField(lockscreenIndicationController, "mIndicationArea") as? View
            } catch (_: Throwable) {
                null
            } ?: return null
            var parent = area.parent
            while (parent is ViewGroup) {
                if (parent.javaClass.name == "com.android.systemui.keyguard.ui.view.KeyguardRootView") {
                    lockscreenRootView = parent
                    return parent
                }
                parent = parent.parent
            }
            return null
        }

        private fun ensureOverlayHost(root: ViewGroup): ViewGroup {
            lockscreenOverlayHost?.let { host ->
                if (host.isAttachedToWindow) return host
            }
            return (root.rootView as? ViewGroup)?.also { lockscreenOverlayHost = it } ?: root
        }

        private fun createFlashlightOverlay(root: ViewGroup): LinearLayout {
            val context = root.context
            val density = context.resources.displayMetrics.density
            fun dp(value: Float): Int = (value * density + 0.5f).toInt()

            return LinearLayout(context).apply {
                tag = FLASHLIGHT_OVERLAY_TAG
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                contentDescription = "Flashlight on, tap to turn off"
                setPadding(dp(8f), dp(7f), dp(12f), dp(7f))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(18f).toFloat()
                    setColor(0x26000000)
                }
                setOnClickListener {
                    BaseHook.turnOffFlashlight(lockscreenContext)
                    hideLockscreenFlashlightOverlay()
                }

                addView(ImageView(context).apply {
                    val iconId = resources.getIdentifier("ic_flashlight_on", "drawable", "com.android.systemui")
                        .takeIf { it != 0 }
                        ?: resources.getIdentifier("qs_flashlight_icon_on", "drawable", "com.android.systemui")
                    if (iconId != 0) {
                        setImageDrawable(resources.getDrawable(iconId, context.theme))
                    } else {
                        setImageResource(android.R.drawable.ic_menu_manage)
                    }
                    imageTintList = android.content.res.ColorStateList.valueOf(0xE6FFFFFF.toInt())
                    alpha = 0.95f
                }, LinearLayout.LayoutParams(dp(15f), dp(15f)).apply {
                    marginEnd = dp(5f)
                })

                addView(TextView(context).apply {
                    text = "Flashlight on - tap to turn off"
                    setTextColor(0xE6FFFFFF.toInt())
                    textSize = 14f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    includeFontPadding = false
                    maxLines = 1
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
            }
        }

        private fun positionFlashlightOverlay(root: ViewGroup, overlay: View) {
            val density = root.context.resources.displayMetrics.density
            fun dp(value: Float): Float = value * density
            val rootWidth = root.width.takeIf { it > 0 } ?: root.resources.displayMetrics.widthPixels
            val rootHeight = root.height.takeIf { it > 0 } ?: root.resources.displayMetrics.heightPixels
            val preferredY = rootHeight * FLASHLIGHT_OVERLAY_Y_FRACTION
            val maxYAboveStack = ensureNotificationStackView(root)?.let { stack ->
                val overlayHeight = overlay.height.takeIf { it > 0 }?.toFloat() ?: dp(38f)
                stack.y + stack.translationY - overlayHeight - dp(20f)
            }

            overlay.x = minOf(dp(72f), rootWidth * FLASHLIGHT_OVERLAY_X_FRACTION)
            overlay.y = if (maxYAboveStack != null && maxYAboveStack > 0f) {
                minOf(preferredY, maxYAboveStack).coerceAtLeast(rootHeight * 0.285f)
            } else {
                preferredY
            }
        }

        private fun shiftLockscreenNotifications(root: ViewGroup?, apply: Boolean) {
            val stack = ensureNotificationStackView(root)
            if (stack == null) {
                notificationStackShiftApplied = false
                return
            }

            if (apply) {
                if (!notificationStackShiftApplied || notificationStackView !== stack) {
                    notificationStackBaseTranslationY = stack.translationY
                    notificationStackShiftApplied = true
                    notificationStackView = stack
                }
                stack.translationY = notificationStackBaseTranslationY + dp(stack.context, FLASHLIGHT_NOTIFICATION_SHIFT_DP)
            } else if (notificationStackShiftApplied && notificationStackView === stack) {
                stack.translationY = notificationStackBaseTranslationY
                notificationStackShiftApplied = false
            }
        }

        private fun ensureNotificationStackView(root: ViewGroup?): View? {
            root ?: return null
            notificationStackView?.let { cached ->
                if (cached.isAttachedToWindow) return cached
            }

            val found = listOf("shared_notification_container", "notification_stack_scroller")
                .firstNotNullOfOrNull { name ->
                    val resId = try {
                        root.resources.getIdentifier(name, "id", "com.android.systemui")
                    } catch (_: Throwable) {
                        0
                    }
                    if (resId != 0) root.rootView.findViewById<View>(resId) else null
                } ?: findViewByClassName(root.rootView, "NotificationStackScrollLayout")

            if (found != null) {
                logStatic("flashlight prompt shifting notification view ${found.javaClass.name} by ${FLASHLIGHT_NOTIFICATION_SHIFT_DP}dp")
            }
            notificationStackView = found
            return found
        }

        private fun findViewByClassName(view: View, classNamePart: String): View? {
            if (view.javaClass.name.contains(classNamePart)) return view
            val group = view as? ViewGroup ?: return null
            for (i in 0 until group.childCount) {
                findViewByClassName(group.getChildAt(i), classNamePart)?.let { return it }
            }
            return null
        }

        private fun dp(context: Context, value: Float): Float {
            return value * context.resources.displayMetrics.density
        }
    }
}
