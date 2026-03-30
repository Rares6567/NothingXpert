package com.nothingxpert

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.nothing.ketchum.Common
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphException
import com.nothing.ketchum.GlyphFrame
import com.nothing.ketchum.GlyphManager
import com.nothingxpert.util.RootShell
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Listens for notifications and activates the corresponding Glyph LEDs
 * when the screen is off and the feature is enabled.
 *
 * Glyphs stay on until the notification is dismissed.
 * Uses root to temporarily toggle Glyph SDK debug mode while actively controlling Glyphs.
 */
class GlyphNotificationService : NotificationListenerService() {

    companion object {
        private const val TAG = "GlyphNotifService"
        private const val KETCHUM_PKG = "com.nothing.ketchum"
        private const val DEBUG_ENABLE_CMD = "settings put global nt_glyph_interface_debug_enable 1"
        private const val DEBUG_DISABLE_CMD = "settings put global nt_glyph_interface_debug_enable 0"
        private const val ESSENTIAL_LIST_CMD = "dumpsys notification --noredact | grep 'AppSettings:' | grep 'essential=true'"
        private const val ESSENTIAL_REFRESH_INTERVAL_MS = 15 * 60 * 1000L
        private const val ESSENTIAL_SYNC_REFRESH_TIMEOUT_MS = 8_000L
        private const val ESSENTIAL_IN_FLIGHT_WAIT_MS = 1_500L
        private const val ESSENTIAL_SYNC_RETRY_WINDOW_MS = 5_000L

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

    // Track active Essential notification keys separately.
    // These do not directly drive custom frames unless another custom notification is active.
    private val activeEssentialNotifications = mutableSetOf<String>()

    // Cache of package → zone set mappings for quick lookup
    @Volatile
    private var mappingsCache: Map<String, Set<String>>? = null

    // Package label cache used for substitute app-name fallback matching.
    private val appLabelCache = mutableMapOf<String, String>()

    // Temporary zones previewed from the picker dialog.
    @Volatile
    private var manualPreviewZones: Set<String>? = null

    @Volatile
    private var essentialPackages: Set<String> = emptySet()

    @Volatile
    private var lastEssentialPackagesRefreshMs: Long = 0L

    @Volatile
    private var lastEssentialSyncAttemptMs: Long = 0L

    private val essentialRefreshInFlight = AtomicBoolean(false)

    private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            GlyphNotifSettingsActivity.PREF_GLYPH_NOTIF_MAPPINGS -> {
                invalidateMappingsCache()
                synchronized(activeNotifications) {
                    activeNotifications.clear()
                    activeEssentialNotifications.clear()
                }
                refreshGlyphs()
            }
            GlyphNotifSettingsActivity.PREF_GLYPH_NOTIF_ENABLED -> {
                if (!prefs.getBoolean(GlyphNotifSettingsActivity.PREF_GLYPH_NOTIF_ENABLED, false)) {
                    synchronized(activeNotifications) {
                        activeNotifications.clear()
                        activeEssentialNotifications.clear()
                    }
                    refreshGlyphs()
                }
            }
        }
    }

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

        // Clear any stale debug state at startup. We enable it lazily only while a session is active.
        disableDebugModeAsync()

        // Initialize Glyph SDK
        initGlyphManager()

        refreshEssentialPackagesAsync(force = true)

        try {
            prefs.registerOnSharedPreferenceChangeListener(prefChangeListener)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        try {
            prefs.unregisterOnSharedPreferenceChangeListener(prefChangeListener)
        } catch (_: Exception) {}
        activeNotifications.clear()
        activeEssentialNotifications.clear()
        manualPreviewZones = null
        closeGlyphSession()
        glyphManager?.unInit()
        glyphManager = null
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    private fun enableDebugModeSync() {
        try {
            val res = RootShell.exec(DEBUG_ENABLE_CMD)
            if (res?.exitCode == 0) {
                Log.d(TAG, "Glyph debug mode enabled via root")
            } else {
                Log.w(TAG, "Failed to enable glyph debug mode")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enable glyph debug mode: ${e.message}")
        }
    }

    private fun disableDebugModeAsync() {
        Thread {
            try {
                val res = RootShell.exec(DEBUG_DISABLE_CMD)
                if (res?.exitCode == 0) {
                    Log.d(TAG, "Glyph debug mode disabled via root")
                } else {
                    Log.w(TAG, "Failed to disable glyph debug mode")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to disable glyph debug mode: ${e.message}")
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
        } finally {
            // Do not keep Ketchum debug mode enabled while idle.
            disableDebugModeAsync()
        }
    }

    // ════════════════════════════════════════
    //  Notification events
    // ════════════════════════════════════════

    private fun suppressKetchumNotificationIfNeeded(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName != KETCHUM_PKG) return false

        val key = sbn.key ?: return false
        return try {
            cancelNotification(key)
            Log.d(TAG, "Suppressed Ketchum notification: key=$key")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to suppress Ketchum notification: ${t.message}")
            false
        }
    }

    private fun suppressExistingKetchumNotifications() {
        val active = try {
            super.getActiveNotifications()
        } catch (_: Exception) {
            null
        } ?: return

        for (sbn in active) {
            suppressKetchumNotificationIfNeeded(sbn)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        // Hide Ketchum foreground debug cards from the shade.
        if (suppressKetchumNotificationIfNeeded(sbn)) return

        // Check if feature is enabled
        if (!prefs.getBoolean(GlyphNotifSettingsActivity.PREF_GLYPH_NOTIF_ENABLED, false)) return

        // Track screen state. Non-essential custom lighting is only when screen is off,
        // but essential tracking should still work while screen is on.
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isScreenOn = pm?.isInteractive == true

        // Resolve mapped zones, including delegated/reposted notifications.
        val resolved = resolveMappedZones(sbn)
        if (resolved == null) {
            val opPkg = try {
                sbn.opPkg
            } catch (_: Throwable) {
                null
            }
            Log.d(
                TAG,
                "No mapping match for notif: host=${sbn.packageName} opPkg=$opPkg key=${sbn.key}"
            )
            return
        }
        val mappedPackage = resolved.first
        val key = sbn.key ?: return

        // Track Essential notifications so we can yield Glyph control to the system
        // while Essential is active.
        if (isEssentialPackage(mappedPackage)) {
            synchronized(activeNotifications) {
                activeEssentialNotifications.add(key)
            }
            if (!isScreenOn) {
                refreshGlyphs()
            }
            Log.d(TAG, "Essential notification tracked: pkg=$mappedPackage key=$key screenOn=$isScreenOn")
            return
        }

        if (isScreenOn) {
            Log.d(TAG, "Skip notification while screen on: host=${sbn.packageName} key=${sbn.key}")
            return
        }

        val zones = resolved.second

        // Track this notification
        synchronized(activeNotifications) {
            activeNotifications[key] = zones
        }

        // If we missed an Essential post event (e.g., arrived while screen was on before tracking),
        // recover currently active Essential notifications from the system snapshot.
        if (activeEssentialNotifications.isEmpty()) {
            syncActiveEssentialNotificationsFromSystem()
        }

        // Activate glyphs with all currently active zones merged
        refreshGlyphs()

        Log.d(
            TAG,
            "Notification posted: host=${sbn.packageName}, mapped=$mappedPackage (key=$key), zones=$zones"
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        val key = sbn.key ?: return

        val wasTrackedCustom: Boolean
        val wasTrackedEssential: Boolean
        synchronized(activeNotifications) {
            wasTrackedCustom = activeNotifications.remove(key) != null
            wasTrackedEssential = activeEssentialNotifications.remove(key)
        }

        if (wasTrackedCustom || wasTrackedEssential) {
            refreshGlyphs()
            Log.d(
                TAG,
                "Notification dismissed: ${sbn.packageName} (key=$key), custom=$wasTrackedCustom essential=$wasTrackedEssential"
            )
        }
    }

    /**
     * Rebuild glyph frame from active mapped notifications.
     * Essential notifications are represented as a fixed indicator overlay (B1 / CAMERA).
     */
    private fun refreshGlyphs() {
        syncActiveEssentialNotificationsFromSystem()

        val allZones: Set<String>
        synchronized(activeNotifications) {
            val activeZones = activeNotifications.values.flatten()
            val previewZones = manualPreviewZones ?: emptySet()
            val merged = (activeZones + previewZones).toMutableSet()
            if (activeEssentialNotifications.isNotEmpty()) {
                merged.add(essentialOverlayZone())
            }
            allZones = merged.toSet()
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

    private fun resolveMappedZones(sbn: StatusBarNotification): Pair<String, Set<String>>? {
        val candidates = linkedSetOf<String>()

        val hostPkg = sbn.packageName
        if (!hostPkg.isNullOrBlank()) candidates.add(hostPkg)

        val opPkg = try {
            sbn.opPkg
        } catch (_: Throwable) {
            null
        }
        if (!opPkg.isNullOrBlank()) candidates.add(opPkg)

        val uid = try {
            sbn.uid
        } catch (_: Throwable) {
            -1
        }
        if (uid > 0) {
            val uidPackages = try {
                packageManager.getPackagesForUid(uid)?.toList().orEmpty()
            } catch (_: Throwable) {
                emptyList()
            }
            candidates.addAll(uidPackages)
        }

        val extras = sbn.notification?.extras
        val extraPackageKeys = arrayOf(
            "android.originatingPackage",
            "android.sourcePackage",
            "sourcePackage",
            "source_package",
            "originatingPackage",
            "originating_package",
            "package",
            "pkg"
        )

        if (extras != null) {
            for (key in extraPackageKeys) {
                val maybePkg = extras.getString(key)
                if (!maybePkg.isNullOrBlank()) {
                    candidates.add(maybePkg)
                }
            }
        }

        for (candidate in candidates) {
            val zones = getMappingForPackage(candidate)
            if (zones != null) return candidate to zones
        }

        // If the source is marked as Essential but not explicitly mapped,
        // still route it so refreshGlyphs can add the Essential overlay indicator.
        val essentialCandidate = candidates.firstOrNull { isEssentialPackage(it) }
        if (!essentialCandidate.isNullOrBlank()) {
            return essentialCandidate to (getMappingForPackage(essentialCandidate) ?: emptySet())
        }

        val substituteAppName = (
            extras?.getCharSequence("android.substName")
                ?: extras?.getCharSequence("android.substituteAppName")
            )?.toString()?.trim()

        if (!substituteAppName.isNullOrEmpty()) {
            val cache = mappingsCache ?: buildMappingsCache().also { mappingsCache = it }
            for ((pkg, zones) in cache) {
                val label = resolveAppLabel(pkg) ?: continue
                if (label.equals(substituteAppName, ignoreCase = true)) {
                    return pkg to zones
                }
            }

            val essentialByLabel = essentialPackages.firstOrNull { pkg ->
                resolveAppLabel(pkg)?.equals(substituteAppName, ignoreCase = true) == true
            }
            if (!essentialByLabel.isNullOrBlank()) {
                return essentialByLabel to (getMappingForPackage(essentialByLabel) ?: emptySet())
            }
        }

        if (hostPkg == "android" || hostPkg?.startsWith("com.nothing") == true) {
            Log.d(
                TAG,
                "No mapping for delegated notif: host=$hostPkg opPkg=$opPkg candidates=$candidates subApp=$substituteAppName"
            )
        }

        return null
    }

    private fun essentialOverlayZone(): String {
        // Phone (2): B1 is CAMERA. Phone (1) also uses CAMERA as the closest equivalent segment.
        return "CAMERA"
    }

    private fun waitForEssentialRefreshInFlight(maxWaitMs: Long) {
        if (!essentialRefreshInFlight.get()) return

        val deadline = SystemClock.elapsedRealtime() + maxWaitMs
        while (essentialRefreshInFlight.get() && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(50L)
        }
    }

    private fun refreshEssentialPackagesSync(timeoutMs: Long = ESSENTIAL_SYNC_REFRESH_TIMEOUT_MS): Boolean {
        if (!essentialRefreshInFlight.compareAndSet(false, true)) return false

        return try {
            val res = RootShell.exec(ESSENTIAL_LIST_CMD, timeoutMs = timeoutMs)
            if (res != null) {
                essentialPackages = parseEssentialPackages(res.stdout)
                lastEssentialPackagesRefreshMs = SystemClock.elapsedRealtime()
                Log.d(TAG, "Essential package cache refreshed (sync): ${essentialPackages.size}")
                true
            } else {
                Log.w(TAG, "Failed to refresh essential package cache (sync)")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Essential package refresh error (sync): ${e.message}")
            false
        } finally {
            essentialRefreshInFlight.set(false)
        }
    }

    private fun isEssentialPackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false

        val cached = essentialPackages
        if (cached.contains(packageName)) return true

        if (cached.isEmpty()) {
            // Startup race: avoid missing essential overlay while async warmup is still running.
            waitForEssentialRefreshInFlight(ESSENTIAL_IN_FLIGHT_WAIT_MS)
            if (essentialPackages.contains(packageName)) return true

            val now = SystemClock.elapsedRealtime()
            if (now - lastEssentialSyncAttemptMs >= ESSENTIAL_SYNC_RETRY_WINDOW_MS) {
                lastEssentialSyncAttemptMs = now
                if (refreshEssentialPackagesSync()) {
                    if (essentialPackages.contains(packageName)) return true
                }
            }

            refreshEssentialPackagesAsync(force = true)
            return false
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastEssentialPackagesRefreshMs > ESSENTIAL_REFRESH_INTERVAL_MS) {
            refreshEssentialPackagesAsync(force = false)
        }
        return false
    }

    private fun refreshEssentialPackagesAsync(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastEssentialPackagesRefreshMs <= ESSENTIAL_REFRESH_INTERVAL_MS) return
        if (!essentialRefreshInFlight.compareAndSet(false, true)) return

        Thread {
            try {
                val res = RootShell.exec(ESSENTIAL_LIST_CMD, timeoutMs = 12_000L)
                if (res != null) {
                    essentialPackages = parseEssentialPackages(res.stdout)
                    lastEssentialPackagesRefreshMs = SystemClock.elapsedRealtime()
                    Log.d(TAG, "Essential package cache refreshed: ${essentialPackages.size}")
                } else {
                    Log.w(TAG, "Failed to refresh essential package cache")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Essential package refresh error: ${e.message}")
            } finally {
                essentialRefreshInFlight.set(false)
            }
        }.start()
    }

    private fun parseEssentialPackages(raw: String): Set<String> {
        if (raw.isBlank()) return emptySet()

        val regex = Regex("""AppSettings:\s+([A-Za-z0-9._]+)\s+\([0-9]+\).*?\bessential=true\b""")
        val result = linkedSetOf<String>()

        for (line in raw.lineSequence()) {
            val pkg = regex.find(line)?.groupValues?.getOrNull(1)
            if (!pkg.isNullOrBlank()) result.add(pkg)
        }

        return result
    }

    private fun syncActiveEssentialNotificationsFromSystem() {
        val sbns = try {
            super.getActiveNotifications()
        } catch (_: Exception) {
            null
        } ?: return

        val essentialKeys = linkedSetOf<String>()
        for (sbn in sbns) {
            val resolved = resolveMappedZones(sbn) ?: continue
            if (isEssentialPackage(resolved.first)) {
                val key = sbn.key
                if (!key.isNullOrBlank()) essentialKeys.add(key)
            }
        }

        synchronized(activeNotifications) {
            activeEssentialNotifications.clear()
            activeEssentialNotifications.addAll(essentialKeys)
        }

        if (essentialKeys.isNotEmpty()) {
            Log.d(TAG, "Synced active essential notifications: ${essentialKeys.size}")
        }
    }

    private fun resolveAppLabel(packageName: String): String? {
        appLabelCache[packageName]?.let { return it }

        return try {
            val ai = packageManager.getApplicationInfo(packageName, 0)
            val label = packageManager.getApplicationLabel(ai).toString()
            appLabelCache[packageName] = label
            label
        } catch (_: Exception) {
            null
        }
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
        appLabelCache.clear()
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
            enableDebugModeSync()
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
        } finally {
            // Release SDK control when idle so Nothing OS features (e.g. music visualizer)
            // can acquire the Glyph session.
            closeGlyphSession()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Notification listener connected")
        invalidateMappingsCache()
        handler.post {
            suppressExistingKetchumNotifications()
            syncActiveEssentialNotificationsFromSystem()
            refreshGlyphs()
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "Notification listener disconnected")
        synchronized(activeNotifications) {
            activeNotifications.clear()
            activeEssentialNotifications.clear()
        }
        manualPreviewZones = null
        turnOffGlyphs()
    }
}
