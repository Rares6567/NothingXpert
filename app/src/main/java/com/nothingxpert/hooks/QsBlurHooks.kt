package com.nothingxpert.hooks

import android.os.SystemClock
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class QsBlurHooks : BaseHook() {
    override val tag = "QsBlur"

    override fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != SYSTEMUI_PKG) return

        hookDepthController(lpparam)
        hookQsExpansion(lpparam)
        hookScrimView(lpparam)
        hookLightRevealScrim(lpparam)
        hookPanelAlpha(lpparam)
        hookKeyguardRootAlpha(lpparam)
    }

    private fun hookDepthController(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeHook("NotificationShadeDepthController constructors") {
            val cls = XposedHelpers.findClass(
                "com.android.systemui.statusbar.NotificationShadeDepthController",
                lpparam.classLoader
            )
            XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    depthController = param.thisObject
                    if (!isEnabled()) {
                        pushQsBlurProgress(0f)
                    }
                }
            })

            XposedHelpers.findAndHookMethod(
                cls,
                "setQsPanelExpansion",
                Float::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isEnabled()) return

                        val expansion = (param.args.firstOrNull() as? Float ?: 0f).coerceIn(0f, 1f)
                        depthController = param.thisObject
                        updatePredictedBlurState(param.thisObject, expansion, null)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val expansion = (param.args.firstOrNull() as? Float ?: 0f).coerceIn(0f, 1f)
                        depthController = param.thisObject
                        runCatching {
                            applyDirectBlur(
                                param.thisObject,
                                if (isEnabled()) expansion else 0f,
                                null
                            )
                        }.onFailure {
                            log("Failed to apply direct QS blur from controller: $it")
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                cls,
                "setRoot",
                View::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        depthController = param.thisObject
                        val expansion = if (isEnabled()) {
                            runCatching {
                                XposedHelpers.callMethod(param.thisObject, "getQsPanelExpansion") as? Float ?: 0f
                            }.getOrDefault(0f)
                        } else {
                            0f
                        }
                        runCatching {
                            applyDirectBlur(
                                param.thisObject,
                                expansion.coerceIn(0f, 1f),
                                null
                            )
                        }.onFailure {
                            log("Failed to apply direct QS blur after root attach: $it")
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                cls,
                "onPanelExpansionChanged",
                XposedHelpers.findClass(
                    "com.android.systemui.shade.ShadeExpansionChangeEvent",
                    lpparam.classLoader
                ),
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isEnabled()) return

                        depthController = param.thisObject
                        val event = param.args.firstOrNull() ?: return
                        val shadeExpansion = runCatching {
                            XposedHelpers.callMethod(event, "getFraction") as? Float ?: 0f
                        }.getOrDefault(0f).coerceIn(0f, 1f)
                        updatePredictedBlurState(param.thisObject, null, shadeExpansion)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        depthController = param.thisObject
                        runCatching {
                            applyDirectBlur(
                                param.thisObject,
                                null,
                                if (isEnabled()) {
                                    XposedHelpers.callMethod(param.thisObject, "getShadeExpansion") as? Float ?: 0f
                                } else {
                                    0f
                                }
                            )
                        }.onFailure {
                            log("Failed to apply direct shade blur: $it")
                        }
                    }
                }
            )
        }
    }

    private fun hookQsExpansion(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeHook("QS blur via QSImpl.setQsExpansion") {
            XposedHelpers.findAndHookMethod(
                "com.android.systemui.qs.QSImpl",
                lpparam.classLoader,
                "setQsExpansion",
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isEnabled()) {
                            pushQsBlurProgress(0f)
                            return
                        }

                        val expansion = (param.args.firstOrNull() as? Float ?: 0f)
                            .coerceIn(0f, 1f)
                        pushQsBlurProgress(expansion)
                    }
                }
            )
        }
    }

    private fun hookScrimView(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeHook("ScrimView QS blur presentation") {
            val scrimViewClass = XposedHelpers.findClass(
                "com.android.systemui.scrim.ScrimView",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                scrimViewClass,
                "setViewAlpha",
                Float::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isEnabled()) return

                        val scrimName = (param.thisObject as? View)?.resourceEntryName() ?: return
                        val presentationRatio = currentPresentationBlurRatio()
                        if (presentationRatio <= 0f) return
                        val targetAlpha = when (scrimName) {
                            "scrim_behind" -> targetBehindAlpha(presentationRatio)
                            "scrim_notifications" -> targetNotificationsAlpha(presentationRatio)
                            else -> return
                        }
                        param.args[0] = targetAlpha
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                scrimViewClass,
                "setTint",
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isEnabled() || currentPresentationBlurRatio() <= 0f) return

                        val scrimName = (param.thisObject as? View)?.resourceEntryName() ?: return
                        if (scrimName == "scrim_behind") {
                            param.args[0] = 0
                        }
                    }
                }
            )
        }
    }

    private fun hookLightRevealScrim(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeHook("LightRevealScrim QS blur presentation") {
            val cls = XposedHelpers.findClass(
                "com.android.systemui.statusbar.LightRevealScrim",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                cls,
                "setAlpha",
                Float::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isEnabled() || !shouldSuppressRevealOcclusion()) return
                        param.args[0] = 0f
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                cls,
                "setRevealGradientEndColorAlpha",
                Float::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isEnabled() || !shouldSuppressRevealOcclusion()) return
                        param.args[0] = 0f
                    }
                }
            )
        }
    }

    private fun hookPanelAlpha(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeHook("NotificationPanelViewController QS blur alpha") {
            XposedHelpers.findAndHookMethod(
                "com.android.systemui.shade.NotificationPanelViewController",
                lpparam.classLoader,
                "setAlpha",
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isEnabled() || !shouldKeepShadeContentVisible()) return
                        param.args[0] = 255
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                "com.android.systemui.shade.NotificationShadeWindowControllerImpl",
                lpparam.classLoader,
                "setAlpha",
                Float::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isEnabled() || !shouldKeepShadeContentVisible()) return
                        param.args[0] = 1f
                    }
                }
            )
        }
    }

    private fun hookKeyguardRootAlpha(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeHook("KeyguardRootView QS blur alpha") {
            XposedHelpers.findAndHookMethod(
                View::class.java,
                "setAlpha",
                Float::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isEnabled() || !shouldKeepShadeContentVisible()) return
                        if (param.thisObject?.javaClass?.name != "com.android.systemui.keyguard.ui.view.KeyguardRootView") {
                            return
                        }
                        val alpha = (param.args.firstOrNull() as? Float ?: 1f)
                        if (alpha <= 0f) {
                            param.args[0] = 1f
                        }
                    }
                }
            )
        }
    }

    private fun pushQsBlurProgress(expansion: Float) {
        val controller = depthController ?: return
        runCatching {
            val clampedExpansion = expansion.coerceIn(0f, 1f)
            XposedHelpers.callMethod(controller, "setQsPanelExpansion", clampedExpansion)
            applyDirectBlur(controller, clampedExpansion, null)
        }.onFailure {
            log("Failed to update QS blur progress: $it")
        }
    }

    private fun applyDirectBlur(
        controller: Any,
        forcedQsExpansion: Float?,
        forcedShadeExpansion: Float?
    ) {
        val root = runCatching {
            XposedHelpers.callMethod(controller, "getRoot")
        }.getOrNull() ?: return
        val viewRootImpl = runCatching {
            XposedHelpers.callMethod(root, "getViewRootImpl")
        }.getOrNull() ?: return

        val blurUtils = XposedHelpers.getObjectField(controller, "blurUtils")
        val windowController = XposedHelpers.getObjectField(
            controller,
            "notificationShadeWindowController"
        )
        val (qsExpansion, shadeExpansion, blurRatio) = computeBlurState(
            controller,
            root,
            forcedQsExpansion,
            forcedShadeExpansion
        ) ?: return
        val blurRadius = (
            XposedHelpers.callMethod(blurUtils, "blurRadiusOfRatio", blurRatio) as? Float ?: 0f
        ).toInt()
        currentBlurRatio = blurRatio
        if (blurRatio > 0f) {
            lastNonZeroBlurRatio = blurRatio
            lastNonZeroBlurUptime = SystemClock.uptimeMillis()
        }

        XposedHelpers.callMethod(blurUtils, "prepareBlur", viewRootImpl, blurRadius)
        XposedHelpers.callMethod(blurUtils, "applyBlur", viewRootImpl, blurRadius, false)
        XposedHelpers.callMethod(windowController, "setBackgroundBlurRadius", blurRadius)
        applyScrimPresentation(root as View, currentPresentationBlurRatio())
        if (lastLoggedBlurRadius != blurRadius) {
            lastLoggedBlurRadius = blurRadius
            log("Applied QS blur radius=$blurRadius qs=$qsExpansion shade=$shadeExpansion")
        }
    }

    private fun computeBlurState(
        controller: Any,
        root: Any,
        forcedQsExpansion: Float?,
        forcedShadeExpansion: Float?
    ): Triple<Float, Float, Float>? {
        val shadeInterpolationClass = runCatching {
            XposedHelpers.findClass(
                "com.android.systemui.animation.ShadeInterpolation",
                root.javaClass.classLoader
            )
        }.getOrNull() ?: return null
        val qsExpansion = forcedQsExpansion ?: runCatching {
            XposedHelpers.callMethod(controller, "getQsPanelExpansion") as? Float ?: 0f
        }.getOrDefault(0f)
        val shadeExpansion = forcedShadeExpansion ?: runCatching {
            XposedHelpers.callMethod(controller, "getShadeExpansion") as? Float ?: 0f
        }.getOrDefault(0f)
        val shadeRatio = runCatching {
            XposedHelpers.callStaticMethod(
                shadeInterpolationClass,
                "getNotificationScrimAlpha",
                shadeExpansion
            ) as? Float
        }.getOrNull()?.times(0.8f) ?: 0f
        val qsRatio = runCatching {
            XposedHelpers.callStaticMethod(
                shadeInterpolationClass,
                "getNotificationScrimAlpha",
                qsExpansion
            ) as? Float
        }.getOrNull()?.times(shadeExpansion) ?: 0f
        val computedRatio = maxOf(shadeRatio, qsRatio).coerceIn(0f, 1f)
        val rawExpansion = maxOf(qsExpansion, shadeExpansion).coerceIn(0f, 1f)
        val seededRatio = if (rawExpansion > 0f) {
            computedRatio.coerceAtLeast(MIN_TRANSITION_RATIO)
        } else {
            computedRatio
        }
        return Triple(qsExpansion, shadeExpansion, seededRatio)
    }

    private fun applyScrimPresentation(root: View, blurRatio: Float) {
        val resources = root.resources
        val behindId = resources.getIdentifier("scrim_behind", "id", SYSTEMUI_PKG)
        val notificationsId = resources.getIdentifier("scrim_notifications", "id", SYSTEMUI_PKG)
        val behindScrim = if (behindId != 0) root.findViewById<View>(behindId) else null
        val notificationsScrim = if (notificationsId != 0) root.findViewById<View>(notificationsId) else null

        val behindAlpha = targetBehindAlpha(blurRatio)
        val notificationsAlpha = targetNotificationsAlpha(blurRatio)

        behindScrim?.let {
            XposedHelpers.callMethod(it, "setViewAlpha", behindAlpha)
            XposedHelpers.callMethod(it, "setTint", 0)
        }
        notificationsScrim?.let {
            XposedHelpers.callMethod(it, "setViewAlpha", notificationsAlpha)
        }
    }

    private fun targetBehindAlpha(blurRatio: Float): Float {
        return if (blurRatio > 0f) {
            (0.15f + ((1f - blurRatio) * 0.25f)).coerceIn(0.15f, 1f)
        } else {
            1f
        }
    }

    private fun targetNotificationsAlpha(blurRatio: Float): Float {
        return if (blurRatio > 0f) {
            ((1f - blurRatio) * 0.3f).coerceIn(0f, 1f)
        } else {
            1f
        }
    }

    private fun View.resourceEntryName(): String? {
        val viewId = id
        if (viewId == View.NO_ID) return null
        return runCatching { resources.getResourceEntryName(viewId) }.getOrNull()
    }

    private fun currentPresentationBlurRatio(): Float {
        val liveRatio = depthController?.let { controller ->
            runCatching {
                val root = XposedHelpers.callMethod(controller, "getRoot")
                computeBlurState(controller, root, null, null)?.third ?: currentBlurRatio
            }.getOrDefault(currentBlurRatio)
        } ?: currentBlurRatio
        if (liveRatio > 0f) {
            return liveRatio
        }
        val liveGestureProgress = depthController?.let { controller ->
            runCatching {
                val root = XposedHelpers.callMethod(controller, "getRoot")
                val blurState = computeBlurState(controller, root, null, null)
                maxOf(blurState?.first ?: 0f, blurState?.second ?: 0f)
            }.getOrDefault(currentGestureProgress)
        } ?: currentGestureProgress
        if (liveGestureProgress > 0f) {
            return MIN_TRANSITION_RATIO
        }
        val elapsed = SystemClock.uptimeMillis() - lastNonZeroBlurUptime
        if (elapsed in 0..SCRIM_HYSTERESIS_MS) {
            val decay = 1f - (elapsed.toFloat() / SCRIM_HYSTERESIS_MS.toFloat())
            return (lastNonZeroBlurRatio * decay).coerceAtLeast(MIN_TRANSITION_RATIO)
        }
        return 0f
    }

    private fun updatePredictedBlurState(
        controller: Any,
        forcedQsExpansion: Float?,
        forcedShadeExpansion: Float?
    ) {
        runCatching {
            val root = XposedHelpers.callMethod(controller, "getRoot")
            val blurState = computeBlurState(controller, root, forcedQsExpansion, forcedShadeExpansion)
                ?: return
            currentGestureProgress = maxOf(blurState.first, blurState.second)
            currentBlurRatio = blurState.third
            if (currentGestureProgress > 0f) {
                lastGestureUptime = SystemClock.uptimeMillis()
            }
            if (blurState.third > 0f) {
                lastNonZeroBlurRatio = blurState.third
                lastNonZeroBlurUptime = SystemClock.uptimeMillis()
            }
        }
    }

    private fun shouldSuppressRevealOcclusion(): Boolean {
        if (currentPresentationBlurRatio() > 0f) {
            return true
        }
        val elapsed = SystemClock.uptimeMillis() - lastGestureUptime
        return currentGestureProgress > 0f || elapsed in 0..SCRIM_HYSTERESIS_MS
    }

    private fun shouldKeepShadeContentVisible(): Boolean {
        return currentPresentationBlurRatio() > 0f || currentGestureProgress > 0f
    }

    private fun isEnabled(): Boolean {
        return getPreferenceBoolean(PREF_QS_BLUR_ENABLED, false)
    }

    companion object {
        const val PREF_QS_BLUR_ENABLED = "pref_qs_blur_enabled"

        @Volatile
        private var depthController: Any? = null

        @Volatile
        private var lastLoggedBlurRadius: Int = Int.MIN_VALUE

        @Volatile
        private var currentBlurRatio: Float = 0f

        @Volatile
        private var lastNonZeroBlurRatio: Float = 0f

        @Volatile
        private var lastNonZeroBlurUptime: Long = 0L

        @Volatile
        private var currentGestureProgress: Float = 0f

        @Volatile
        private var lastGestureUptime: Long = 0L

        private const val SCRIM_HYSTERESIS_MS = 140L
        private const val MIN_TRANSITION_RATIO = 0.08f

        fun refreshFromPrefs() {
            val controller = depthController ?: return
            val enabled = BaseHook.getPreferenceBoolean(PREF_QS_BLUR_ENABLED, false)
            val target = if (enabled) {
                runCatching {
                    XposedHelpers.callMethod(controller, "getQsPanelExpansion") as? Float ?: 0f
                }.getOrDefault(0f)
            } else {
                0f
            }

            runCatching {
                val clamped = target.coerceIn(0f, 1f)
                XposedHelpers.callMethod(controller, "setQsPanelExpansion", clamped)
                QsBlurHooks().applyDirectBlur(controller, clamped, null)
            }
        }
    }
}
