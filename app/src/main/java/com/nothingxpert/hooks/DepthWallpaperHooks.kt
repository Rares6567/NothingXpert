package com.nothingxpert.hooks

import android.app.WallpaperManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import com.nothingxpert.RemotePrefProvider
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.Executors

class DepthWallpaperHooks : BaseHook() {
    override val tag = "DepthWallpaper"

    override fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != SYSTEMUI_PKG) return

        hookCentralSurfaces(lpparam)
        hookBootComplete(lpparam)
        hookWakefulness(lpparam)
        hookScrimController(lpparam)
        hookScrimViewAlpha(lpparam)
        hookQSExpansion(lpparam)
        hookKeyguardSecurity(lpparam)
        hookBouncerState(lpparam)
        hookWallpaperZoomOut(lpparam)
        hookStatusBarState(lpparam)
        hookDozing(lpparam)
        hookKeyguardShowing(lpparam)
        hookKeyguardMediator(lpparam)
        hookUnlockAnimation(lpparam)
        hookWallpaperOffsets(lpparam)
        hookWallpaperCanvasCapture(lpparam)
    }

    // ── CentralSurfaces: inject view into the root window ────────────────────
    //
    // NothingOS view hierarchy (super_notification_shade.xml):
    //   0: communal_ui_stub
    //   1: scrim_behind
    //   2: scrim_notifications
    //   3: light_reveal_scrim
    //   4: status_bar_expanded  (contains notification_container_parent → QS + notification stack)
    //   5: shared_notification_container  ← lockscreen notification host
    //   6: keyguard_root_view             ← clock/status live here
    //   ...
    //
    // We insert our depth view AFTER keyguard_root_view so it can render over
    // the clock, then clip it above the notification stack so notifications
    // remain in front.
    private fun hookCentralSurfaces(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classNames = listOf(
            "com.android.systemui.statusbar.phone.CentralSurfacesImpl",
            "com.nothing.systemui.statusbar.phone.CentralSurfacesImplEx"
        )
        for (className in classNames) {
            safeHook("$className.makeStatusBarView") {
                val cls = XposedHelpers.findClass(className, lpparam.classLoader)
                // makeStatusBarView takes RegisterStatusBarResult on NothingOS
                val method = cls.declaredMethods.firstOrNull { it.name == "makeStatusBarView" }
                    ?: return@safeHook
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val instance = param.thisObject ?: return
                        val context = try {
                            XposedHelpers.getObjectField(instance, "mContext") as? Context
                        } catch (_: Throwable) { null } ?: return

                        systemUiContext = context.applicationContext
                        syncInitialBootCompleteState()
                        syncInitialWakefulness(instance)

                        // Get the root NotificationShadeWindowView
                        val windowView = try {
                            val ctrl = XposedHelpers.getObjectField(instance, "mNotificationShadeWindowController")
                            val view = XposedHelpers.callMethod(ctrl, "getWindowRootView")
                            view as? ViewGroup
                        } catch (_: Throwable) {
                            try {
                                XposedHelpers.getObjectField(instance, "mNotificationShadeWindowView") as? ViewGroup
                            } catch (_: Throwable) { null }
                        }

                        if (windowView == null) {
                            log("Could not get NotificationShadeWindowView")
                            return
                        }

                        val keyguardRootId = context.resources.getIdentifier(
                            "keyguard_root_view", "id", "com.android.systemui"
                        )
                        val sharedContainerId = context.resources.getIdentifier(
                            "shared_notification_container", "id", "com.android.systemui"
                        )
                        var insertIdx = -1
                        if (keyguardRootId != 0) {
                            for (i in 0 until windowView.childCount) {
                                if (windowView.getChildAt(i).id == keyguardRootId) {
                                    insertIdx = i + 1
                                    break
                                }
                            }
                        }

                        if (insertIdx == -1 && sharedContainerId != 0) {
                            for (i in 0 until windowView.childCount) {
                                if (windowView.getChildAt(i).id == sharedContainerId) {
                                    insertIdx = i + 1
                                    break
                                }
                            }
                        }

                        if (insertIdx == -1) {
                            insertIdx = minOf(7, windowView.childCount)
                        }

                        setupDepthView(context, windowView, insertIdx)
                        syncInitialStatusBarState(instance)
                        updateVisibility()
                        log("Depth wallpaper view injected at index $insertIdx in root window")
                    }
                })
            }
        }

    }

    private fun setupDepthView(context: Context, root: ViewGroup, insertIdx: Int) {
        val container = FrameLayout(context).apply {
            id = View.generateViewId()
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }

        val imageView = ImageView(context).apply {
            id = View.generateViewId()
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // ImageWallpaper stretches the full bitmap into the surface frame.
            // Mirroring that avoids the subject drifting relative to the wallpaper.
            scaleType = ImageView.ScaleType.FIT_XY
        }

        val dimOverlay = View(context).apply {
            id = View.generateViewId()
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            alpha = 0f
        }

        container.addView(imageView)
        container.addView(dimOverlay)
        root.addView(container, insertIdx)

        rootWindowView = root
        depthContainer = container
        subjectImageView = imageView
        dimmingOverlay = dimOverlay
        refreshTrackedViews(root, context)

        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateDepthLayering()
            updateDepthClip()
        }
        keyguardRootView?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateDepthLayering()
        }
        clockContainerView?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateDepthClip()
        }
        widgetHostView?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateDepthClip()
        }
        sharedNotificationContainerView?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateDepthLayering()
        }
        notificationStackView?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateDepthClip()
        }

        updateDepthLayering()
        loadSubjectImage()
    }

    private fun findViewByIdName(root: View, context: Context, idName: String): View? {
        val id = context.resources.getIdentifier(idName, "id", "com.android.systemui")
        return if (id != 0) root.findViewById(id) else null
    }

    private fun refreshTrackedViews(root: ViewGroup, context: Context) {
        if (keyguardRootView?.parent == null) {
            keyguardRootView = findViewByIdName(root, context, "keyguard_root_view")
        }
        if (clockContainerView?.parent == null) {
            clockContainerView = findViewByIdName(root, context, "keyguard_clock_container")
        }
        if (widgetHostView?.parent == null) {
            widgetHostView = findViewByIdName(root, context, "nt_widget_host_view_container")
                ?: findViewByIdName(root, context, "widgets_scroll_container")
        }
        if (sharedNotificationContainerView?.parent == null) {
            sharedNotificationContainerView = findViewByIdName(root, context, "shared_notification_container")
                ?: findDescendantByClassName(
                    root,
                    "com.android.systemui.statusbar.notification.stack.ui.view.SharedNotificationContainer"
                )
        }
        if (notificationStackView?.parent == null) {
            notificationStackView = findViewByIdName(root, context, "notification_stack_scroller")
                ?: findDescendantByClassName(
                    root,
                    "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout"
                )
        }
    }

    private fun findDescendantByClassName(root: View, className: String): View? {
        if (root.javaClass.name == className) return root
        val group = root as? ViewGroup ?: return null
        for (i in 0 until group.childCount) {
            val match = findDescendantByClassName(group.getChildAt(i), className)
            if (match != null) return match
        }
        return null
    }

    private fun updateDepthClip() {
        val imageView = subjectImageView ?: return
        val root = rootWindowView ?: return
        if (root.width <= 0 || root.height <= 0) return
        val context = systemUiContext ?: return
        refreshTrackedViews(root, context)

        val rootLoc = IntArray(2)
        root.getLocationOnScreen(rootLoc)

        var clipBottom = root.height
        val clockBottom = clockContainerView?.takeIf { it.height > 0 }?.let { view ->
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            (loc[1] - rootLoc[1]) + view.height + dp(24)
        }

        val notificationTop = notificationStackView?.takeIf { it.height > 0 }?.let { view ->
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            loc[1] - rootLoc[1]
        }
        val widgetTop = widgetHostView
            ?.takeIf { it.height > 0 && it.visibility == View.VISIBLE }
            ?.let { view ->
                val loc = IntArray(2)
                view.getLocationOnScreen(loc)
                loc[1] - rootLoc[1]
            }

        if (notificationTop != null && notificationTop > 0) {
            clipBottom = minOf(clipBottom, notificationTop)
        }
        if (widgetTop != null && widgetTop > 0) {
            clipBottom = minOf(clipBottom, widgetTop)
        }
        if (clockBottom != null) {
            clipBottom = maxOf(clipBottom, clockBottom)
        }

        imageView.clipBounds = Rect(0, 0, root.width, clipBottom.coerceIn(1, root.height))
    }

    private fun updateDepthLayering() {
        val container = depthContainer ?: return
        val root = rootWindowView ?: return
        val context = systemUiContext ?: return
        refreshTrackedViews(root, context)
        val keyguardRoot = keyguardRootView
        val notifications = sharedNotificationContainerView

        val desiredDepthZ = maxOf((keyguardRoot?.z ?: 0f) + 1f, 1f)
        if (container.translationZ != desiredDepthZ) {
            container.translationZ = desiredDepthZ
        }

        if (notifications != null) {
            val desiredNotificationsZ = maxOf(notifications.z, desiredDepthZ + 1f)
            if (notifications.translationZ != desiredNotificationsZ) {
                notifications.translationZ = desiredNotificationsZ
            }
        }
    }

    private fun dp(value: Int): Int {
        val ctx = systemUiContext ?: return value
        return (value * ctx.resources.displayMetrics.density).toInt()
    }

    private fun hookBootComplete(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeHook("BootCompleteCacheImpl.setBootComplete") {
            XposedHelpers.findAndHookMethod(
                "com.android.systemui.BootCompleteCacheImpl",
                lpparam.classLoader,
                "setBootComplete",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        isBootComplete = true
                        mainHandler.post { updateVisibility() }
                    }
                }
            )
        }
    }

    private fun hookWakefulness(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeHook("WakefulnessLifecycle visibility gating") {
            val className = "com.android.systemui.keyguard.WakefulnessLifecycle"

            XposedHelpers.findAndHookMethod(
                className,
                lpparam.classLoader,
                "dispatchStartedWakingUp",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        isWakeTransitionActive = true
                        mainHandler.post { hideDepthWallpaper() }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                className,
                lpparam.classLoader,
                "dispatchFinishedWakingUp",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        isWakeTransitionActive = false
                        mainHandler.post { updateVisibility() }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                className,
                lpparam.classLoader,
                "dispatchStartedGoingToSleep",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        isWakeTransitionActive = true
                        mainHandler.post { hideDepthWallpaper() }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                className,
                lpparam.classLoader,
                "dispatchFinishedGoingToSleep",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        isWakeTransitionActive = true
                        mainHandler.post { hideDepthWallpaper() }
                    }
                }
            )
        }
    }

    private fun hookBouncerState(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeHook("StatusBarKeyguardViewManager bouncer state") {
            val cls = XposedHelpers.findClass(
                "com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager",
                lpparam.classLoader
            )

            val immediateHideTargets = setOf("showBouncer", "showPrimaryBouncer")
            cls.declaredMethods
                .filter { method -> method.name in immediateHideTargets }
                .forEach { method ->
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            cacheStatusBarKeyguardViewManager(param.thisObject)
                            fallbackBouncerShowing = true
                            fallbackBouncerAnimatingAway = false
                            mainHandler.post { hideDepthWallpaper() }
                        }
                    })
                }

            val refreshTargets = setOf(
                "updateStates",
                "hide",
                "hideBouncer",
                "hideAlternateBouncer",
                "reset",
                "show"
            )
            cls.declaredMethods
                .filter { method -> method.name in refreshTargets }
                .forEach { method ->
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            cacheStatusBarKeyguardViewManager(param.thisObject)
                            syncBouncerState(param.thisObject)
                            mainHandler.post { updateVisibility() }
                        }
                    })
                }
        }
    }

    // ── ScrimController: sync dimming overlay ────────────────────────────────
    private fun hookScrimController(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeHook("ScrimController.applyAndDispatchState") {
            XposedHelpers.findAndHookMethod(
                "com.android.systemui.statusbar.phone.ScrimController",
                lpparam.classLoader,
                "applyAndDispatchState",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        scrimControllerInstance = param.thisObject
                        val scrimAlpha = readScrimBehindAlpha(param.thisObject)
                        currentScrimStateName = readScrimStateName(param.thisObject) ?: currentScrimStateName
                        mainHandler.post {
                            dimmingOverlay?.alpha = scrimAlpha
                            updateVisibility()
                        }
                    }
                }
            )
        }
    }

    private fun readScrimBehindAlpha(scrimController: Any): Float {
        // NothingOS field: mScrimBehindAlphaKeyguard (confirmed from decompiled ScrimController)
        return try {
            XposedHelpers.getFloatField(scrimController, "mScrimBehindAlphaKeyguard")
        } catch (_: Throwable) {
            try {
                XposedHelpers.getFloatField(scrimController, "mBehindAlpha")
            } catch (_: Throwable) { 0f }
        }
    }

    private fun readScrimStateName(scrimController: Any?): String? {
        scrimController ?: return null
        return try {
            XposedHelpers.getObjectField(scrimController, "mState")?.toString()
        } catch (_: Throwable) {
            null
        }
    }

    private fun hookScrimViewAlpha(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeHook("ScrimView.setViewAlpha") {
            XposedHelpers.findAndHookMethod(
                "com.android.systemui.scrim.ScrimView",
                lpparam.classLoader,
                "setViewAlpha",
                Float::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val scrimController = scrimControllerInstance ?: return
                        val notificationScrim = try {
                            XposedHelpers.getObjectField(scrimController, "mNotificationsScrim")
                        } catch (_: Throwable) {
                            null
                        } ?: return

                        if (notificationScrim !== param.thisObject) return

                        var notificationAlpha = param.args[0] as? Float ?: return
                        val keyguardAlpha = readScrimBehindAlpha(scrimController)
                        if (notificationAlpha < keyguardAlpha) {
                            notificationAlpha = 0f
                        }

                        notificationShadeSubjectAlpha = if (notificationAlpha > keyguardAlpha) {
                            ((1f - notificationAlpha) / (1f - keyguardAlpha).coerceAtLeast(0.001f))
                                .coerceIn(0f, 1f)
                        } else {
                            1f
                        }

                        mainHandler.post { updateVisibility() }
                    }
                }
            )
        }
    }

    // ── QS expansion: hide only once the keyguard shade is meaningfully open ──
    // NothingOS signature: setQsExpansion(float f, float f2, float f3, float f4)
    private fun hookQSExpansion(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeHook("QSImpl.setQsExpansion") {
            XposedHelpers.findAndHookMethod(
                "com.android.systemui.qs.QSImpl",
                lpparam.classLoader,
                "setQsExpansion",
                Float::class.javaPrimitiveType,  // expansion
                Float::class.javaPrimitiveType,  // panelExpansionFraction
                Float::class.javaPrimitiveType,  // headerTranslation
                Float::class.javaPrimitiveType,  // squishinessFraction
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isOnKeyguard) return
                        val expansion = param.args[0] as Float
                        qsExpansion = expansion
                        mainHandler.post {
                            val container = depthContainer ?: return@post
                            if (!isEnabled()) return@post
                            if (expansion > QS_HIDE_THRESHOLD) {
                                container.visibility = View.GONE
                            } else if (container.visibility == View.GONE && shouldShow()) {
                                container.visibility = View.VISIBLE
                            }
                            if (container.visibility == View.VISIBLE) {
                                container.alpha = currentSubjectAlpha()
                            }
                        }
                    }
                }
            )
        }
    }

    // ── Keyguard security: hide during PIN/pattern entry ─────────────────────
    private fun hookKeyguardSecurity(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeHook("KeyguardSecurityContainerController.startAppearAnimation") {
            XposedHelpers.findAndHookMethod(
                "com.android.keyguard.KeyguardSecurityContainerController",
                lpparam.classLoader,
                "startAppearAnimation",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        fallbackBouncerShowing = true
                        fallbackBouncerAnimatingAway = false
                        mainHandler.post { hideDepthWallpaper() }
                    }
                }
            )
        }

        // startDisappearAnimation(Runnable) — confirmed from decompiled source
        safeHook("KeyguardSecurityContainerController.startDisappearAnimation") {
            XposedHelpers.findAndHookMethod(
                "com.android.keyguard.KeyguardSecurityContainerController",
                lpparam.classLoader,
                "startDisappearAnimation",
                Runnable::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        fallbackBouncerShowing = true
                        fallbackBouncerAnimatingAway = true
                        mainHandler.post { hideDepthWallpaper() }
                    }
                }
            )
        }
    }

    // ── WallpaperManager: force zoom to 1f ───────────────────────────────────
    private fun hookWallpaperZoomOut(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeHook("WallpaperManager.setWallpaperZoomOut") {
            XposedHelpers.findAndHookMethod(
                "android.app.WallpaperManager",
                lpparam.classLoader,
                "setWallpaperZoomOut",
                android.os.IBinder::class.java,
                Float::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isEnabled()) {
                            param.args[1] = 1f
                        }
                    }
                }
            )
        }
    }

    private fun hookWallpaperCanvasCapture(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeHook("ImageWallpaper.CanvasEngine.drawFrameOnCanvas") {
            XposedHelpers.findAndHookMethod(
                "com.android.systemui.wallpapers.ImageWallpaper\$CanvasEngine",
                lpparam.classLoader,
                "drawFrameOnCanvas",
                Bitmap::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!getPreferenceBoolean(PREF_DEPTH_ENABLED, false)) return
                        val bitmap = param.args.firstOrNull() as? Bitmap ?: return
                        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return

                        val sourceFlag = readWallpaperSourceFlag(param.thisObject) ?: return
                        if (sourceFlag != WallpaperManager.FLAG_LOCK &&
                            sourceFlag != WallpaperManager.FLAG_SYSTEM
                        ) return

                        maybeCacheRenderedWallpaper(bitmap, sourceFlag)
                    }
                }
            )
        }
    }

    private fun hookWallpaperOffsets(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeHook("ImageWallpaper.CanvasEngine.onOffsetsChanged") {
            XposedHelpers.findAndHookMethod(
                "com.android.systemui.wallpapers.ImageWallpaper\$CanvasEngine",
                lpparam.classLoader,
                "onOffsetsChanged",
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        currentWallpaperXOffset = sanitizeWallpaperOffset(
                            param.args.getOrNull(0) as? Float
                        )
                        currentWallpaperYOffset = sanitizeWallpaperOffset(
                            param.args.getOrNull(1) as? Float
                        )

                        if (!getPreferenceBoolean(PREF_DEPTH_ENABLED, false)) return

                        val bitmap = try {
                            XposedHelpers.getObjectField(param.thisObject, "mBitmap") as? Bitmap
                        } catch (_: Throwable) {
                            null
                        } ?: return

                        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return

                        val sourceFlag = readWallpaperSourceFlag(param.thisObject) ?: return
                        if (sourceFlag != WallpaperManager.FLAG_LOCK &&
                            sourceFlag != WallpaperManager.FLAG_SYSTEM
                        ) return

                        maybeCacheRenderedWallpaper(bitmap, sourceFlag)
                    }
                }
            )
        }
    }

    private fun readWallpaperSourceFlag(canvasEngine: Any?): Int? {
        canvasEngine ?: return null
        return try {
            XposedHelpers.callMethod(canvasEngine, "getSourceFlag") as? Int
        } catch (_: Throwable) {
            try {
                XposedHelpers.callMethod(canvasEngine, "getWallpaperFlags") as? Int
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun maybeCacheRenderedWallpaper(bitmap: Bitmap, sourceFlag: Int) {
        val now = SystemClock.uptimeMillis()
        val signature = listOf(
            sourceFlag,
            bitmap.width,
            bitmap.height,
            runCatching { bitmap.generationId }.getOrDefault(0),
            offsetSignatureValue(currentWallpaperXOffset),
            offsetSignatureValue(currentWallpaperYOffset)
        ).joinToString(":")

        synchronized(renderedWallpaperLock) {
            if (signature == lastRenderedWallpaperSignature &&
                now - lastRenderedWallpaperCaptureAt < RENDERED_WALLPAPER_CAPTURE_DEBOUNCE_MS
            ) {
                return
            }

            lastRenderedWallpaperSignature = signature
            lastRenderedWallpaperCaptureAt = now
        }

        bgExecutor.submit {
            try {
                val rendered = renderWallpaperBitmapToDisplay(bitmap) ?: return@submit
                try {
                    val ctx = systemUiContext ?: return@submit
                    ctx.contentResolver.openOutputStream(
                        Uri.parse(RemotePrefProvider.buildRenderedWallpaperUri(sourceFlag)),
                        "wt"
                    )?.use { output ->
                        rendered.compress(Bitmap.CompressFormat.PNG, 100, output)
                    }
                } finally {
                    if (!rendered.isRecycled) {
                        rendered.recycle()
                    }
                }
            } catch (t: Throwable) {
                log("Failed to cache rendered wallpaper: $t")
            }
        }
    }

    private fun renderWallpaperBitmapToDisplay(bitmap: Bitmap): Bitmap? {
        val ctx = systemUiContext ?: return null
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return null
        val bounds = wm.currentWindowMetrics.bounds
        val displayWidth = bounds.width().coerceAtLeast(1)
        val displayHeight = bounds.height().coerceAtLeast(1)

        val ratioW = displayWidth.toFloat() / bitmap.width.toFloat()
        val ratioH = displayHeight.toFloat() / bitmap.height.toFloat()
        val scale = maxOf(ratioW, ratioH)

        val desiredWidth = Math.round(scale * bitmap.width).coerceAtLeast(1)
        val desiredHeight = Math.round(scale * bitmap.height).coerceAtLeast(1)
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, desiredWidth, desiredHeight, true)

        val horizontalOverflow = (desiredWidth - displayWidth).coerceAtLeast(0)
        val verticalOverflow = (desiredHeight - displayHeight).coerceAtLeast(0)
        val xPixelShift = Math.round(
            horizontalOverflow * sanitizeWallpaperOffset(currentWallpaperXOffset)
        ).coerceIn(0, horizontalOverflow)
        val yPixelShift = Math.round(
            verticalOverflow * sanitizeWallpaperOffset(currentWallpaperYOffset)
        ).coerceIn(0, verticalOverflow)

        val croppedBitmap = Bitmap.createBitmap(
            scaledBitmap,
            xPixelShift,
            yPixelShift,
            displayWidth.coerceAtMost(scaledBitmap.width - xPixelShift),
            displayHeight.coerceAtMost(scaledBitmap.height - yPixelShift)
        )

        if (croppedBitmap != scaledBitmap && scaledBitmap != bitmap && !scaledBitmap.isRecycled) {
            scaledBitmap.recycle()
        }

        return croppedBitmap
    }

    private fun sanitizeWallpaperOffset(value: Float?): Float {
        if (value == null || value.isNaN() || value.isInfinite()) {
            return DEFAULT_WALLPAPER_OFFSET
        }
        return value.coerceIn(0f, 1f)
    }

    private fun offsetSignatureValue(value: Float): Int {
        return Math.round(sanitizeWallpaperOffset(value) * 1000f)
    }

    // ── StatusBarState: track keyguard state ─────────────────────────────────
    // NothingOS signature: setState(int i, boolean z) — confirmed from decompiled source
    // States: SHADE=0, KEYGUARD=1, SHADE_LOCKED=2
    private fun hookStatusBarState(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeHook("StatusBarStateControllerImpl.setState") {
            val cls = XposedHelpers.findClass(
                "com.android.systemui.statusbar.StatusBarStateControllerImpl",
                lpparam.classLoader
            )
            val methods = cls.declaredMethods.filter { method ->
                method.name == "setState" &&
                    method.parameterTypes.isNotEmpty() &&
                    method.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            if (methods.isEmpty()) {
                log("No setState overload found")
                return@safeHook
            }
            methods.forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val state = param.args.firstOrNull() as? Int ?: return
                        val onKeyguard = state == 1 || state == 2
                        hasStatusBarState = true
                        if (isOnKeyguard != onKeyguard) {
                            isOnKeyguard = onKeyguard
                            log("StatusBar state changed: $state, keyguard=$onKeyguard")
                        } else {
                            isOnKeyguard = onKeyguard
                        }
                        if (!onKeyguard) {
                            clearBouncerState()
                        }
                        mainHandler.post { updateVisibility() }
                    }
                })
            }
        }
    }

    // ── Doze: hide when dozing ───────────────────────────────────────────────
    private fun hookDozing(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeHook("StatusBarStateControllerImpl.setIsDozing") {
            val cls = XposedHelpers.findClass(
                "com.android.systemui.statusbar.StatusBarStateControllerImpl",
                lpparam.classLoader
            )
            val methods = cls.declaredMethods.filter { method ->
                method.name == "setIsDozing" &&
                    method.parameterTypes.isNotEmpty() &&
                    method.parameterTypes[0] == Boolean::class.javaPrimitiveType
            }
            if (methods.isEmpty()) {
                log("No setIsDozing overload found")
                return@safeHook
            }
            methods.forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val dozing = param.args.firstOrNull() as? Boolean ?: return
                        if (isDozing != dozing) {
                            isDozing = dozing
                            log("Dozing changed: $dozing")
                        } else {
                            isDozing = dozing
                        }
                        mainHandler.post { updateVisibility() }
                    }
                })
            }
        }
    }

    // ── KeyguardStateController: authoritative showing/hiding state ─────────
    private fun hookKeyguardShowing(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeHook("KeyguardStateControllerImpl showing") {
            val cls = XposedHelpers.findClass(
                "com.android.systemui.statusbar.policy.KeyguardStateControllerImpl",
                lpparam.classLoader
            )
            val targetNames = setOf(
                "notifyKeyguardState",
                "setKeyguardShowing",
                "setShowing",
                "notifyKeyguardChanged"
            )
            val methods = cls.declaredMethods.filter { method ->
                method.name in targetNames &&
                    method.parameterTypes.isNotEmpty() &&
                    method.parameterTypes[0] == Boolean::class.javaPrimitiveType
            }
            if (methods.isEmpty()) {
                log("No KeyguardStateController showing method found")
                return@safeHook
            }
            methods.forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val showing = param.args.firstOrNull() as? Boolean ?: return
                        hasStatusBarState = true
                        if (isOnKeyguard != showing) {
                            isOnKeyguard = showing
                            log("Keyguard showing changed: $showing via ${method.name}")
                        } else {
                            isOnKeyguard = showing
                        }
                        if (!showing) {
                            clearBouncerState()
                        }
                        mainHandler.post { updateVisibility() }
                    }
                })
            }
        }
    }

    // ── KeyguardViewMediator: fallback for unlock transitions on NothingOS ───
    private fun hookKeyguardMediator(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeHook("KeyguardViewMediator showing") {
            val cls = XposedHelpers.findClass(
                "com.android.systemui.keyguard.KeyguardViewMediator",
                lpparam.classLoader
            )
            val methods = cls.declaredMethods.filter { method ->
                method.name.contains("setShowing") &&
                    method.parameterTypes.isNotEmpty() &&
                    method.parameterTypes[0] == Boolean::class.javaPrimitiveType
            }
            if (methods.isEmpty()) {
                log("No KeyguardViewMediator setShowing* method found")
                return@safeHook
            }
            methods.forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val showing = param.args.firstOrNull() as? Boolean ?: return
                        hasStatusBarState = true
                        if (isOnKeyguard != showing) {
                            isOnKeyguard = showing
                            log("Keyguard showing changed: $showing via ${method.name}")
                        } else {
                            isOnKeyguard = showing
                        }
                        if (!showing) {
                            clearBouncerState()
                        }
                        mainHandler.post { updateVisibility() }
                    }
                })
            }
        }
    }

    // ── Unlock transition: hide before the wallpaper/keyguard animation drifts ──
    private fun hookUnlockAnimation(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeHook("NotificationPanelViewController.unlockAnimationStarted") {
            val cls = XposedHelpers.findClass(
                "com.android.systemui.shade.NotificationPanelViewController",
                lpparam.classLoader
            )
            val methods = cls.declaredMethods.filter { method ->
                method.name == "unlockAnimationStarted" &&
                    method.parameterTypes.size == 3 &&
                    method.parameterTypes[0] == Boolean::class.javaPrimitiveType &&
                    method.parameterTypes[1] == Boolean::class.javaPrimitiveType &&
                    method.parameterTypes[2] == Long::class.javaPrimitiveType
            }
            methods.forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        fallbackBouncerAnimatingAway = true
                        mainHandler.post { hideDepthWallpaper() }
                    }
                })
            }
        }

        safeHook("StatusBarKeyguardViewManager.startPreHideAnimation") {
            XposedHelpers.findAndHookMethod(
                "com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager",
                lpparam.classLoader,
                "startPreHideAnimation",
                Runnable::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        cacheStatusBarKeyguardViewManager(param.thisObject)
                        fallbackBouncerShowing = true
                        fallbackBouncerAnimatingAway = true
                        mainHandler.post { hideDepthWallpaper() }
                    }
                }
            )
        }
    }

    // ── Visibility logic ─────────────────────────────────────────────────────

    private fun isEnabled(): Boolean {
        val enabled = getPreferenceBoolean(PREF_DEPTH_ENABLED, false)
        val path = getPreferenceString(PREF_DEPTH_IMAGE, "")
        return enabled && path.isNotEmpty()
    }

    private fun cacheStatusBarKeyguardViewManager(instance: Any?) {
        if (instance != null) {
            statusBarKeyguardViewManager = instance
        }
    }

    private fun clearBouncerState() {
        fallbackBouncerShowing = false
        fallbackBouncerAnimatingAway = false
    }

    private fun syncInitialBootCompleteState() {
        if (!isBootComplete) {
            isBootComplete = readSystemBootCompleted()
        }
    }

    private fun syncInitialWakefulness(centralSurfaces: Any) {
        val wakefulness = try {
            val lifecycle = XposedHelpers.getObjectField(centralSurfaces, "mWakefulnessLifecycle")
            XposedHelpers.callMethod(lifecycle, "getWakefulness") as? Int
        } catch (_: Throwable) {
            null
        }

        if (wakefulness != null) {
            isWakeTransitionActive = wakefulness != WAKEFULNESS_AWAKE
            log("Initial wakefulness: $wakefulness, activeTransition=$isWakeTransitionActive")
            return
        }

        val interactive = systemUiContext
            ?.getSystemService(PowerManager::class.java)
            ?.isInteractive == true
        isWakeTransitionActive = !interactive
        log("Initial interactive=$interactive, activeTransition=$isWakeTransitionActive")
    }

    private fun syncBouncerState(instance: Any? = statusBarKeyguardViewManager) {
        val manager = instance ?: return
        statusBarKeyguardViewManager = manager

        val isShowing = readBooleanMethod(manager, "isBouncerShowing")
        val isPrimaryShowingOrTransitioning = readBooleanMethod(manager, "primaryBouncerIsOrWillBeShowing")
        val isAnimatingAway = readBooleanMethod(manager, "bouncerIsAnimatingAway")

        fallbackBouncerShowing = isShowing || isPrimaryShowingOrTransitioning || isAnimatingAway
        fallbackBouncerAnimatingAway = isAnimatingAway
    }

    private fun isBouncerActive(): Boolean {
        val manager = statusBarKeyguardViewManager
        if (manager != null) {
            val isShowing = readBooleanMethod(manager, "isBouncerShowing")
            val isPrimaryShowingOrTransitioning = readBooleanMethod(manager, "primaryBouncerIsOrWillBeShowing")
            val isAnimatingAway = readBooleanMethod(manager, "bouncerIsAnimatingAway")
            if (isShowing || isPrimaryShowingOrTransitioning || isAnimatingAway) {
                fallbackBouncerShowing = true
                fallbackBouncerAnimatingAway = isAnimatingAway
                return true
            }
        }
        return fallbackBouncerShowing || fallbackBouncerAnimatingAway
    }

    private fun readBooleanMethod(instance: Any, methodName: String): Boolean {
        return try {
            XposedHelpers.callMethod(instance, methodName) as? Boolean == true
        } catch (_: Throwable) {
            false
        }
    }

    private fun readSystemBootCompleted(): Boolean {
        return try {
            val cls = Class.forName("android.os.SystemProperties")
            (XposedHelpers.callStaticMethod(cls, "get", "sys.boot_completed") as? String) == "1"
        } catch (_: Throwable) {
            true
        }
    }

    private fun shouldShow(): Boolean {
        if (!isEnabled()) return false
        if (!hasStatusBarState) return false
        if (!isBootComplete) {
            isBootComplete = readSystemBootCompleted()
            if (!isBootComplete) return false
        }
        if (!isOnKeyguard) return false
        if (isDozing) return false
        if (isWakeTransitionActive) return false
        val scrimStateName = readScrimStateName(scrimControllerInstance) ?: currentScrimStateName
        if (scrimStateName.isNotEmpty() &&
            scrimStateName != SCRIM_STATE_KEYGUARD &&
            scrimStateName != SCRIM_STATE_SHADE_LOCKED
        ) return false
        if (isBouncerActive()) return false
        if (qsExpansion > QS_HIDE_THRESHOLD) return false
        val ctx = systemUiContext ?: return false
        if (ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) return false
        return true
    }

    private fun currentSubjectAlpha(): Float {
        val scrimAlpha = notificationShadeSubjectAlpha.coerceIn(0f, 1f)
        val stabilizedScrimAlpha = if (scrimAlpha >= SUBJECT_ALPHA_FULL_THRESHOLD) {
            1f
        } else {
            scrimAlpha
        }

        if (qsExpansion <= QS_FADE_START_THRESHOLD) {
            return stabilizedScrimAlpha
        }

        val qsAlpha = 1f - (
            (qsExpansion - QS_FADE_START_THRESHOLD) /
                (QS_HIDE_THRESHOLD - QS_FADE_START_THRESHOLD).coerceAtLeast(0.001f)
            )
            .coerceIn(0f, 1f)

        return minOf(stabilizedScrimAlpha, qsAlpha.coerceIn(0f, 1f))
    }

    private fun syncInitialStatusBarState(centralSurfaces: Any) {
        try {
            val controller = try {
                XposedHelpers.getObjectField(centralSurfaces, "mStatusBarStateController")
            } catch (_: Throwable) {
                XposedHelpers.getObjectField(centralSurfaces, "mStatusBarStateControllerImpl")
            }

            val state = try {
                XposedHelpers.callMethod(controller, "getState") as? Int
            } catch (_: Throwable) {
                null
            }
            if (state != null) {
                hasStatusBarState = true
                isOnKeyguard = state == 1 || state == 2
                if (!isOnKeyguard) {
                    clearBouncerState()
                }
                log("Initial status bar state: $state, keyguard=$isOnKeyguard")
            }

            val dozing = try {
                XposedHelpers.callMethod(controller, "isDozing") as? Boolean
            } catch (_: Throwable) {
                null
            }
            if (dozing != null) {
                isDozing = dozing
                log("Initial dozing: $isDozing")
            }
        } catch (t: Throwable) {
            log("Failed to sync initial status bar state: $t")
        }
    }

    private fun updateVisibility() {
        val container = depthContainer ?: return
        if (!isEnabled()) {
            if (container.visibility != View.GONE) {
                container.visibility = View.GONE
            }
            return
        }
        val target = if (shouldShow()) View.VISIBLE else View.GONE
        if (container.visibility != target) {
            container.visibility = target
            if (target == View.VISIBLE) {
                updateDepthLayering()
                updateDepthClip()
                container.alpha = currentSubjectAlpha()
            }
        } else if (target == View.VISIBLE) {
            updateDepthLayering()
            updateDepthClip()
            container.alpha = currentSubjectAlpha()
        }
    }

    private fun hideDepthWallpaper() {
        val container = depthContainer ?: return
        if (container.visibility != View.GONE) {
            container.visibility = View.GONE
        }
    }

    // ── Bitmap loading ───────────────────────────────────────────────────────

    private fun loadSubjectImage() {
        if (!isEnabled()) {
            mainHandler.post { updateVisibility() }
            return
        }

        val path = getPreferenceString(PREF_DEPTH_IMAGE, "")
        val opacity = getPreferenceInt(PREF_DEPTH_OPACITY, 100)

        if (path.isEmpty()) {
            mainHandler.post { updateVisibility() }
            return
        }

        if (path == lastLoadedPath && !forceReload) {
            mainHandler.post {
                subjectImageView?.imageAlpha = Math.round(opacity * 2.55f)
                updateVisibility()
            }
            return
        }

        bgExecutor.submit {
            try {
                val bitmap = decodeSubjectBitmap(path)
                if (bitmap == null) {
                    log("Failed to decode subject bitmap")
                    mainHandler.post { updateVisibility() }
                    return@submit
                }

                val resized = resizeBitmapToDisplay(bitmap)
                if (resized != bitmap && !bitmap.isRecycled) bitmap.recycle()

                mainHandler.post {
                    val old = currentBitmap
                    currentBitmap = resized
                    lastLoadedPath = path
                    forceReload = false

                    subjectImageView?.setImageBitmap(resized)
                    subjectImageView?.imageAlpha = Math.round(opacity * 2.55f)

                    if (old != null && old != resized && !old.isRecycled) {
                        old.recycle()
                    }

                    updateVisibility()
                    log("Subject loaded: ${resized.width}x${resized.height}")
                }
            } catch (e: OutOfMemoryError) {
                log("OOM loading subject: $e")
            } catch (e: Throwable) {
                log("Error loading subject: $e")
            }
        }
    }

    companion object {
        const val PREF_DEPTH_ENABLED = "pref_depth_wallpaper_enabled"
        const val PREF_DEPTH_IMAGE = "pref_depth_wallpaper_image_path"
        const val PREF_DEPTH_OPACITY = "pref_depth_wallpaper_opacity"

        @Volatile var depthContainer: FrameLayout? = null
        @Volatile var subjectImageView: ImageView? = null
        @Volatile var dimmingOverlay: View? = null
        @Volatile var currentBitmap: Bitmap? = null
        @Volatile var systemUiContext: Context? = null
        @Volatile var rootWindowView: ViewGroup? = null
        @Volatile var keyguardRootView: View? = null
        @Volatile var clockContainerView: View? = null
        @Volatile var widgetHostView: View? = null
        @Volatile var sharedNotificationContainerView: View? = null
        @Volatile var notificationStackView: View? = null
        @Volatile var scrimControllerInstance: Any? = null
        @Volatile var currentScrimStateName: String = ""
        @Volatile var notificationShadeSubjectAlpha = 1f
        @Volatile var statusBarKeyguardViewManager: Any? = null

        @Volatile var isOnKeyguard = false
        @Volatile var isDozing = false
        @Volatile var qsExpansion = 0f
        @Volatile var hasStatusBarState = false
        @Volatile var isBootComplete = false
        @Volatile var isWakeTransitionActive = false
        @Volatile var fallbackBouncerShowing = false
        @Volatile var fallbackBouncerAnimatingAway = false
        @Volatile var lastLoadedPath: String? = null
        @Volatile var forceReload = false
        @Volatile var lastRenderedWallpaperSignature: String? = null
        @Volatile var lastRenderedWallpaperCaptureAt = 0L

        const val WAKEFULNESS_AWAKE = 2
        const val SCRIM_STATE_KEYGUARD = "KEYGUARD"
        const val SCRIM_STATE_SHADE_LOCKED = "SHADE_LOCKED"
        const val QS_FADE_START_THRESHOLD = 0.18f
        const val QS_HIDE_THRESHOLD = 0.45f
        const val SUBJECT_ALPHA_FULL_THRESHOLD = 0.92f
        const val RENDERED_WALLPAPER_CAPTURE_DEBOUNCE_MS = 3_000L
        const val DEFAULT_WALLPAPER_OFFSET = 0.5f
        @Volatile var currentWallpaperXOffset = DEFAULT_WALLPAPER_OFFSET
        @Volatile var currentWallpaperYOffset = DEFAULT_WALLPAPER_OFFSET

        private val bgExecutor by lazy { Executors.newSingleThreadExecutor() }
        private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
        private val renderedWallpaperLock = Any()

        fun refreshFromPrefs() {
            val path = BaseHook.getPreferenceString(PREF_DEPTH_IMAGE, "")
            val opacity = BaseHook.getPreferenceInt(PREF_DEPTH_OPACITY, 100)
            val enabled = BaseHook.getPreferenceBoolean(PREF_DEPTH_ENABLED, false)

            if (path != lastLoadedPath) {
                forceReload = true
            }

            mainHandler.post {
                val container = depthContainer ?: return@post
                if (!enabled || path.isEmpty()) {
                    container.visibility = View.GONE
                    return@post
                }

                val iv = subjectImageView ?: return@post
                iv.imageAlpha = Math.round(opacity * 2.55f)

                if (forceReload) {
                    bgExecutor.submit {
                        try {
                            val bitmap = decodeSubjectBitmap(path) ?: return@submit
                            val resized = resizeBitmapToDisplay(bitmap)
                            if (resized != bitmap && !bitmap.isRecycled) bitmap.recycle()

                            mainHandler.post {
                                val old = currentBitmap
                                currentBitmap = resized
                                lastLoadedPath = path
                                forceReload = false

                                iv.setImageBitmap(resized)
                                iv.imageAlpha = Math.round(opacity * 2.55f)

                                if (old != null && old != resized && !old.isRecycled) {
                                    old.recycle()
                                }
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log("NothingXpert/DepthWallpaper: refreshFromPrefs bitmap load failed: $t")
                        }
                    }
                }
            }
        }

        fun decodeSubjectBitmap(path: String): Bitmap? {
            return try {
                val sampleSize = calculateSampleSize(path)
                val opts = if (sampleSize > 1) {
                    BitmapFactory.Options().apply { inSampleSize = sampleSize }
                } else null

                if (path.startsWith("content://")) {
                    val ctx = systemUiContext ?: return null
                    ctx.contentResolver.openInputStream(Uri.parse(path))?.use { input ->
                        BitmapFactory.decodeStream(input, null, opts)
                    }
                } else {
                    val file = java.io.File(path)
                    if (!file.exists() || !file.canRead()) {
                        XposedBridge.log("NothingXpert/DepthWallpaper: Subject file not readable: $path")
                        null
                    } else {
                        BitmapFactory.decodeFile(path, opts)
                    }
                }
            } catch (e: Throwable) {
                XposedBridge.log("NothingXpert/DepthWallpaper: decodeSubjectBitmap failed: $e")
                null
            }
        }

        private fun calculateSampleSize(path: String): Int {
            val ctx = systemUiContext ?: return 1
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return 1
            val bounds = wm.currentWindowMetrics.bounds
            val targetW = bounds.width().coerceAtLeast(1)
            val targetH = bounds.height().coerceAtLeast(1)

            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            try {
                if (path.startsWith("content://")) {
                    ctx.contentResolver.openInputStream(Uri.parse(path))?.use { input ->
                        BitmapFactory.decodeStream(input, null, boundsOpts)
                    }
                } else {
                    BitmapFactory.decodeFile(path, boundsOpts)
                }
            } catch (_: Throwable) {
                return 1
            }

            val imgW = boundsOpts.outWidth
            val imgH = boundsOpts.outHeight
            if (imgW <= 0 || imgH <= 0) return 1

            var sample = 1
            while (imgW / (sample * 2) >= targetW && imgH / (sample * 2) >= targetH) {
                sample *= 2
            }
            return sample
        }

        fun resizeBitmapToDisplay(bitmap: Bitmap): Bitmap {
            val ctx = systemUiContext ?: return bitmap
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return bitmap
            val bounds: Rect = wm.currentWindowMetrics.bounds
            val screenW = bounds.width().coerceAtLeast(1)
            val screenH = bounds.height().coerceAtLeast(1)

            if (bitmap.width == screenW && bitmap.height == screenH) {
                return bitmap
            }

            return Bitmap.createScaledBitmap(bitmap, screenW, screenH, true)
        }
    }
}
