package com.nothingxpert.hooks

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.nothingxpert.XPrefs

/**
 * Creates a floating window overlay that shows real-time system stats:
 * CPU, GPU, RAM usage and temperatures.
 */
class FloatingWindowHooks : BaseHook() {
    override val tag = "FloatingWindow"
    
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    
    override fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != SYSTEMUI_PKG) return
        
        safeHook("SystemUIApplication.onCreate") {
            XposedHelpers.findAndHookMethod(
                "com.android.systemui.SystemUIApplication",
                lpparam.classLoader,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val context = param.thisObject as? Context ?: return
                        systemUiContext = context.applicationContext
                        
                        // Start the floating window if enabled
                        handler.postDelayed({
                            initFloatingWindow()
                        }, 2000)
                    }
                }
            )
        }
    }
    
    private fun initFloatingWindow() {
        val ctx = systemUiContext ?: return
        if (!getPreferenceBoolean(PREF_FLOATING_WINDOW_ENABLED, false)) {
            log("Floating window disabled")
            return
        }
        
        if (floatingViewCreated) {
            log("Floating window already created")
            return
        }
        
        try {
            createFloatingWindow(ctx)
            floatingViewCreated = true
            log("Floating window created successfully")
        } catch (t: Throwable) {
            log("Failed to create floating window: $t")
        }
    }
    
    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingWindow(context: Context) {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        
        // Load Nothing font - try multiple approaches
        val nothingFont = loadNothingFont(context)
        currentFont = nothingFont
        
        // Create rounded background drawable
        val cornerRadius = dpToPx(context, 12).toFloat()
        val backgroundDrawable = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.parseColor("#80000000")) // 50% transparent black
            setCornerRadius(cornerRadius)
        }
        
        // Create the container layout - transparent background with rounded corners
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = backgroundDrawable
            setPadding(dpToPx(context, 12), dpToPx(context, 8), dpToPx(context, 12), dpToPx(context, 8))
        }
        
        // Create text views for stats
        val textColor = Color.WHITE
        val fontSize = getPreferenceString(PREF_FLOATING_WINDOW_SIZE, "small")
        val textSize = when (fontSize) {
            "large" -> 14f
            "medium" -> 12f
            else -> 10f
        }
        
        cpuTextView = createStatsTextView(context, textColor, textSize)
        gpuTextView = createStatsTextView(context, textColor, textSize)
        ramTextView = createStatsTextView(context, textColor, textSize)
        tempTextView = createStatsTextView(context, textColor, textSize)
        
        // Detailed views (hidden by default)
        detailTextView1 = createStatsTextView(context, textColor, textSize).apply { visibility = View.GONE }
        detailTextView2 = createStatsTextView(context, textColor, textSize).apply { visibility = View.GONE }
        detailTextView3 = createStatsTextView(context, textColor, textSize).apply { visibility = View.GONE }
        detailTextView4 = createStatsTextView(context, textColor, textSize).apply { visibility = View.GONE }
        detailTextView5 = createStatsTextView(context, textColor, textSize).apply { visibility = View.GONE }
        detailTextView6 = createStatsTextView(context, textColor, textSize).apply { visibility = View.GONE }
        detailTextView7 = createStatsTextView(context, textColor, textSize).apply { visibility = View.GONE }
        detailTextView8 = createStatsTextView(context, textColor, textSize).apply { visibility = View.GONE }
        
        container.addView(cpuTextView)
        container.addView(gpuTextView)
        container.addView(ramTextView)
        container.addView(tempTextView)
        container.addView(detailTextView1)
        container.addView(detailTextView2)
        container.addView(detailTextView3)
        container.addView(detailTextView4)
        container.addView(detailTextView5)
        container.addView(detailTextView6)
        container.addView(detailTextView7)
        container.addView(detailTextView8)
        
        // Window layout params
        val layoutParams = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(context, 16)
            y = dpToPx(context, 100)
        }
        
        // Make draggable and clickable
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        val clickThreshold = dpToPx(context, 10)
        
        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (kotlin.math.abs(dx) > clickThreshold || kotlin.math.abs(dy) > clickThreshold) {
                        isDragging = true
                    }
                    if (isDragging) {
                        layoutParams.x = initialX + dx.toInt()
                        layoutParams.y = initialY + dy.toInt()
                        try {
                            wm.updateViewLayout(container, layoutParams)
                        } catch (_: Throwable) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // It was a click - toggle expanded mode
                        toggleExpandedMode()
                    }
                    true
                }
                else -> false
            }
        }
        
        windowManager = wm
        floatingView = container
        windowLayoutParams = layoutParams
        
        try {
            wm.addView(container, layoutParams)
        } catch (t: Throwable) {
            log("Failed to add floating view: $t")
            return
        }
        
        // Start update loop
        startStatsUpdater()
    }
    
    private fun createStatsTextView(context: Context, color: Int, size: Float): TextView {
        return TextView(context).apply {
            setTextColor(color)
            textSize = size
            typeface = currentFont ?: Typeface.MONOSPACE
            setSingleLine(true)
            letterSpacing = 0.05f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }
    
    private fun startStatsUpdater() {
        val updateRunnable = object : Runnable {
            override fun run() {
                if (!getPreferenceBoolean(PREF_FLOATING_WINDOW_ENABLED, false)) {
                    hideFloatingWindow()
                    return
                }
                
                updateStats()
                handler.postDelayed(this, UPDATE_INTERVAL_MS)
            }
        }
        handler.post(updateRunnable)
    }
    
    private fun updateStats() {
        val ctx = systemUiContext ?: return
        
        // Update on background thread, post to UI
        Thread {
            // Read CPU from Settings.Global (written by SystemHooks CPU reporter)
            var cpuUsage: Int? = null
            var cpuTemp: Int? = null
            try {
                val usageStr = android.provider.Settings.Global.getString(ctx.contentResolver, "nothingxpert_cpu_usage")
                cpuUsage = usageStr?.toIntOrNull()
                val tempStr = android.provider.Settings.Global.getString(ctx.contentResolver, "nothingxpert_cpu_temp")
                cpuTemp = tempStr?.toIntOrNull()
            } catch (_: Throwable) { }
            
            // Fallback to direct read if Settings.Global doesn't have data
            if (cpuUsage == null) {
                cpuUsage = readCpuUsageDirect()
            }
            if (cpuTemp == null) {
                cpuTemp = readCpuTemp()
            }
            
            val gpuUsage = readGpuUsage()
            val gpuTemp = readGpuTemp()
            val ramInfo = readRamInfo(ctx)
            
            // Read detailed info if expanded
            var batteryTemp: Int? = null
            var batteryLevel: Int? = null
            var uptime = ""
            var cpuFreqs = ""
            var batteryCurrent = ""
            var batteryVoltage = ""
            var availRam = ""
            var swapInfo = ""
            
            if (isExpanded) {
                batteryTemp = readBatteryTemp()
                batteryLevel = readBatteryLevel(ctx)
                uptime = getUptime()
                cpuFreqs = getCpuFrequencies()
                batteryCurrent = getBatteryCurrent()
                batteryVoltage = getBatteryVoltage()
                availRam = getAvailableRam(ramInfo)
                swapInfo = getSwapInfo()
            }
            
            handler.post {
                cpuTextView?.text = formatCpuText(cpuUsage, cpuTemp)
                gpuTextView?.text = formatGpuText(gpuUsage, gpuTemp)
                ramTextView?.text = formatRamText(ramInfo)
                tempTextView?.text = formatTempText(cpuTemp, gpuTemp)
                
                if (isExpanded) {
                    detailTextView1?.text = "━━━━ BATTERY ━━━━"
                    detailTextView2?.text = "Level: ${batteryLevel ?: 0}%  Temp: ${batteryTemp ?: 0}°C"
                    detailTextView3?.text = "Current: $batteryCurrent  Volt: $batteryVoltage"
                    detailTextView4?.text = "━━━━ SYSTEM ━━━━"
                    detailTextView5?.text = "Uptime: $uptime  $swapInfo"
                    detailTextView6?.text = "Free RAM: $availRam"
                    detailTextView7?.text = cpuFreqs
                    detailTextView8?.text = "▲ Tap to collapse"
                }
            }
        }.start()
    }
    
    private fun toggleExpandedMode() {
        isExpanded = !isExpanded
        handler.post {
            val visibility = if (isExpanded) View.VISIBLE else View.GONE
            detailTextView1?.visibility = visibility
            detailTextView2?.visibility = visibility
            detailTextView3?.visibility = visibility
            detailTextView4?.visibility = visibility
            detailTextView5?.visibility = visibility
            detailTextView6?.visibility = visibility
            detailTextView7?.visibility = visibility
            detailTextView8?.visibility = visibility
        }
    }
    
    private fun getBatteryCurrent(): String {
        return try {
            val raw = readFile("/sys/class/power_supply/battery/current_now")?.trim()
            val uA = raw?.toLongOrNull() ?: return "--"
            val mA = kotlin.math.abs(uA / 1000)
            "${mA}mA"
        } catch (_: Throwable) { "--" }
    }
    
    private fun getBatteryVoltage(): String {
        return try {
            val raw = readFile("/sys/class/power_supply/battery/voltage_now")?.trim()
            val uV = raw?.toLongOrNull() ?: return "--"
            val v = uV / 1000000.0
            String.format("%.2fV", v)
        } catch (_: Throwable) { "--" }
    }
    
    private fun getAvailableRam(ramInfo: RamInfo?): String {
        return ramInfo?.usedFormatted ?: "--"
    }
    
    private fun getSwapInfo(): String {
        return try {
            val memInfo = readFile("/proc/meminfo") ?: return ""
            val lines = memInfo.lines()
            val swapTotal = lines.find { it.startsWith("SwapTotal:") }?.split("\\s+".toRegex())?.getOrNull(1)?.toLongOrNull() ?: 0
            val swapFree = lines.find { it.startsWith("SwapFree:") }?.split("\\s+".toRegex())?.getOrNull(1)?.toLongOrNull() ?: 0
            if (swapTotal <= 0) return "No Swap"
            val swapUsed = swapTotal - swapFree
            val usedMB = swapUsed / 1024
            val totalMB = swapTotal / 1024
            "Swap: ${usedMB}/${totalMB}MB"
        } catch (_: Throwable) { "" }
    }
    
    private fun readBatteryTemp(): Int? {
        return try {
            val raw = readFile("/sys/class/power_supply/battery/temp")?.trim()
            val v = raw?.toIntOrNull() ?: return null
            if (v > 100) v / 10 else v
        } catch (_: Throwable) { null }
    }
    
    private fun readBatteryLevel(context: Context): Int? {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
            bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (_: Throwable) { null }
    }
    
    private fun getUptime(): String {
        return try {
            val uptimeMs = android.os.SystemClock.elapsedRealtime()
            val hours = (uptimeMs / (1000 * 60 * 60)).toInt()
            val mins = ((uptimeMs / (1000 * 60)) % 60).toInt()
            "${hours}h ${mins}m"
        } catch (_: Throwable) { "--" }
    }
    
    private fun getCpuFrequencies(): String {
        return try {
            val freqs = mutableListOf<String>()
            for (cpu in 0..7) {
                val path = "/sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_cur_freq"
                val raw = readFile(path)?.trim()
                val khz = raw?.toLongOrNull()
                if (khz != null) {
                    val mhz = khz / 1000
                    freqs.add("$mhz")
                } else {
                    freqs.add("--")
                }
            }
            if (freqs.isEmpty()) "CPU Freq: --"
            else "CPU: ${freqs.joinToString(" ")} MHz"
        } catch (_: Throwable) { "CPU Freq: --" }
    }
    
    private fun formatCpuText(usage: Int?, temp: Int?): String {
        // Match MainActivity behavior: show 0% when null/unavailable
        val usageStr = "${usage ?: 0}%"
        return "CPU: $usageStr"
    }
    
    private fun formatGpuText(usage: Int?, temp: Int?): String {
        // Match MainActivity behavior: show 0% when null/unavailable
        val usageStr = "${usage ?: 0}%"
        return "GPU: $usageStr"
    }
    
    private fun formatRamText(info: RamInfo?): String {
        if (info == null) return "RAM: --"
        return "RAM: ${info.usedFormatted} / ${info.totalFormatted} (${info.percent}%)"
    }
    
    private fun formatTempText(cpuTemp: Int?, gpuTemp: Int?): String {
        val cpuStr = cpuTemp?.let { "${it}°C" } ?: "--°C"
        val gpuStr = gpuTemp?.let { "${it}°C" } ?: "--°C"
        return "Temp: C:$cpuStr G:$gpuStr"
    }
    
    private fun readCpuUsageDirect(): Int? {
        val line = try {
            java.io.File("/proc/stat").bufferedReader().useLines { seq ->
                seq.firstOrNull { it.startsWith("cpu ") }
            }
        } catch (_: Throwable) { null } ?: return lastCpuUsageValue.takeIf { it >= 0 }
        
        val parts = line.trim().split("\\s+".toRegex())
        if (parts.size < 5) return lastCpuUsageValue.takeIf { it >= 0 }
        
        val user = parts[1].toLongOrNull() ?: return lastCpuUsageValue.takeIf { it >= 0 }
        val nice = parts[2].toLongOrNull() ?: return lastCpuUsageValue.takeIf { it >= 0 }
        val system = parts[3].toLongOrNull() ?: return lastCpuUsageValue.takeIf { it >= 0 }
        val idle = parts[4].toLongOrNull() ?: return lastCpuUsageValue.takeIf { it >= 0 }
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
            return 0 // Return 0 for first read instead of null
        }
        
        val totalDiff = total - lastCpuTotal
        val idleDiff = idleAll - lastCpuIdle
        lastCpuTotal = total
        lastCpuIdle = idleAll
        
        if (totalDiff <= 0) return lastCpuUsageValue.takeIf { it >= 0 } ?: 0
        val usage = (((totalDiff - idleDiff).toDouble() / totalDiff) * 100).toInt().coerceIn(0, 100)
        lastCpuUsageValue = usage
        return usage
    }
    
    private fun readCpuTemp(): Int? {
        // Try specific thermal zones first
        val zonePaths = listOf(
            "/sys/class/thermal/thermal_zone25/temp",
            "/sys/class/thermal/thermal_zone26/temp",
            "/sys/class/thermal/thermal_zone27/temp",
            "/sys/class/thermal/thermal_zone28/temp",
            "/sys/class/thermal/thermal_zone29/temp",
            "/sys/class/thermal/thermal_zone30/temp",
            "/sys/class/thermal/thermal_zone31/temp",
            "/sys/class/thermal/thermal_zone32/temp"
        )
        
        var best: Int? = null
        for (path in zonePaths) {
            val raw = readFile(path)?.trim() ?: continue
            val v = raw.toIntOrNull() ?: continue
            val c = if (v > 1000 || v < -1000) v / 1000 else v
            if (c in 0..120) {
                best = maxOf(best ?: c, c)
            }
        }
        if (best != null) return best
        
        // Fallback: search for cpu/cpuss/soc thermal zones
        for (i in 0..120) {
            val type = readFile("/sys/class/thermal/thermal_zone$i/type")?.trim()?.lowercase() ?: continue
            if (type.contains("cpu") || type.contains("cpuss") || type.contains("soc")) {
                val raw = readFile("/sys/class/thermal/thermal_zone$i/temp")?.trim() ?: continue
                val v = raw.toIntOrNull() ?: continue
                val c = if (v > 1000 || v < -1000) v / 1000 else v
                if (c in 0..120) return c
            }
        }
        return null
    }
    
    private fun readGpuUsage(): Int? {
        // Adreno gpubusy
        val busyLine = readFile("/sys/class/kgsl/kgsl-3d0/gpubusy")
        if (busyLine != null) {
            val nums = busyLine.trim().split("\\s+".toRegex())
            if (nums.size >= 2) {
                val busy = nums[0].toLongOrNull() ?: return null
                val total = nums[1].toLongOrNull() ?: return null
                if (total > 0) return ((busy.toDouble() / total) * 100).toInt().coerceIn(0, 100)
            }
        }
        
        // Alternative path
        val pct = readFile("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage")?.trim()?.toIntOrNull()
        if (pct != null) return pct.coerceIn(0, 100)
        
        return null
    }
    
    private fun readGpuTemp(): Int? {
        // kgsl temp
        val rawKgsl = readFile("/sys/class/kgsl/kgsl-3d0/temp")?.trim()
        val kgslValue = rawKgsl?.toIntOrNull()
        if (kgslValue != null) {
            return if (kgslValue > 1000) kgslValue / 1000 else kgslValue
        }
        
        // Fallback: search for gpu thermal zones
        for (i in 0..120) {
            val type = readFile("/sys/class/thermal/thermal_zone$i/type")?.trim()?.lowercase() ?: continue
            if (type.contains("gpu") || type.contains("gpuss")) {
                val raw = readFile("/sys/class/thermal/thermal_zone$i/temp")?.trim() ?: continue
                val v = raw.toIntOrNull() ?: continue
                val c = if (v > 1000 || v < -1000) v / 1000 else v
                if (c in 0..120) return c
            }
        }
        return null
    }
    
    private fun readRamInfo(context: Context): RamInfo? {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            
            val total = info.totalMem
            val avail = info.availMem
            val used = total - avail
            val percent = if (total > 0) ((used.toDouble() / total) * 100).toInt() else 0
            
            RamInfo(
                used = used,
                total = total,
                percent = percent,
                usedFormatted = Formatter.formatShortFileSize(context, used),
                totalFormatted = Formatter.formatShortFileSize(context, total)
            )
        } catch (_: Throwable) {
            null
        }
    }
    
    private fun readFile(path: String): String? {
        return try {
            java.io.File(path).takeIf { it.exists() && it.canRead() }?.readText()
        } catch (_: Throwable) {
            null
        }
    }
    
    private fun loadNothingFont(context: Context): Typeface {
        // Try loading NDot57 font from various locations
        
        // 1. Try loading from our module's APK resources
        try {
            val moduleContext = context.createPackageContext(
                "com.nothingxpert",
                Context.CONTEXT_IGNORE_SECURITY
            )
            val fontId = moduleContext.resources.getIdentifier("ndot57", "font", "com.nothingxpert")
            if (fontId != 0) {
                val font = androidx.core.content.res.ResourcesCompat.getFont(moduleContext, fontId)
                if (font != null) {
                    log("Loaded ndot57 from module resources")
                    return font
                }
            }
        } catch (t: Throwable) {
            log("Failed to load font from module: $t")
        }
        
        // 2. Try system fonts directory (Nothing OS installs fonts here)
        val systemFontPaths = listOf(
            "/system/fonts/NDot57.ttf",
            "/system/fonts/ndot57.ttf",
            "/system/fonts/NDot57.otf",
            "/system/fonts/ndot57.otf",
            "/system/fonts/NDot-57.ttf",
            "/system_ext/fonts/NDot57.ttf",
            "/product/fonts/NDot57.ttf"
        )
        
        for (path in systemFontPaths) {
            try {
                val file = java.io.File(path)
                if (file.exists() && file.canRead()) {
                    log("Loaded font from $path")
                    return Typeface.createFromFile(file)
                }
            } catch (_: Throwable) {}
        }
        
        // 3. Try Typeface.create with font family name (if registered in system)
        try {
            val font = Typeface.create("ndot57", Typeface.NORMAL)
            if (font != Typeface.DEFAULT && font != Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)) {
                log("Loaded ndot57 from system font family")
                return font
            }
        } catch (_: Throwable) {}
        
        // Fallback to monospace
        log("Using fallback monospace font")
        return Typeface.MONOSPACE
    }
    
    private fun dpToPx(context: Context, dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
    
    private fun hideFloatingWindow() {
        try {
            floatingView?.let { view ->
                windowManager?.removeView(view)
            }
            floatingView = null
            floatingViewCreated = false
        } catch (_: Throwable) {}
    }
    
    data class RamInfo(
        val used: Long,
        val total: Long,
        val percent: Int,
        val usedFormatted: String,
        val totalFormatted: String
    )
    
    companion object {
        const val PREF_FLOATING_WINDOW_ENABLED = "pref_floating_window_enabled"
        const val PREF_FLOATING_WINDOW_SIZE = "pref_floating_window_size"
        
        private const val UPDATE_INTERVAL_MS = 1000L
        
        @Volatile private var systemUiContext: Context? = null
        @Volatile private var floatingViewCreated = false
        @Volatile private var floatingView: View? = null
        @Volatile private var windowManager: WindowManager? = null
        @Volatile private var windowLayoutParams: WindowManager.LayoutParams? = null
        
        @Volatile private var cpuTextView: TextView? = null
        @Volatile private var gpuTextView: TextView? = null
        @Volatile private var ramTextView: TextView? = null
        @Volatile private var tempTextView: TextView? = null
        
        @Volatile private var detailTextView1: TextView? = null
        @Volatile private var detailTextView2: TextView? = null
        @Volatile private var detailTextView3: TextView? = null
        @Volatile private var detailTextView4: TextView? = null
        @Volatile private var detailTextView5: TextView? = null
        @Volatile private var detailTextView6: TextView? = null
        @Volatile private var detailTextView7: TextView? = null
        @Volatile private var detailTextView8: TextView? = null
        
        @Volatile private var isExpanded = false
        
        @Volatile private var lastCpuTotal: Long = -1L
        @Volatile private var lastCpuIdle: Long = -1L
        @Volatile private var lastCpuUsageValue: Int = 0
        @Volatile private var currentFont: Typeface? = null
        
        fun toggleFloatingWindow() {
            if (floatingViewCreated) {
                try {
                    floatingView?.let { windowManager?.removeView(it) }
                    floatingView = null
                    floatingViewCreated = false
                } catch (_: Throwable) {}
            }
        }
        
        fun refreshFromPrefs() {
            val mainHandler = Handler(Looper.getMainLooper())
            mainHandler.post {
                val enabled = BaseHook.getPreferenceBoolean(PREF_FLOATING_WINDOW_ENABLED, false)
                if (!enabled) {
                    // Disable: remove the window
                    toggleFloatingWindow()
                } else if (!floatingViewCreated) {
                    // Enable: create the window if not already created
                    val ctx = systemUiContext ?: return@post
                    try {
                        FloatingWindowHooks().createFloatingWindow(ctx)
                        floatingViewCreated = true
                    } catch (_: Throwable) {}
                }
            }
        }
    }
}
