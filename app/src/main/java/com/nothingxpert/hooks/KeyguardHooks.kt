package com.nothingxpert.hooks

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.PowerManager
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.view.MotionEvent
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class KeyguardHooks : BaseHook() {
    override val tag = "Keyguard"
    
    override fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != SYSTEMUI_PKG) return
        
        installDoubleTapSleep(lpparam)
        installSingleTapSleep(lpparam)
        installShufflePinHook(lpparam)
        installDozeBlocker(lpparam)
        installTouchBlocker(lpparam)
        installTapToWakeRemap(lpparam)
        installNSegmentsClockCenterFix(lpparam)
    }
    
    private fun installDoubleTapSleep(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!ENABLE_DOUBLE_TAP) return
        
        safeHook("KeyguardTouchHandlingInteractor.onDoubleClick") {
            XposedHelpers.findAndHookMethod(
                KEYGUARD_TOUCH_CLASS,
                lpparam.classLoader,
                "onDoubleClick",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val instance = param.thisObject ?: return
                        if (goToSleepFromInteractor(instance)) {
                            param.result = null
                        }
                    }
                }
            )
        }
        
        safeHook("KeyguardTouchHandlingViewModel.onDoubleClick") {
            XposedHelpers.findAndHookMethod(
                KEYGUARD_TOUCH_VIEWMODEL_CLASS,
                lpparam.classLoader,
                "onDoubleClick",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val viewModel = param.thisObject ?: return
                        try {
                            val interactor = XposedHelpers.getObjectField(viewModel, "interactor") ?: return
                            if (goToSleepFromInteractor(interactor)) {
                                param.result = null
                            }
                        } catch (t: Throwable) {
                            log("viewmodel hook failed: $t")
                        }
                    }
                }
            )
        }
        
        safeHook("KeyguardTouchViewBinder onDoubleTapDetected") {
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
        }
        
        safeHook("TouchHandlingViewInteractionHandler.onDoubleTap") {
            XposedHelpers.findAndHookMethod(
                TOUCH_INTERACTION_GESTURE_CLASS,
                lpparam.classLoader,
                "onDoubleTap",
                MotionEvent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
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
        }
        
        safeHook("NTTapHandle.doubleTapEvent") {
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
                            val keyguard = XposedHelpers.getObjectField(handler, "mKeyguardStateController") ?: return
                            val isShowing = (XposedHelpers.callMethod(keyguard, "isShowing") as? Boolean) ?: false
                            if (!isShowing) return
                            
                            val powerManager = XposedHelpers.getObjectField(handler, "mPowerManager") as? PowerManager
                            if (powerManager?.isInteractive != true) return
                            
                            val uptime = SystemClock.uptimeMillis()
                            if (tryGoToSleep(powerManager, uptime)) {
                                blockTouchesUntil = SystemClock.uptimeMillis() + TOUCH_BLOCK_MS
                                param.result = null
                            }
                        } catch (t: Throwable) {
                            log("NTTapHandle hook failed: $t")
                        }
                    }
                }
            )
        }
    }
    
    private fun installSingleTapSleep(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeHook("KeyguardTouchHandlingInteractor.onClick") {
            XposedHelpers.findAndHookMethod(
                KEYGUARD_TOUCH_CLASS,
                lpparam.classLoader,
                "onClick",
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isSingleTapEnabled()) return
                        val instance = param.thisObject ?: return
                        if (goToSleepFromInteractor(instance)) {
                            blockTouchesUntil = SystemClock.uptimeMillis() + TOUCH_BLOCK_MS
                            param.result = null
                        }
                    }
                }
            )
        }
        
        safeHook("KeyguardTouchViewBinder.onSingleTapDetected") {
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
                        setTapPosition(x, y)
                        if (tryGoToSleep(pm, uptime)) {
                            blockTouchesUntil = SystemClock.uptimeMillis() + TOUCH_BLOCK_MS
                            param.result = null
                        }
                    }
                }
            )
        }
        
        safeHook("TouchHandlingViewInteractionHandler.dispatchSingleTap") {
            XposedHelpers.findAndHookMethod(
                TOUCH_INTERACTION_HANDLER_CLASS,
                lpparam.classLoader,
                "dispatchSingleTap",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isSingleTapEnabled()) return
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
        }
        
        if (ENABLE_DOUBLE_TAP || isSingleTapEnabled()) {
            safeHook("TouchHandlingView.setDoublePressHandlingEnabled") {
                XposedHelpers.findAndHookMethod(
                    TOUCH_HANDLING_VIEW_CLASS,
                    lpparam.classLoader,
                    "setDoublePressHandlingEnabled",
                    Boolean::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val ctx = (param.thisObject as? android.view.View)?.context
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
            }
        }
    }
    
    private fun installShufflePinHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeHook("KeyguardPinBasedInputView.onFinishInflate") {
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
        }
    }
    
    private fun installDozeBlocker(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeHook("DozeTriggers.onSensor") {
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
                            log("Blocking doze sensor $sensorType during blackout")
                            param.result = null
                        }
                    }
                }
            )
        }
    }
    
    private fun installTouchBlocker(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeHook("TouchHandlingViewInteractionHandler.onTouchEvent") {
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
        }
        
        safeHook("TouchHandlingViewInteractionHandler.scheduleLongPress") {
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
        }
    }
    
    private fun installTapToWakeRemap(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeHook("DozeServiceHostEx.fireSingleTap") {
            XposedHelpers.findAndHookMethod(
                "com.nothing.systemui.statusbar.phone.DozeServiceHostEx",
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
                                param.result = null
                                return
                            }
                            lastWakeTapTs = 0L
                            try {
                                XposedHelpers.callMethod(param.thisObject, "fireDoubleTap")
                            } catch (t: Throwable) {
                                log("fireDoubleTap via host failed: $t")
                            }
                        } else {
                            lastWakeTapTs = now
                            captureLastTapPos()
                        }
                        param.result = null
                    }
                }
            )
        }
        
        safeHook("KeyguardViewMediatorEx.handleKeyGestureSingleTap") {
            XposedHelpers.findAndHookMethod(
                "com.nothing.systemui.keyguard.KeyguardViewMediatorEx",
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
                                param.result = null
                                return
                            }
                            lastWakeTapTs = 0L
                            try {
                                XposedHelpers.callMethod(param.thisObject, "handleKeyGestureDoubleTap")
                            } catch (t: Throwable) {
                                log("handleKeyGestureDoubleTap failed: $t")
                            }
                        } else {
                            lastWakeTapTs = now
                            captureLastTapPos()
                        }
                        param.result = null
                    }
                }
            )
        }
        
        safeHook("KeyguardViewMediatorEx.handleKeyGestureDoubleTap") {
            XposedHelpers.findAndHookMethod(
                "com.nothing.systemui.keyguard.KeyguardViewMediatorEx",
                lpparam.classLoader,
                "handleKeyGestureDoubleTap",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isDoubleTapWakeEnabled()) return
                        lastWakeTapTs = 0L
                    }
                }
            )
        }
    }

    private fun installNSegmentsClockCenterFix(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeHook("NSegmentsClockView.drawClock") {
            XposedHelpers.findAndHookMethod(
                NSEGMENTS_CLOCK_VIEW_CLASS,
                lpparam.classLoader,
                "drawClock",
                Canvas::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isNSegmentsClockCenterFixEnabled()) return
                        val canvas = param.args.getOrNull(0) as? Canvas ?: return
                        if (drawNSegmentsClockCentered(param.thisObject ?: return, canvas)) {
                            param.result = null
                        }
                    }
                }
            )
        }
    }
    
    private fun goToSleepFromInteractor(instance: Any): Boolean {
        return try {
            val powerManager = XposedHelpers.getObjectField(instance, "powerManager") as? PowerManager
            val systemClock = XposedHelpers.getObjectField(instance, "systemClock")
            val uptime = XposedHelpers.callMethod(systemClock, "uptimeMillis") as? Long
            if (powerManager != null && uptime != null) {
                if (tryGoToSleep(powerManager, uptime)) return true
            }
            
            val context = XposedHelpers.getObjectField(instance, "context")
            val pm = XposedHelpers.callMethod(context, "getSystemService", "power") as? PowerManager
            if (pm != null && uptime != null) {
                if (tryGoToSleep(pm, uptime)) return true
            }
            false
        } catch (t: Throwable) {
            log("goToSleepFromInteractor failed: $t")
            false
        }
    }
    
    private fun tryGoToSleep(powerManager: PowerManager, uptime: Long): Boolean {
        try {
            val app = currentApplication()
            val dpm = app?.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            dpm?.lockNow()
            return true
        } catch (_: Throwable) {}
        
        return try {
            XposedHelpers.callMethod(powerManager, "goToSleep", uptime)
            true
        } catch (_: Throwable) {
            try {
                XposedHelpers.callMethod(powerManager, "goToSleep", uptime, 0, 0)
                true
            } catch (_: Throwable) {
                try {
                    XposedHelpers.callMethod(powerManager, "goToSleep", uptime, 2, 0)
                    true
                } catch (t: Throwable) {
                    log("goToSleep invocation failed: $t")
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
            }
        } catch (t: Throwable) {
            log("setTapPosition failed: $t")
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
                } catch (_: Throwable) {}
            }
            log("shuffled PIN layout")
        } catch (t: Throwable) {
            log("shuffle PIN failed: $t")
        }
    }
    
    private fun captureLastTapPos() {
        try {
            val point = readTapPos() ?: return
            lastWakeTapX = point.x
            lastWakeTapY = point.y
        } catch (_: Throwable) {}
    }
    
    private fun isSameTapArea(): Boolean {
        if (lastWakeTapX < 0 || lastWakeTapY < 0) return true
        val point = readTapPos() ?: return false
        val dx = point.x - lastWakeTapX
        val dy = point.y - lastWakeTapY
        val slop = getDoubleTapWakeSlopPx()
        return (dx * dx + dy * dy) <= (slop * slop)
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
            try {
                XposedHelpers.callMethod(central, "getTapPos") as? android.graphics.Point
            } catch (_: Throwable) {
                val utilClass = XposedHelpers.findClass("com.nothing.systemui.statusbar.phone.TapPositionUtil", loader)
                val ctx = getSystemContext() ?: return null
                XposedHelpers.callStaticMethod(utilClass, "getTapPos", ctx) as? android.graphics.Point
            }
        } catch (_: Throwable) { null }
    }
    
    private fun getDoubleTapWakeSlopPx(): Int {
        val ctx = getSystemContext() ?: return DOUBLE_TAP_WAKE_SLOP_PX
        return try {
            val base = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
            (base * 3).coerceAtLeast(DOUBLE_TAP_WAKE_SLOP_PX)
        } catch (_: Throwable) { DOUBLE_TAP_WAKE_SLOP_PX }
    }

    private fun drawNSegmentsClockCentered(clockView: Any, canvas: Canvas): Boolean {
        return try {
            val view = clockView as? android.view.View ?: return false
            val timeStr = XposedHelpers.callMethod(clockView, "getTimeStr") as? String ?: return false
            if (timeStr.isEmpty() || !TextUtils.isDigitsOnly(timeStr)) return false

            val isAlignmentCenter = XposedHelpers.callMethod(clockView, "getAlignmentCenter") as? Boolean ?: true
            if (!isAlignmentCenter) return false

            val paint = XposedHelpers.getObjectField(clockView, "paint") as? Paint ?: return false
            val scaleRatio = XposedHelpers.callMethod(clockView, "getScaleRatio") as? Float ?: return false
            val fontSize = XposedHelpers.getFloatField(clockView, "fontSize")
            val isDoze = XposedHelpers.callMethod(clockView, "getIsDoze") as? Boolean ?: false
            val isScreenOff = XposedHelpers.callMethod(clockView, "getIsScreenOff") as? Boolean ?: false
            val isRegionDark = XposedHelpers.callMethod(clockView, "getIsRegionDark") as? Boolean
            val isDarkTextUnsafe = isDoze || isScreenOff || isRegionDark == true
            val lsFontWeight = XposedHelpers.getIntField(clockView, "lsFontWeight")
            val isWakingUp = XposedHelpers.getBooleanField(clockView, "isWakingUp")
            val fontWeightAry = XposedHelpers.getObjectField(clockView, "fontWeightAry") as? Array<*> ?: return false

            val curDozeState = XposedHelpers.getBooleanField(clockView, "curDozeState")
            val nextDozeState = isDoze || isScreenOff
            if (curDozeState != nextDozeState) {
                XposedHelpers.setBooleanField(clockView, "curDozeState", nextDozeState)
            }

            paint.textSize = fontSize * scaleRatio
            paint.color = if (isDarkTextUnsafe) -1 else -0x1000000
            paint.typeface = getNSegmentsTypeface(clockView, lsFontWeight) ?: return false

            val advances = FloatArray(timeStr.length)
            var totalAdvance = 0f
            var inkLeft = Float.POSITIVE_INFINITY
            var inkRight = Float.NEGATIVE_INFINITY
            val digitBounds = Rect()

            for (i in timeStr.indices) {
                val weight = (fontWeightAry.getOrNull(i) as? Number)?.toInt() ?: lsFontWeight
                paint.typeface = getNSegmentsTypeface(clockView, weight) ?: return false

                val digit = timeStr[i].toString()
                val advance = paint.measureText(digit)
                advances[i] = advance

                digitBounds.setEmpty()
                paint.getTextBounds(digit, 0, digit.length, digitBounds)
                inkLeft = minOf(inkLeft, totalAdvance + digitBounds.left)
                inkRight = maxOf(inkRight, totalAdvance + digitBounds.right)
                totalAdvance += advance
            }

            if (totalAdvance <= 0f) return false

            paint.typeface = getNSegmentsTypeface(clockView, lsFontWeight) ?: return false
            val fontMetrics = paint.fontMetrics
            val y = (view.height / 2.0f) - ((fontMetrics.top + fontMetrics.bottom) / 2.0f)

            val inkCenterCorrection = if (inkLeft.isFinite() && inkRight.isFinite()) {
                ((inkLeft + inkRight) - totalAdvance) / 2.0f
            } else {
                0f
            }

            var x = if (isWakingUp) {
                (view.width / 2.0f) + (totalAdvance / 2.0f)
            } else {
                (view.width - totalAdvance) / 2.0f
            } - inkCenterCorrection

            val indices = if (isWakingUp) timeStr.indices.reversed() else timeStr.indices
            for (index in indices) {
                if (isWakingUp) {
                    x -= advances[index]
                }

                val weight = (fontWeightAry.getOrNull(index) as? Number)?.toInt() ?: lsFontWeight
                paint.typeface = getNSegmentsTypeface(clockView, weight) ?: return false
                val digit = timeStr[index].toString()
                canvas.drawText(digit, x, y, paint)
                runCatching {
                    XposedHelpers.callMethod(clockView, "setupRect", index, x.toInt(), y.toInt(), digit)
                }

                if (!isWakingUp) {
                    x += advances[index]
                }
            }
            true
        } catch (t: Throwable) {
            log("NSegments clock center fix failed: $t")
            false
        }
    }

    private fun getNSegmentsTypeface(clockView: Any, weight: Int): Typeface? {
        return try {
            XposedHelpers.callMethod(clockView, "getTypeface", weight) as? Typeface
        } catch (_: Throwable) {
            null
        }
    }
    
    private fun isSingleTapEnabled() = getPreferenceBoolean(PREF_SINGLE_TAP, false)
    private fun isShufflePinEnabled() = getPreferenceBoolean(PREF_SHUFFLE_PIN, false)
    private fun isDoubleTapWakeEnabled() = getPreferenceBoolean(PREF_DOUBLE_TAP_WAKE, false)
    private fun isNSegmentsClockCenterFixEnabled() = getPreferenceBoolean(PREF_NSEGMENTS_CLOCK_CENTER_FIX, false)
    
    companion object {
        // Static versions for cross-module access
        fun setTapPositionStatic(x: Int, y: Int) {
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
                }
            } catch (_: Throwable) {}
        }
        
        fun tryGoToSleepStatic(powerManager: PowerManager, uptime: Long): Boolean {
            try {
                val app = currentApplication()
                val dpm = app?.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                dpm?.lockNow()
                return true
            } catch (_: Throwable) {}
            
            return try {
                XposedHelpers.callMethod(powerManager, "goToSleep", uptime)
                true
            } catch (_: Throwable) {
                try {
                    XposedHelpers.callMethod(powerManager, "goToSleep", uptime, 0, 0)
                    true
                } catch (_: Throwable) {
                    try {
                        XposedHelpers.callMethod(powerManager, "goToSleep", uptime, 2, 0)
                        true
                    } catch (_: Throwable) { false }
                }
            }
        }
        
        private const val KEYGUARD_TOUCH_CLASS = "com.android.systemui.keyguard.domain.interactor.KeyguardTouchHandlingInteractor"
        private const val KEYGUARD_TOUCH_VIEWMODEL_CLASS = "com.android.systemui.keyguard.ui.viewmodel.KeyguardTouchHandlingViewModel"
        private const val TOUCH_HANDLING_VIEW_CLASS = "com.android.systemui.common.ui.view.TouchHandlingView"
        private const val KEYGUARD_TOUCH_LISTENER_CLASS = "com.android.systemui.keyguard.ui.binder.KeyguardTouchViewBinder\$bind\$1"
        private const val TOUCH_INTERACTION_GESTURE_CLASS = "com.android.systemui.common.ui.view.TouchHandlingViewInteractionHandler\$gestureDetector\$1"
        private const val TOUCH_INTERACTION_HANDLER_CLASS = "com.android.systemui.common.ui.view.TouchHandlingViewInteractionHandler"
        private const val DOZE_TRIGGERS_CLASS = "com.android.systemui.doze.DozeTriggers"
        private const val NT_TAP_HANDLE_CLASS = "com.nothing.systemui.statusbar.phone.NTTapHandle"
        private const val NSEGMENTS_CLOCK_VIEW_CLASS = "com.nothing.systemui.shared.clocks.view.NSegmentsClockView"
        
        private const val PREF_SINGLE_TAP = "pref_single_tap_sleep"
        private const val PREF_SHUFFLE_PIN = "pref_shuffle_pin"
        private const val PREF_DOUBLE_TAP_WAKE = "pref_double_tap_wake"
        private const val PREF_NSEGMENTS_CLOCK_CENTER_FIX = "pref_nsegments_clock_center_fix"
        
        private const val TOUCH_BLOCK_MS = 0L
        private const val DOUBLE_TAP_WAKE_WINDOW_MS = 350L
        private const val DOUBLE_TAP_WAKE_ARM_DELAY_MS = 900L
        private const val DOUBLE_TAP_WAKE_SLOP_PX = 120
        
        const val ENABLE_DOUBLE_TAP = false
        
        private val DOZE_BLOCKED_SENSORS = setOf(3, 4, 7, 8, 10, 11, 14)
        
        @Volatile var blockTouchesUntil: Long = 0L
        @Volatile var lastWakeTapTs: Long = 0L
        @Volatile var lastScreenOffTs: Long = 0L
        @Volatile var lastWakeTapX: Int = -1
        @Volatile var lastWakeTapY: Int = -1
        @Volatile var systemUiClassLoader: ClassLoader? = null
    }
}
