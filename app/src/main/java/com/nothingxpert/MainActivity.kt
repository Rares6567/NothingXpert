package com.nothingxpert

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.ActivityManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.HandlerThread
import android.text.format.Formatter
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.AppBarLayout
import java.io.DataOutputStream

class MainActivity : AppCompatActivity() {
    private val ramHandler = Handler(Looper.getMainLooper())
    private val ramUpdateRunnable = object : Runnable {
        override fun run() {
            updateRamUsage()
            updateCpuUsage()
            updateGpuUsage()
            ramHandler.postDelayed(this, 1000) // faster updates
        }
    }

    private lateinit var ramValue: TextView
    private lateinit var cpuValue: TextView
    private lateinit var gpuValue: TextView

    private var lastCpuIdle: Long = 0
    private var lastCpuTotal: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        setContentView(R.layout.activity_main)

        // Set up toolbar
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        // Handle status bar insets
        val appBar = findViewById<AppBarLayout>(R.id.appbar)
        ViewCompat.setOnApplyWindowInsetsListener(appBar) { view, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.updatePadding(top = statusBarInsets.top)
            insets
        }

        setupLockScreenCategory()
        setupMiscCategory()
        setupRamMonitor()
        
        // Animate on startup
        animateTitleOnStartup()
        animateCardsOnStartup()
    }

    override fun onDestroy() {
        super.onDestroy()
        ramHandler.removeCallbacks(ramUpdateRunnable)
    }

    private fun animateTitleOnStartup() {
        val titleNothing = findViewById<TextView>(R.id.title_nothing)
        val titleXpert = findViewById<TextView>(R.id.title_xpert)
        
        // Start invisible
        titleNothing.alpha = 0f
        titleNothing.translationX = -30f
        titleXpert.alpha = 0f
        titleXpert.translationY = 20f
        
        // Animate "Nothing" sliding in
        titleNothing.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(500)
            .setStartDelay(50)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
        
        // Animate "Xpert" fading up
        titleXpert.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(500)
            .setStartDelay(150)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction {
                // Add a subtle color pulse animation to "Xpert"
                animateXpertColor(titleXpert)
            }
            .start()
    }

    private fun animateXpertColor(textView: TextView) {
        val colorFrom = ContextCompat.getColor(this, android.R.color.white)
        val colorAccent = ContextCompat.getColor(this, R.color.main_preference_on_color_1)
        
        val colorAnimation = ValueAnimator.ofObject(ArgbEvaluator(), colorFrom, colorAccent, colorFrom)
        colorAnimation.duration = 1500
        colorAnimation.addUpdateListener { animator ->
            textView.setTextColor(animator.animatedValue as Int)
        }
        colorAnimation.start()
    }

    private fun animateCardsOnStartup() {
        val lockscreenCard = findViewById<View>(R.id.category_lockscreen)
        val miscCard = findViewById<View>(R.id.category_misc)
        
        // Start with invisible
        lockscreenCard.alpha = 0f
        lockscreenCard.translationY = 50f
        miscCard.alpha = 0f
        miscCard.translationY = 50f
        
        // Animate lockscreen card
        lockscreenCard.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(400)
            .setStartDelay(250)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
        
        // Animate misc card with stagger
        miscCard.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(400)
            .setStartDelay(350)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_restart_systemui -> {
                restartSystemUI()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun restartSystemUI() {
        try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("killall com.android.systemui\n")
            os.writeBytes("exit\n")
            os.flush()
            os.close()
            process.waitFor()
            Toast.makeText(this, "SystemUI restarted", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to restart SystemUI: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupLockScreenCategory() {
        val categoryView = findViewById<View>(R.id.category_lockscreen)
        
        // Set icon
        val iconView = categoryView.findViewById<ImageView>(R.id.category_icon)
        iconView.setImageResource(R.drawable.ic_settings_lockscreen)
        
        // Set icon colors (using first color set - light blue)
        val bgColor = ContextCompat.getColor(this, R.color.main_preference_color_1)
        val iconColor = ContextCompat.getColor(this, R.color.main_preference_on_color_1)
        iconView.background.setTintList(ColorStateList.valueOf(bgColor))
        iconView.imageTintList = ColorStateList.valueOf(iconColor)
        
        // Set title and summary
        val titleView = categoryView.findViewById<TextView>(R.id.category_title)
        titleView.text = getString(R.string.pref_category_lockscreen).uppercase()
        
        val summaryView = categoryView.findViewById<TextView>(R.id.category_summary)
        summaryView.text = getString(R.string.lockscreen_category_summary)
        
        // Click listener to open settings with animation
        categoryView.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }
    }

    private fun setupMiscCategory() {
        val categoryView = findViewById<View>(R.id.category_misc)
        
        // Set icon
        val iconView = categoryView.findViewById<ImageView>(R.id.category_icon)
        iconView.setImageResource(R.drawable.ic_settings_misc)
        
        // Set icon colors (using second color set - light purple/blue)
        val bgColor = ContextCompat.getColor(this, R.color.main_preference_color_2)
        val iconColor = ContextCompat.getColor(this, R.color.main_preference_on_color_2)
        iconView.background.setTintList(ColorStateList.valueOf(bgColor))
        iconView.imageTintList = ColorStateList.valueOf(iconColor)
        
        // Set title and summary
        val titleView = categoryView.findViewById<TextView>(R.id.category_title)
        titleView.text = getString(R.string.pref_category_misc).uppercase()
        
        val summaryView = categoryView.findViewById<TextView>(R.id.category_summary)
        summaryView.text = getString(R.string.misc_category_summary)
        
        // Click listener to open misc settings with animation
        categoryView.setOnClickListener {
            startActivity(Intent(this, MiscSettingsActivity::class.java))
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }
    }

    private fun setupRamMonitor() {
        ramValue = findViewById(R.id.ram_value)
        cpuValue = findViewById(R.id.cpu_value)
        gpuValue = findViewById(R.id.gpu_value)
        updateRamUsage()
        updateGpuUsage()
        // Kick off update loop
        ramHandler.postDelayed(ramUpdateRunnable, 1000)
    }

    private fun updateRamUsage() {
        val am = getSystemService(ActivityManager::class.java) ?: return
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)

        val total = info.totalMem
        val free = info.availMem
        val used = total - free
        val percent = if (total > 0) ((used.toDouble() / total) * 100).toInt() else 0

        ramValue.text = getString(
            R.string.ram_monitor_format,
            formatBytes(used),
            formatBytes(total),
            percent
        )
    }

    private fun updateCpuUsage(force: Boolean = false) {
        var usageProp = -1
        var tempProp: String? = null
        try {
            // Read from Settings.Global which is writable by system_server and readable by app
            usageProp = android.provider.Settings.Global.getString(contentResolver, "nothingxpert_cpu_usage")?.toIntOrNull() ?: -1
            tempProp = android.provider.Settings.Global.getString(contentResolver, "nothingxpert_cpu_temp")
        } catch (_: Throwable) {
        }

        if (usageProp >= 0) {
            val tempStr = when {
                !tempProp.isNullOrBlank() -> "${tempProp}°C"
                else -> readCpuTempExact()?.let { "${it}°C" } ?: "--°C"
            }
            cpuValue.text = getString(R.string.cpu_monitor_format, usageProp.coerceIn(0, 100), tempStr)
            return
        }

        // Local two-sample diff off the main thread to avoid UI lag.
        Thread {
            val usage = computeCpuUsageTwoSample()
            val temp = readCpuTempExact()
            runOnUiThread {
                val tempStr = temp?.let { "${it}°C" } ?: "--°C"
                cpuValue.text = getString(R.string.cpu_monitor_format, usage ?: 0, tempStr)
            }
        }.start()
    }

    private fun computeCpuUsageTwoSample(): Int? {
        val first = parseCpuTotals(readFile("/proc/stat")) ?: return null
        try {
            Thread.sleep(200)
        } catch (_: InterruptedException) {
        }
        val second = parseCpuTotals(readFile("/proc/stat")) ?: return null
        val totalDiff = second.first - first.first
        val idleDiff = second.second - first.second
        if (totalDiff <= 0) return null
        return (((totalDiff - idleDiff).toDouble() / totalDiff) * 100).toInt().coerceIn(0, 100)
    }

    private fun parseCpuTotals(statContent: String?): Pair<Long, Long>? {
        val line = statContent?.lineSequence()?.firstOrNull { it.trimStart().startsWith("cpu ") } ?: return null
        val parts = line.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (parts.size < 8) return null
        val user = parts[1].toLongOrNull() ?: return null
        val nice = parts[2].toLongOrNull() ?: return null
        val system = parts[3].toLongOrNull() ?: return null
        val idle = parts[4].toLongOrNull() ?: return null
        val iowait = parts.getOrNull(5)?.toLongOrNull() ?: 0
        val irq = parts.getOrNull(6)?.toLongOrNull() ?: 0
        val softirq = parts.getOrNull(7)?.toLongOrNull() ?: 0

        val idleAll = idle + iowait
        val nonIdle = user + nice + system + irq + softirq
        val total = idleAll + nonIdle
        return total to idleAll
    }

    private fun updateGpuUsage() {
        val percent = readGpuBusyPercent()
        val temp = readGpuTemp()
        val tempStr = temp?.let { "${it}°C" } ?: "--°C"
        val pctStr = percent?.let { "$it%" } ?: "0%"
        gpuValue.text = getString(R.string.gpu_monitor_format, pctStr, tempStr)
    }

    private fun readGpuBusyPercent(): Int? {
        // Common path for Adreno
        val busyLine = readFile("/sys/class/kgsl/kgsl-3d0/gpubusy")
        if (busyLine != null) {
            val nums = busyLine.trim().split("\\s+".toRegex())
            if (nums.size >= 2) {
                val busy = nums[0].toLongOrNull() ?: return null
                val total = nums[1].toLongOrNull() ?: return null
                if (total > 0) return ((busy.toDouble() / total) * 100).toInt().coerceIn(0, 100)
            }
        }
        val pct = readFile("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage")?.trim()?.toIntOrNull()
        if (pct != null) return pct.coerceIn(0, 100)
        return null
    }

    private fun readGpuTemp(): Int? {
        // kgsl temp (may require elevated perms on some builds)
        val rawKgsl = readFile("/sys/class/kgsl/kgsl-3d0/temp")?.trim()
        val kgslValue = rawKgsl?.toIntOrNull()
        if (kgslValue != null) {
            return if (kgslValue > 1000) kgslValue / 1000 else kgslValue
        }
        // Fallback to thermal zones tagged with gpu / gpuss
        return readThermalTemp(listOf("gpu", "gpuss"))
    }

    private fun readCpuTempExact(): Int? {
        // Prefer specific cpu/cpuss thermal zones; take the max valid reading
        val zonePaths = listOf(
            "/sys/class/thermal/thermal_zone25/temp",
            "/sys/class/thermal/thermal_zone26/temp",
            "/sys/class/thermal/thermal_zone27/temp",
            "/sys/class/thermal/thermal_zone28/temp",
            "/sys/class/thermal/thermal_zone29/temp",
            "/sys/class/thermal/thermal_zone30/temp",
            "/sys/class/thermal/thermal_zone31/temp",
            "/sys/class/thermal/thermal_zone32/temp",
            "/sys/class/thermal/thermal_zone33/temp",
            "/sys/class/thermal/thermal_zone34/temp",
            "/sys/class/thermal/thermal_zone35/temp",
            "/sys/class/thermal/thermal_zone36/temp",
            "/sys/class/thermal/thermal_zone37/temp",
            "/sys/class/thermal/thermal_zone41/temp",
            "/sys/class/thermal/thermal_zone42/temp",
            "/sys/class/thermal/thermal_zone43/temp",
            "/sys/class/thermal/thermal_zone44/temp"
        )
        var best: Int? = null
        for (p in zonePaths) {
            val raw = readFile(p)?.trim() ?: continue
            val v = raw.toIntOrNull() ?: continue
            val c = if (v > 1000 || v < -1000) v / 1000 else v
            if (c in 0..120) {
                best = maxOf(best ?: c, c)
            }
        }
        if (best != null) return best
        // Fallback generic search
        return readThermalTemp(listOf("cpu", "cpuss", "soc"))
    }

    private fun readThermalTemp(keywords: List<String>): Int? {
        var best: Int? = null
        for (i in 0..120) {
            val type = readFile("/sys/class/thermal/thermal_zone$i/type")?.trim()?.lowercase() ?: continue
            if (keywords.any { type.contains(it) }) {
                val raw = readFile("/sys/class/thermal/thermal_zone$i/temp")?.trim() ?: continue
                val value = raw.toIntOrNull() ?: continue
                val celsius = when {
                    value > 1000 || value < -1000 -> value / 1000
                    else -> value
                }
                if (celsius in 0..120) {
                    best = maxOf(best ?: celsius, celsius)
                }
            }
        }
        return best
    }

    private fun readFile(path: String): String? {
        // Try direct read first
        try {
            java.io.File(path).takeIf { it.exists() }?.let { return it.readText() }
        } catch (_: Exception) { }
        return null
    }

    private fun formatBytes(bytes: Long): String {
        return Formatter.formatShortFileSize(this, bytes)
    }

}
