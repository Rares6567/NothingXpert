package com.nothingxpert.hooks

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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
import com.nothingxpert.util.RootShell
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

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

        // Track the active instance so screen on/off receiver can pause/resume without creating new objects.
        updaterInstance = this
        
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

        // New views: invalidate UI caches so the first update always populates text.
        lastCpuText = null
        lastGpuText = null
        lastRamText = null
        lastTempText = null
        lastDetail1 = null
        lastDetail2 = null
        lastDetail3 = null
        lastDetail4 = null
        lastDetail5 = null
        lastDetail6 = null
        lastDetail7 = null
        lastDetail8 = null
        
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
        var lastUpdateTime = 0L
        val clickThreshold = dpToPx(context, 10)
        val updateThrottleMs = 16L // ~60fps max
        
        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    lastUpdateTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (kotlin.math.abs(dx) > clickThreshold || kotlin.math.abs(dy) > clickThreshold) {
                        isDragging = true
                        isDraggingWindow = true // Pause stats updates
                    }
                    if (isDragging) {
                        val now = System.currentTimeMillis()
                        // Throttle updates to reduce lag
                        if (now - lastUpdateTime >= updateThrottleMs) {
                            layoutParams.x = initialX + dx.toInt()
                            layoutParams.y = initialY + dy.toInt()
                            try {
                                wm.updateViewLayout(container, layoutParams)
                            } catch (_: Throwable) {}
                            lastUpdateTime = now
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    isDraggingWindow = false // Resume stats updates
                    if (isDragging) {
                        // Final position update on release
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        layoutParams.x = initialX + dx.toInt()
                        layoutParams.y = initialY + dy.toInt()
                        try {
                            wm.updateViewLayout(container, layoutParams)
                        } catch (_: Throwable) {}
                    } else {
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
        updaterInstance = this
        if (statsUpdaterRunning) return
        ensureScreenReceiver()
        statsUpdaterRunning = true
        // Avoid stacking callbacks if we pause/resume quickly.
        handler.removeCallbacks(statsUpdaterRunnable)
        handler.post(statsUpdaterRunnable)
    }

    private fun stopStatsUpdater() {
        statsUpdaterRunning = false
        handler.removeCallbacks(statsUpdaterRunnable)
    }

    private val statsUpdaterRunnable = object : Runnable {
        override fun run() {
            if (!statsUpdaterRunning) return
            if (!getPreferenceBoolean(PREF_FLOATING_WINDOW_ENABLED, false)) {
                stopStatsUpdater()
                hideFloatingWindow()
                return
            }

            val ctx = systemUiContext
            val pm = ctx?.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val interactive = pm?.isInteractive ?: true

            if (!interactive) {
                // Screen off/dozing: pause updates. Screen receiver will restart us.
                stopStatsUpdater()
                return
            }

            // Skip updates while dragging to reduce lag
            if (!isDraggingWindow) {
                scheduleStatsUpdate()
            }

            val delay = when {
                isExpanded -> UPDATE_INTERVAL_EXPANDED_MS
                lowActivityStreak >= LOW_ACTIVITY_STREAK_FOR_IDLE -> UPDATE_INTERVAL_IDLE_MS
                else -> UPDATE_INTERVAL_COLLAPSED_MS
            }
            handler.postDelayed(this, delay)
        }
    }

    private fun scheduleStatsUpdate() {
        // Avoid spawning a new Thread each tick; reuse a single worker and drop frames if it lags.
        if (!statsUpdateInFlight.compareAndSet(false, true)) return
        try {
            statsExecutor.execute {
                try {
                    performStatsUpdate()
                } finally {
                    statsUpdateInFlight.set(false)
                }
            }
        } catch (_: RejectedExecutionException) {
            statsUpdateInFlight.set(false)
        }
    }
    
    private fun performStatsUpdate() {
        val ctx = systemUiContext ?: return
        
        val now = android.os.SystemClock.elapsedRealtime()

        // Fast path: usage values.
        val cpuUsage = readCpuUsageDirect()
        val gpuUsage = readGpuUsage()
        val cpuPct = (cpuUsage ?: 0).coerceIn(0, 100)
        val gpuPct = (gpuUsage ?: 0).coerceIn(0, 100)

        // Allow the scheduler to slow down when the device is basically idle.
        if (!isExpanded && cpuPct <= LOW_ACTIVITY_PCT && gpuPct <= LOW_ACTIVITY_PCT) {
            lowActivityStreak = (lowActivityStreak + 1).coerceAtMost(1_000)
        } else {
            lowActivityStreak = 0
        }

        // Slow path: temps + RAM (throttled).
        if (now - lastSlowReadTs >= SLOW_READ_INTERVAL_MS) {
            lastSlowReadTs = now
            cachedCpuTemp = readCpuTemp()
            cachedGpuTemp = readGpuTemp()
            cachedRamInfo = readRamInfo(ctx)
        }
        val cpuTemp = cachedCpuTemp
        val gpuTemp = cachedGpuTemp
        val ramInfo = cachedRamInfo
        
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
            // Expanded details can be expensive (lots of /sys reads). Throttle them more aggressively.
            if (now - lastDetailReadTs >= DETAIL_READ_INTERVAL_MS) {
                lastDetailReadTs = now
                cachedBatteryTemp = readBatteryTemp()
                cachedBatteryLevel = readBatteryLevel(ctx)
                cachedUptime = getUptime()
                cachedCpuFreqs = getCpuFrequencies()
                cachedBatteryCurrent = getBatteryCurrent()
                cachedBatteryVoltage = getBatteryVoltage()
                cachedSwapInfo = getSwapInfo()
            }
            cachedAvailRam = getAvailableRam(ramInfo)

            batteryTemp = cachedBatteryTemp
            batteryLevel = cachedBatteryLevel
            uptime = cachedUptime
            cpuFreqs = cachedCpuFreqs
            batteryCurrent = cachedBatteryCurrent
            batteryVoltage = cachedBatteryVoltage
            availRam = cachedAvailRam
            swapInfo = cachedSwapInfo
        }

        val cpuText = formatCpuText(cpuUsage)
        val gpuText = formatGpuText(gpuUsage)
        val ramText = formatRamText(ramInfo)
        val tempText = formatTempText(cpuTemp, gpuTemp)

        // Only create detail strings when expanded to reduce allocations
        val d1: String
        val d2: String
        val d3: String
        val d4: String
        val d5: String
        val d6: String
        val d7: String
        val d8: String

        if (isExpanded) {
            d1 = "━━━━ BATTERY ━━━━"
            stringBuilder.clear()
            stringBuilder.append("Level: ").append(batteryLevel ?: 0).append("%  Temp: ").append(batteryTemp ?: 0).append("°C")
            d2 = stringBuilder.toString()

            stringBuilder.clear()
            stringBuilder.append("Current: ").append(batteryCurrent).append("  Volt: ").append(batteryVoltage)
            d3 = stringBuilder.toString()

            d4 = "━━━━ SYSTEM ━━━━"

            stringBuilder.clear()
            stringBuilder.append("Uptime: ").append(uptime).append("  ").append(swapInfo)
            d5 = stringBuilder.toString()

            stringBuilder.clear()
            stringBuilder.append("Free RAM: ").append(availRam)
            d6 = stringBuilder.toString()

            d7 = cpuFreqs
            d8 = "▲ Tap to collapse"
        } else {
            d1 = ""
            d2 = ""
            d3 = ""
            d4 = ""
            d5 = ""
            d6 = ""
            d7 = ""
            d8 = ""
        }

        handler.post {
            if (cpuText != lastCpuText) {
                cpuTextView?.text = cpuText
                lastCpuText = cpuText
            }
            if (gpuText != lastGpuText) {
                gpuTextView?.text = gpuText
                lastGpuText = gpuText
            }
            if (ramText != lastRamText) {
                ramTextView?.text = ramText
                lastRamText = ramText
            }
            if (tempText != lastTempText) {
                tempTextView?.text = tempText
                lastTempText = tempText
            }
            
            if (isExpanded) {
                if (d1 != lastDetail1) {
                    detailTextView1?.text = d1
                    lastDetail1 = d1
                }
                if (d2 != lastDetail2) {
                    detailTextView2?.text = d2
                    lastDetail2 = d2
                }
                if (d3 != lastDetail3) {
                    detailTextView3?.text = d3
                    lastDetail3 = d3
                }
                if (d4 != lastDetail4) {
                    detailTextView4?.text = d4
                    lastDetail4 = d4
                }
                if (d5 != lastDetail5) {
                    detailTextView5?.text = d5
                    lastDetail5 = d5
                }
                if (d6 != lastDetail6) {
                    detailTextView6?.text = d6
                    lastDetail6 = d6
                }
                if (d7 != lastDetail7) {
                    detailTextView7?.text = d7
                    lastDetail7 = d7
                }
                if (d8 != lastDetail8) {
                    detailTextView8?.text = d8
                    lastDetail8 = d8
                }
            }
        }
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
    
    private fun formatCpuText(usage: Int?): String {
        // Match MainActivity behavior: show 0% when null/unavailable
        val usageStr = "${usage ?: 0}%"
        return "CPU: $usageStr"
    }

    private fun formatGpuText(usage: Int?): String {
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
            java.io.File("/proc/stat")
                .takeIf { it.exists() && it.canRead() }
                ?.bufferedReader()
                ?.use { it.readLine() }
        } catch (_: Throwable) {
            null
        }?.takeIf { it.startsWith("cpu ") }
            ?: readFile("/proc/stat")?.lineSequence()?.firstOrNull { it.startsWith("cpu ") }
            ?: return lastCpuUsageValue.takeIf { it >= 0 }
        
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
        var bestZone: Int? = null
        for (path in zonePaths) {
            val raw = readFile(path)?.trim() ?: continue
            val v = raw.toIntOrNull() ?: continue
            val c = if (v > 1000 || v < -1000) v / 1000 else v
            if (c in 0..120) {
                if (best == null || c > best) {
                    best = c
                    bestZone = path
                        .substringAfter("thermal_zone", missingDelimiterValue = "")
                        .substringBefore("/")
                        .toIntOrNull()
                }
            }
        }
        if (best != null) {
            bestZone?.let { cachedCpuThermalZone = it }
            return best
        }
        
        // Fallback: search for cpu/cpuss/soc thermal zones
        for (i in 0..120) {
            val type = readFile("/sys/class/thermal/thermal_zone$i/type")?.trim()?.lowercase() ?: continue
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
        val cached = cachedGpuThermalZone
        if (cached >= 0) {
            val raw = readFile("/sys/class/thermal/thermal_zone$cached/temp")?.trim()
            val v = raw?.toIntOrNull()
            if (v != null) {
                val c = if (v > 1000 || v < -1000) v / 1000 else v
                if (c in 0..120) return c
            }
            cachedGpuThermalZone = -1
        }

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
                if (c in 0..120) {
                    cachedGpuThermalZone = i
                    return c
                }
            }
        }
        return null
    }
    
    private fun readRamInfo(context: Context): RamInfo? {
        // Avoid binder calls in SystemUI: parse /proc/meminfo directly.
        val memInfo = readFile("/proc/meminfo")
        try {
            fun parseKiB(line: String): Long? {
                val after = line.substringAfter(':', "").trimStart()
                if (after.isEmpty()) return null
                val num = after.takeWhile { it.isDigit() }
                return num.toLongOrNull()
            }

            var totalKiB: Long? = null
            var availKiB: Long? = null
            var freeKiB: Long? = null
            var buffersKiB: Long? = null
            var cachedKiB: Long? = null

            val lines = memInfo?.lineSequence() ?: throw IllegalStateException("meminfo unavailable")
            for (line in lines) {
                when {
                    line.startsWith("MemTotal:") -> totalKiB = parseKiB(line)
                    line.startsWith("MemAvailable:") -> availKiB = parseKiB(line)
                    line.startsWith("MemFree:") -> freeKiB = parseKiB(line)
                    line.startsWith("Buffers:") -> buffersKiB = parseKiB(line)
                    line.startsWith("Cached:") -> cachedKiB = parseKiB(line)
                }

                val hasFallbackAvail = freeKiB != null && buffersKiB != null && cachedKiB != null
                if (totalKiB != null && (availKiB != null || hasFallbackAvail)) break
            }

            val total = (totalKiB ?: 0L) * 1024L
            val avail = (availKiB ?: ((freeKiB ?: 0L) + (buffersKiB ?: 0L) + (cachedKiB ?: 0L))) * 1024L
            if (total <= 0) throw IllegalStateException("invalid MemTotal")

            val used = (total - avail).coerceIn(0L, total)
            val percent = ((used.toDouble() / total.toDouble()) * 100.0).toInt().coerceIn(0, 100)

            return RamInfo(
                used = used,
                total = total,
                percent = percent,
                usedFormatted = Formatter.formatShortFileSize(context, used),
                totalFormatted = Formatter.formatShortFileSize(context, total)
            )
        } catch (_: Throwable) {
            // Fallback to the system API.
            return try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
                val info = ActivityManager.MemoryInfo()
                am.getMemoryInfo(info)

                val total = info.totalMem
                val avail = info.availMem
                val used = (total - avail).coerceAtLeast(0L)
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
    }
    
    private fun readFile(path: String): String? {
        // Prefer direct reads first (much cheaper than hopping through su for every file).
        return try {
            java.io.File(path).takeIf { it.exists() && it.canRead() }?.readText()
        } catch (_: Throwable) {
            null
        } ?: RootShell.cat(path)
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
            stopStatsUpdater()
            floatingView?.let { view ->
                try {
                    windowManager?.removeView(view)
                } catch (_: Throwable) {
                    // View may already be removed
                }
                // Clear all view references to prevent memory leaks
                floatingView = null
                cpuTextView = null
                gpuTextView = null
                ramTextView = null
                tempTextView = null
                detailTextView1 = null
                detailTextView2 = null
                detailTextView3 = null
                detailTextView4 = null
                detailTextView5 = null
                detailTextView6 = null
                detailTextView7 = null
                detailTextView8 = null
            }
            floatingViewCreated = false
            unregisterScreenReceiver()
            updaterInstance = null
            windowManager = null
            windowLayoutParams = null
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
        @Volatile private var isDraggingWindow = false
        
        @Volatile private var lastCpuTotal: Long = -1L
        @Volatile private var lastCpuIdle: Long = -1L
        @Volatile private var lastCpuUsageValue: Int = 0
        @Volatile private var currentFont: Typeface? = null
        
        fun toggleFloatingWindow() {
            if (floatingViewCreated) {
                try {
                    updaterInstance?.stopStatsUpdater()
                    floatingView?.let { view ->
                        try {
                            windowManager?.removeView(view)
                        } catch (_: Throwable) {
                            // View may already be removed
                        }
                    }
                    // Clear all view references to prevent memory leaks
                    floatingView = null
                    cpuTextView = null
                    gpuTextView = null
                    ramTextView = null
                    tempTextView = null
                    detailTextView1 = null
                    detailTextView2 = null
                    detailTextView3 = null
                    detailTextView4 = null
                    detailTextView5 = null
                    detailTextView6 = null
                    detailTextView7 = null
                    detailTextView8 = null
                    floatingViewCreated = false
                    unregisterScreenReceiver()
                    updaterInstance = null
                    windowManager = null
                    windowLayoutParams = null
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
                        val instance = updaterInstance ?: FloatingWindowHooks()
                        instance.createFloatingWindow(ctx)
                        if (updaterInstance == null) {
                            updaterInstance = instance
                        }
                        floatingViewCreated = true
                    } catch (_: Throwable) {}
                }
            }
        }

        private fun ensureScreenReceiver() {
            val ctx = systemUiContext ?: return
            if (screenReceiverRegistered) return
            try {
                val filter = android.content.IntentFilter().apply {
                    addAction(android.content.Intent.ACTION_SCREEN_OFF)
                    addAction(android.content.Intent.ACTION_SCREEN_ON)
                }
                val receiver = object : android.content.BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: android.content.Intent?) {
                        when (intent?.action) {
                            android.content.Intent.ACTION_SCREEN_ON -> {
                                // Resume updates quickly when user wakes device.
                                if (floatingViewCreated && BaseHook.getPreferenceBoolean(PREF_FLOATING_WINDOW_ENABLED, false)) {
                                    updaterInstance?.startStatsUpdater()
                                }
                            }
                            android.content.Intent.ACTION_SCREEN_OFF -> {
                                // Stop updates immediately.
                                updaterInstance?.stopStatsUpdater()
                            }
                        }
                    }
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    ctx.registerReceiver(receiver, filter)
                }
                screenReceiver = receiver
                screenReceiverRegistered = true
            } catch (_: Throwable) {}
        }

        private fun unregisterScreenReceiver() {
            val ctx = systemUiContext ?: return
            if (!screenReceiverRegistered) return
            try {
                screenReceiver?.let { ctx.unregisterReceiver(it) }
            } catch (_: Throwable) {
            } finally {
                screenReceiver = null
                screenReceiverRegistered = false
            }
        }

        private val statsExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "NothingXpert-FloatingStats").apply { isDaemon = true }
        }
        private val statsUpdateInFlight = AtomicBoolean(false)

        // Cached values to reduce per-tick work.
        @Volatile private var lastSlowReadTs: Long = 0L
        @Volatile private var lastDetailReadTs: Long = 0L
        @Volatile private var cachedCpuTemp: Int? = null
        @Volatile private var cachedGpuTemp: Int? = null
        @Volatile private var cachedRamInfo: RamInfo? = null

        @Volatile private var cachedBatteryTemp: Int? = null
        @Volatile private var cachedBatteryLevel: Int? = null
        @Volatile private var cachedUptime: String = ""
        @Volatile private var cachedCpuFreqs: String = ""
        @Volatile private var cachedBatteryCurrent: String = ""
        @Volatile private var cachedBatteryVoltage: String = ""
        @Volatile private var cachedAvailRam: String = ""
        @Volatile private var cachedSwapInfo: String = ""

        @Volatile private var cachedCpuThermalZone: Int = -1
        @Volatile private var cachedGpuThermalZone: Int = -1

        // Avoid redundant UI updates (setText/layout) when values haven't changed.
        @Volatile private var lastCpuText: String? = null
        @Volatile private var lastGpuText: String? = null
        @Volatile private var lastRamText: String? = null
        @Volatile private var lastTempText: String? = null
        @Volatile private var lastDetail1: String? = null
        @Volatile private var lastDetail2: String? = null
        @Volatile private var lastDetail3: String? = null
        @Volatile private var lastDetail4: String? = null
        @Volatile private var lastDetail5: String? = null
        @Volatile private var lastDetail6: String? = null
        @Volatile private var lastDetail7: String? = null
        @Volatile private var lastDetail8: String? = null

        // Reusable StringBuilder for formatting
        private val stringBuilder = StringBuilder(128)

        @Volatile private var statsUpdaterRunning: Boolean = false

        @Volatile private var updaterInstance: FloatingWindowHooks? = null

        @Volatile private var screenReceiver: android.content.BroadcastReceiver? = null
        @Volatile private var screenReceiverRegistered: Boolean = false

        private const val UPDATE_INTERVAL_COLLAPSED_MS = 2000L
        private const val UPDATE_INTERVAL_EXPANDED_MS = 1000L
        private const val UPDATE_INTERVAL_IDLE_MS = 5000L
        private const val SLOW_READ_INTERVAL_MS = 2000L
        private const val DETAIL_READ_INTERVAL_MS = 5000L

        private const val LOW_ACTIVITY_PCT = 5
        private const val LOW_ACTIVITY_STREAK_FOR_IDLE = 3

        @Volatile private var lowActivityStreak: Int = 0
    }
}
