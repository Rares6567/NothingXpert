package com.nothingxpert.hooks

import android.content.Context
import android.os.SystemClock
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.nothingxpert.XPrefs
import java.util.concurrent.atomic.AtomicBoolean

abstract class BaseHook {
    abstract val tag: String

    abstract fun install(lpparam: XC_LoadPackage.LoadPackageParam)
    
    protected fun log(message: String) {
        XposedBridge.log("NothingXpert/$tag: $message")
    }
    
    protected inline fun safeHook(description: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            log("$description failed: $t")
        }
    }
    
    companion object {
        const val MODULE_PKG = "com.nothingxpert"
        const val SYSTEMUI_PKG = "com.android.systemui"
        
        private const val PREF_CACHE_MS = 5_000L
        private const val XSP_CACHE_MS = 10_000L
        
        private val prefCache = HashMap<String, Pair<Long, Boolean>>()
        private val stringPrefCache = HashMap<String, Pair<Long, String>>()
        private val stringSetPrefCache = HashMap<String, Pair<Long, Set<String>>>()
        
        @Volatile private var cachedXsp: XSharedPreferences? = null
        @Volatile private var lastXspCheck: Long = 0L
        @Volatile var useRemotePrefs = false
        
        fun getXsp(): XSharedPreferences? {
            val now = SystemClock.uptimeMillis()
            val xsp = cachedXsp
            if (xsp != null && now - lastXspCheck < XSP_CACHE_MS) {
                if (xsp.hasFileChanged()) xsp.reload()
                return xsp
            }
            
            val paths = listOf(
                "/data/user_de/0/$MODULE_PKG/shared_prefs/${MODULE_PKG}_preferences.xml",
                "/data/data/$MODULE_PKG/shared_prefs/${MODULE_PKG}_preferences.xml"
            )
            
            for (path in paths) {
                try {
                    val file = java.io.File(path)
                    if (file.exists() && file.canRead()) {
                        val newXsp = XSharedPreferences(file)
                        newXsp.makeWorldReadable()
                        cachedXsp = newXsp
                        lastXspCheck = now
                        return newXsp
                    }
                } catch (_: Throwable) {}
            }
            
            return try {
                val newXsp = XSharedPreferences(MODULE_PKG, "${MODULE_PKG}_preferences")
                newXsp.makeWorldReadable()
                cachedXsp = newXsp
                lastXspCheck = now
                newXsp
            } catch (_: Throwable) { null }
        }
        
        fun getPreferenceBoolean(key: String, defValue: Boolean): Boolean {
            val now = SystemClock.uptimeMillis()
            
            synchronized(prefCache) {
                prefCache[key]?.let { (ts, v) ->
                    if (now - ts < PREF_CACHE_MS) return v
                }
            }

            val value = if (useRemotePrefs) {
                try { XPrefs.getBoolean(key, defValue) } catch (_: Throwable) { null }
            } else null
            
            val result = value ?: try {
                val xsp = getXsp()
                if (xsp?.contains(key) == true) xsp.getBoolean(key, defValue) else defValue
            } catch (_: Throwable) { defValue }

            synchronized(prefCache) {
                prefCache[key] = now to result
            }
            return result
        }
        
        fun getPreferenceString(key: String, defValue: String): String {
            val now = SystemClock.uptimeMillis()
            
            synchronized(stringPrefCache) {
                stringPrefCache[key]?.let { (ts, v) ->
                    if (now - ts < PREF_CACHE_MS) return v
                }
            }

            val result = if (useRemotePrefs) {
                try { XPrefs.getString(key, defValue) } catch (_: Throwable) { null }
            } else null
            
            val value = result ?: try {
                getXsp()?.getString(key, null)
            } catch (_: Throwable) { null } ?: defValue

            synchronized(stringPrefCache) {
                stringPrefCache[key] = now to value
            }
            return value
        }
        
        fun getPreferenceStringSet(key: String, defValue: Set<String> = emptySet()): Set<String> {
            val now = SystemClock.uptimeMillis()

            synchronized(stringSetPrefCache) {
                stringSetPrefCache[key]?.let { (ts, v) ->
                    if (now - ts < PREF_CACHE_MS) return v
                }
            }

            val result = if (useRemotePrefs) {
                try {
                    XPrefs.prefs?.getStringSet(key, defValue) ?: defValue
                } catch (_: Throwable) { defValue }
            } else {
                try {
                    getXsp()?.getStringSet(key, defValue) ?: defValue
                } catch (_: Throwable) { defValue }
            }

            synchronized(stringSetPrefCache) {
                stringSetPrefCache[key] = now to result
            }
            return result
        }
        
        fun clearCache(key: String? = null) {
            if (key != null) {
                synchronized(prefCache) { prefCache.remove(key) }
                synchronized(stringPrefCache) { stringPrefCache.remove(key) }
                synchronized(stringSetPrefCache) { stringSetPrefCache.remove(key) }
            } else {
                synchronized(prefCache) { prefCache.clear() }
                synchronized(stringPrefCache) { stringPrefCache.clear() }
                synchronized(stringSetPrefCache) { stringSetPrefCache.clear() }
            }
        }
        
        fun getSystemContext(): Context? {
            return try {
                val at = Class.forName("android.app.ActivityThread")
                val thread = de.robv.android.xposed.XposedHelpers.callStaticMethod(at, "currentActivityThread")
                de.robv.android.xposed.XposedHelpers.callMethod(thread, "getSystemContext") as? Context
            } catch (_: Throwable) { null }
        }
        
        fun currentApplication(): android.app.Application? {
            return try {
                val at = Class.forName("android.app.ActivityThread")
                val method = at.getMethod("currentApplication")
                method.invoke(null) as? android.app.Application
            } catch (_: Throwable) { null }
        }

        // Shared flashlight state and utility to avoid tight coupling between hooks
        private val flashlightState = AtomicBoolean(false)

        fun toggleFlashlight(context: Context?): Boolean {
            try {
                val ctx = context ?: getSystemContext()
                val cameraManager = ctx?.getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
                if (cameraManager == null) {
                    XposedBridge.log("NothingXpert: CameraManager is null")
                    return false
                }
                val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                    val characteristics = cameraManager.getCameraCharacteristics(id)
                    characteristics.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
                if (cameraId == null) {
                    XposedBridge.log("NothingXpert: No camera with flash found")
                    return false
                }
                val isOn = flashlightState.get()
                cameraManager.setTorchMode(cameraId, !isOn)
                flashlightState.set(!isOn)
                XposedBridge.log("NothingXpert: Flashlight toggled to ${!isOn}")
                return true
            } catch (t: Throwable) {
                XposedBridge.log("NothingXpert: toggleFlashlight failed: $t")
                return false
            }
        }

        fun isFlashlightOn(): Boolean = flashlightState.get()

        fun setFlashlightState(isOn: Boolean) = flashlightState.set(isOn)
    }
}
