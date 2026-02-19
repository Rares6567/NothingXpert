package com.nothingxpert.hooks

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class VolumeHooks : BaseHook() {
    override val tag = "Volume"
    
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    
    private val skipUpRunnable = Runnable {
        if (!skipUpSent) {
            val action = getVolumeUpAction()
            val ok = executeVolumeAction(action, runnableContext)
            log("skipUpRunnable fired action=$action ok=$ok")
            if (ok) skipUpSent = true
        }
    }
    
    private val skipDownRunnable = Runnable {
        if (!skipDownSent) {
            val action = getVolumeDownAction()
            val ok = executeVolumeAction(action, runnableContext)
            log("skipDownRunnable fired action=$action ok=$ok")
            if (ok) skipDownSent = true
        }
    }
    
    override fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android") return
        
        installPolicyVolumeHooks(lpparam)
        installInputManagerVolumeHook(lpparam)
    }
    
    private fun installPolicyVolumeHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeHook("PhoneWindowManager.interceptKeyBeforeQueueing") {
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
                            log("interceptBeforeQueueing consumed")
                            param.result = 0
                        }
                    }
                }
            )
            policyVolumeInstalled = true
        }
        
        var dispatchHooked = false
        safeHook("PhoneWindowManager.interceptKeyBeforeDispatching (WS)") {
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
                            log("interceptBeforeDispatch consumed (WS)")
                            param.result = 0L
                        }
                    }
                }
            )
            dispatchHooked = true
        }
        
        if (!dispatchHooked) {
            safeHook("PhoneWindowManager.interceptKeyBeforeDispatching (fallback)") {
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
                                log("interceptBeforeDispatch consumed (fallback)")
                                param.result = 0L
                            }
                        }
                    }
                )
            }
        }
        
        if (dispatchHooked) policyVolumeInstalled = true
    }
    
    private fun installInputManagerVolumeHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeHook("InputManagerCallback.interceptKeyBeforeQueueing") {
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
                            log("InputManagerCallback consumed")
                            param.result = 0
                        }
                    }
                }
            )
        }
    }
    
    private fun handleVolumeForTracks(event: KeyEvent, powerManager: PowerManager, context: Context?): Boolean {
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        
        val configuredAction = if (code == KeyEvent.KEYCODE_VOLUME_UP) getVolumeUpAction() else getVolumeDownAction()
        if (configuredAction == ACTION_DEFAULT) return false
        
        val screenOff = !powerManager.isInteractive
        if (!screenOff) return false
        
        if (isVolumeEventHandled(event)) return true
        markVolumeEventHandled(event)
        
        if (context != null) runnableContext = context
        
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
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
                return true
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
            audio.adjustSuggestedStreamVolume(direction, AudioManager.USE_DEFAULT_STREAM_TYPE, 0)
        } catch (t: Throwable) {
            log("adjustShortPressVolume failed: $t")
        }
    }
    
    private fun executeVolumeAction(action: Int, context: Context?): Boolean {
        val ctx = context ?: runnableContext ?: getSystemContext()
        return try {
            when (action) {
                ACTION_NONE -> true
                ACTION_TORCH -> {
                    doToggleFlashlight(ctx)
                    true
                }
                ACTION_PLAY_PAUSE -> sendMediaCommand(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, ctx)
                ACTION_NEXT -> sendMediaCommand(KeyEvent.KEYCODE_MEDIA_NEXT, ctx)
                ACTION_PREV -> sendMediaCommand(KeyEvent.KEYCODE_MEDIA_PREVIOUS, ctx)
                else -> false
            }
        } catch (t: Throwable) {
            log("executeVolumeAction failed: $t")
            false
        }
    }
    
    private fun sendMediaCommand(keyCode: Int, context: Context?): Boolean {
        val ctx = context ?: runnableContext ?: getSystemContext()
        val audio = ctx?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audio == null) {
            log("sendMediaCommand AudioManager is null")
            return false
        }
        return try {
            val down = KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, keyCode, 0)
            val up = KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0)
            audio.dispatchMediaKeyEvent(down)
            audio.dispatchMediaKeyEvent(up)
            true
        } catch (t: Throwable) {
            log("sendMediaCommand failed: $t")
            false
        }
    }
    
    private fun doToggleFlashlight(context: Context?) {
        toggleFlashlight(context)
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
    
    private fun getVolumeUpAction() = getPreferenceString(PREF_VOLUME_UP_ACTION, "0").toIntOrNull() ?: ACTION_DEFAULT
    private fun getVolumeDownAction() = getPreferenceString(PREF_VOLUME_DOWN_ACTION, "0").toIntOrNull() ?: ACTION_DEFAULT
    
    companion object {
        private const val PHONE_WINDOW_MANAGER_CLASS = "com.android.server.policy.PhoneWindowManager"
        private const val PREF_VOLUME_UP_ACTION = "pref_volume_up_action"
        private const val PREF_VOLUME_DOWN_ACTION = "pref_volume_down_action"
        private const val VOLUME_LONG_PRESS_DELAY_MS = 350L
        
        const val ACTION_NONE = -1
        const val ACTION_DEFAULT = 0
        const val ACTION_TORCH = 1
        const val ACTION_PLAY_PAUSE = 5
        const val ACTION_NEXT = 6
        const val ACTION_PREV = 7
        
        @Volatile var runnableContext: Context? = null
        @Volatile var policyVolumeInstalled = false
        @Volatile var skipUpSent = false
        @Volatile var skipDownSent = false
        @Volatile var lastHandledVolumeEventTime: Long = -1L
        @Volatile var lastHandledVolumeEventAction: Int = -1
        @Volatile var lastHandledVolumeEventCode: Int = -1

        fun toggleFlashlight(context: Context?) {
            // Delegate to shared utility in BaseHook
            val ctx = context ?: runnableContext ?: getSystemContext()
            BaseHook.toggleFlashlight(ctx)
        }
    }
}
