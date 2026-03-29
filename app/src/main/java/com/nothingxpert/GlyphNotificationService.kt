package com.nothingxpert

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.nothing.ketchum.Common
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphException
import com.nothing.ketchum.GlyphFrame
import com.nothing.ketchum.GlyphManager
import com.nothingxpert.util.RootShell

/**
 * Listens for notifications and activates the corresponding Glyph LEDs
 * when the screen is off and the feature is enabled.
 *
 * Glyphs stay on until the notification is dismissed.
 * Uses root to auto-enable Glyph SDK debug mode.
 */
class GlyphNotificationService : NotificationListenerService() {

    companion object {
        private const val TAG = "GlyphNotifService"
        private const val DEBUG_ENABLE_CMD = "settings put global nt_glyph_interface_debug_enable 1"

        // Phone (1) channel constants.
        private const val CH_P1_A1 = 0
        private const val CH_P1_B1 = 1
        private val CH_P1_C1_TO_C4 = 2..5
        private const val CH_P1_E1 = 6
        private val CH_P1_D1_1_TO_D1_8 = 7..14

        // Phone (2) channel constants from Glyph$Code_22111.
        private const val CH_P2_A1 = 0
        private const val CH_P2_A2 = 1
        private const val CH_P2_B1 = 2
        private val CH_P2_C1 = 3..18
        private const val CH_P2_C2 = 19
        private const val CH_P2_C3 = 20
        private const val CH_P2_C4 = 21
        private const val CH_P2_C5 = 22
        private const val CH_P2_C6 = 23
        private const val CH_P2_E1 = 24
        private val CH_P2_D1_1_TO_D1_8 = 25..32

        @Volatile
        private var instance: GlyphNotificationService? = null

        fun setPickerPreviewZones(zones: Set<String>?) {
            instance?.setManualPreviewZones(zones)
        }

        fun clearPickerPreview() {
            instance?.setManualPreviewZones(null)
        }
    }

    private var glyphManager: GlyphManager? = null
    private var isSessionOpen = false
    private var isServiceConnected = false
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences

    // Track active notification keys → their glyph zones
    // When all are dismissed, glyphs turn off
    private val activeNotifications = mutableMapOf<String, Set<String>>()

    // Cache of package → zone set mappings for quick lookup
    @Volatile
    private var mappingsCache: Map<String, Set<String>>? = null

    // Temporary zones previewed from the picker dialog.
    @Volatile
    private var manualPreviewZones: Set<String>? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        instance = this

        try {
            prefs = getSharedPreferences(
                "${HookEntry.MODULE_PKG}_preferences",
                Context.MODE_PRIVATE
            )
        } catch (e: Exception) {
            prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        }

        // Enable Glyph debug mode via root
        enableDebugMode()

        // Initialize Glyph SDK
        initGlyphManager()
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        activeNotifications.clear()
        manualPreviewZones = null
        closeGlyphSession()
        glyphManager?.unInit()
        glyphManager = null
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    private fun enableDebugMode() {
        Thread {
            try {
                RootShell.exec(DEBUG_ENABLE_CMD)
                Log.d(TAG, "Glyph debug mode enabled via root")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to enable glyph debug mode: ${e.message}")
            }
        }.start()
    }

    private fun initGlyphManager() {
        try {
            glyphManager = GlyphManager.getInstance(applicationContext)
            glyphManager?.init(object : GlyphManager.Callback {
                override fun onServiceConnected(componentName: ComponentName?) {
                    Log.d(TAG, "GlyphManager service connected")
                    isServiceConnected = true
                    registerDevice()
                    refreshGlyphs()
                }

                override fun onServiceDisconnected(componentName: ComponentName?) {
                    Log.d(TAG, "GlyphManager service disconnected")
                    isServiceConnected = false
                    isSessionOpen = false
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init GlyphManager: ${e.message}")
        }
    }

    private fun registerDevice() {
        try {
            val gm = glyphManager ?: return
            when {
                Common.is20111() -> gm.register(Glyph.DEVICE_20111)
                Common.is22111() -> gm.register(Glyph.DEVICE_22111)
                Common.is23111() -> gm.register(Glyph.DEVICE_23111)
                Common.is23113() -> gm.register(Glyph.DEVICE_23113)
                Common.is24111() -> gm.register(Glyph.DEVICE_24111)
                else -> {
                    when (Build.DEVICE?.lowercase()) {
                        "spacewar" -> gm.register(Glyph.DEVICE_20111)
                        "pong" -> gm.register(Glyph.DEVICE_22111)
                        else -> Log.w(TAG, "Unknown device: ${Build.DEVICE}")
                    }
                }
            }
            openGlyphSession()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register device: ${e.message}")
        }
    }

    private fun openGlyphSession() {
        try {
            glyphManager?.openSession()
            isSessionOpen = true
            Log.d(TAG, "Glyph session opened")
        } catch (e: GlyphException) {
            Log.e(TAG, "Failed to open glyph session: ${e.message}")
            isSessionOpen = false
        }
    }

    private fun closeGlyphSession() {
        try {
            if (isSessionOpen) {
                glyphManager?.closeSession()
                isSessionOpen = false
                Log.d(TAG, "Glyph session closed")
            }
        } catch (e: GlyphException) {
            Log.e(TAG, "Failed to close glyph session: ${e.message}")
        }
    }

    // ════════════════════════════════════════
    //  Notification events
    // ════════════════════════════════════════

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        // Check if feature is enabled
        if (!prefs.getBoolean(GlyphNotifSettingsActivity.PREF_GLYPH_NOTIF_ENABLED, false)) return

        // Check if screen is off
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm?.isInteractive == true) return

        // Look up glyph mapping for this package
        val pkg = sbn.packageName ?: return
        val zones = getMappingForPackage(pkg) ?: return

        // Track this notification
        val key = sbn.key ?: return
        synchronized(activeNotifications) {
            activeNotifications[key] = zones
        }

        // Activate glyphs with all currently active zones merged
        refreshGlyphs()

        Log.d(TAG, "Notification posted: $pkg (key=$key), zones=$zones")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        val key = sbn.key ?: return

        val wasTracked: Boolean
        synchronized(activeNotifications) {
            wasTracked = activeNotifications.remove(key) != null
        }

        if (wasTracked) {
            refreshGlyphs()
            Log.d(TAG, "Notification dismissed: ${sbn.packageName} (key=$key)")
        }
    }

    /**
     * Rebuild the glyph frame from all active notifications and toggle or turn off.
     */
    private fun refreshGlyphs() {
        val allZones: Set<String>
        synchronized(activeNotifications) {
            val activeZones = activeNotifications.values.flatten()
            val previewZones = manualPreviewZones ?: emptySet()
            allZones = (activeZones + previewZones).toSet()
        }

        if (allZones.isEmpty()) {
            turnOffGlyphs()
        } else {
            activateGlyphs(allZones)
        }
    }

    // ════════════════════════════════════════
    //  Glyph control
    // ════════════════════════════════════════

    private fun getMappingForPackage(packageName: String): Set<String>? {
        var cache = mappingsCache
        if (cache == null) {
            cache = buildMappingsCache()
            mappingsCache = cache
        }
        return cache[packageName]
    }

    private fun buildMappingsCache(): Map<String, Set<String>> {
        val rawMappings = prefs.getStringSet(
            GlyphNotifSettingsActivity.PREF_GLYPH_NOTIF_MAPPINGS, emptySet()
        ) ?: emptySet()

        val result = mutableMapOf<String, Set<String>>()
        for (entry in rawMappings) {
            val parts = entry.split(":", limit = 2)
            if (parts.size == 2) {
                val pkg = parts[0]
                val zones = parts[1].split(",").filter { it.isNotBlank() }.toSet()
                result[pkg] = zones
            }
        }
        return result
    }

    fun invalidateMappingsCache() {
        mappingsCache = null
    }

    private fun setManualPreviewZones(zones: Set<String>?) {
        val normalized = zones?.toSet()?.filter { it.isNotBlank() }?.toSet()
        manualPreviewZones = if (normalized.isNullOrEmpty()) null else normalized
        refreshGlyphs()
    }

    private fun activateGlyphs(zones: Set<String>) {
        val gm = glyphManager ?: return
        if (!isServiceConnected) return

        if (!isSessionOpen) {
            try { RootShell.exec(DEBUG_ENABLE_CMD) } catch (_: Exception) {}
            openGlyphSession()
        }
        if (!isSessionOpen) return

        try {
            val builder = gm.glyphFrameBuilder
            val isPhone1 = resolvePhone1ChannelMode()

            for (zone in zones) {
                buildChannelsForZone(builder, zone, isPhone1)
            }

            val frame = builder.build()
            gm.toggle(frame)

            Log.d(TAG, "Glyphs activated for zones: $zones")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to activate glyphs: ${e.message}")
        }
    }

    private fun resolvePhone1ChannelMode(): Boolean {
        val physicalPhone1 = GlyphsActivity.isPhone1()
        val physicalPhone2 = GlyphsActivity.isPhone2()
        val uiPhone1 = GlyphsActivity.isPhone1ForGlyphUi(this)

        return when {
            // Phone (1) cannot safely emit Phone (2)-only channels.
            physicalPhone1 -> true
            // Phone (2) can test both channel semantics safely.
            physicalPhone2 -> uiPhone1
            else -> uiPhone1
        }
    }

    private fun buildChannelsForZone(builder: GlyphFrame.Builder, zone: String, isPhone1: Boolean) {
        if (isPhone1) {
            when (zone) {
                "CAMERA" -> builder.buildChannel(CH_P1_A1)
                "DIAGONAL" -> builder.buildChannel(CH_P1_B1)
                "BATTERY" -> {
                    for (ch in CH_P1_C1_TO_C4) builder.buildChannel(ch)
                }
                "CENTER" -> {
                    for (ch in CH_P1_D1_1_TO_D1_8) builder.buildChannel(ch)
                }
                "BOTTOM" -> builder.buildChannel(CH_P1_E1)
                else -> Log.w(TAG, "Unknown Phone(1) zone: $zone")
            }
        } else {
            when (zone) {
                "TOP_LEFT" -> builder.buildChannel(CH_P2_A1)
                "TOP_RIGHT" -> builder.buildChannel(CH_P2_A2)
                "CAMERA" -> builder.buildChannel(CH_P2_B1)
                "STRIP_LEFT" -> builder.buildChannel(CH_P2_C2)
                "STRIP_RIGHT" -> {
                    for (ch in CH_P2_C1) builder.buildChannel(ch)
                }
                "STRIP" -> {
                    // Backward compatibility for old saved mappings.
                    for (ch in CH_P2_C1) builder.buildChannel(ch)
                    builder.buildChannel(CH_P2_C2)
                }
                "CURVE_LEFT" -> builder.buildChannel(CH_P2_C3)
                "CURVE_BOTTOM_LEFT" -> builder.buildChannel(CH_P2_C4)
                "CURVE_BOTTOM_RIGHT" -> builder.buildChannel(CH_P2_C5)
                "CURVE_RIGHT" -> builder.buildChannel(CH_P2_C6)
                "CURVE" -> {
                    // Backward compatibility for old saved mappings.
                    builder.buildChannel(CH_P2_C2)
                    builder.buildChannel(CH_P2_C3)
                    builder.buildChannel(CH_P2_C4)
                    builder.buildChannel(CH_P2_C5)
                    builder.buildChannel(CH_P2_C6)
                }
                "USB" -> {
                    for (ch in CH_P2_D1_1_TO_D1_8) builder.buildChannel(ch)
                }
                "BOTTOM" -> builder.buildChannel(CH_P2_E1)
                else -> Log.w(TAG, "Unknown Phone(2) zone: $zone")
            }
        }
    }

    private fun turnOffGlyphs() {
        try {
            if (isSessionOpen) {
                glyphManager?.turnOff()
                Log.d(TAG, "Glyphs turned off")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to turn off glyphs: ${e.message}")
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Notification listener connected")
        invalidateMappingsCache()
    }
}
