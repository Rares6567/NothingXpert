package com.nothingxpert

import com.google.android.material.color.DynamicColors

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.ActivityManager
import android.app.AlarmManager
import android.content.Context
import android.app.PendingIntent
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.HandlerThread
import android.text.format.Formatter
import android.util.Log
import android.graphics.Typeface
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
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
import com.nothingxpert.util.RootShell
import kotlin.system.exitProcess
import androidx.appcompat.app.AlertDialog
import com.nothingxpert.ui.GlitchEffect

class MainActivity : BaseActivity() {

    companion object {
        private const val UPDATE_INTERVAL_MS = 1000L
        private const val MONITOR_START_DELAY_MS = 2000L
        private const val CPU_SAMPLE_DELAY_MS = 500L
        private const val CPU_LOG_TAG = "NothingXpertCPU"
    }

    private val ramHandler = Handler(Looper.getMainLooper())
    private val cpuHandlerThread = HandlerThread("NothingXpert-CPU").also { it.start() }
    private val cpuUpdateHandler = Handler(cpuHandlerThread.looper)
    private val ramUpdateRunnable = object : Runnable {
        override fun run() {
            if (isMainTabSelected && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                // Binder call + sysfs reads offloaded to the CPU handler thread;
                // only the final setText touches the main thread.
                cpuUpdateHandler.post {
                    val ramText = buildRamText()
                    val gpuText = buildGpuText()
                    runOnUiThread {
                        if (!isDestroyed && !isFinishing) {
                            ramValue.text = ramText
                            gpuValue.text = gpuText
                        }
                    }
                }
                triggerCpuUpdate()
                ramHandler.postDelayed(this, UPDATE_INTERVAL_MS)
            }
        }
    }
    private var gateRunnable: Runnable? = null

    private lateinit var ramValue: TextView
    private lateinit var cpuValue: TextView
    private lateinit var gpuValue: TextView
    private lateinit var tabMain: TextView
    private lateinit var tabOptions: TextView
    private lateinit var sectionMain: View
    private lateinit var sectionOptions: View
    private lateinit var languageValue: TextView

    // CPU two-sample tracking
    private var lastCpuIdle: Long = -1
    private var lastCpuTotal: Long = -1
    @Volatile private var lastCpuUsage: Int = -1
    private var cpuFirstSample: Pair<Long, Long>? = null
    @Volatile private var cpuInitThread: Thread? = null

    private lateinit var gestureDetector: android.view.GestureDetector
    private var isMainTabSelected = true
    @Volatile private var restarting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivitiesIfAvailable(this.application)
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        setContentView(R.layout.activity_main)

        // Apply appropriate font based on language (Ndot57 for EN, VT323 for TR)
        applyLanguageBasedFont()

        // Keep prefs readable for LSPosed after recreates/theme toggles
        PrefsUtil.ensurePrefsAccessible(this)

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

        // Handle navigation bar / gesture insets so the tab bar is not overlapped
        val tabBar = findViewById<View>(R.id.tab_bar)
        ViewCompat.setOnApplyWindowInsetsListener(tabBar) { view, insets ->
            val navInsets = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.systemGestures()
            )
            view.updatePadding(bottom = navInsets.bottom)
            insets
        }

        gestureDetector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            private val SWIPE_THRESHOLD = 100
            private val SWIPE_VELOCITY_THRESHOLD = 100

            override fun onDown(e: android.view.MotionEvent): Boolean {
                return false // Let scrollview handle scrolling
            }

            override fun onFling(
                e1: android.view.MotionEvent?,
                e2: android.view.MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val diffY = e2.y - e1.y
                val diffX = e2.x - e1.x
                if (Math.abs(diffX) > Math.abs(diffY)) {
                    if (Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                        if (diffX > 0) {
                            // Swipe Right -> Go to Main
                            if (!isMainTabSelected) selectTab(true)
                        } else {
                            // Swipe Left -> Go to Options
                            if (isMainTabSelected) selectTab(false)
                        }
                        return true
                    }
                }
                return false
            }
        })


        setupLockScreenCategory()
        setupMiscCategory()
        setupStatusBarCategory()
        setupAppsCategory()
        setupGlyphsCategory()
        setupRamMonitor()
        setupTabBar()
        setupLanguageCard()
        
        // Animate on startup
        animateTitleOnStartup()
        animateCardsOnStartup()
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        ev?.let { gestureDetector.onTouchEvent(it) }
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        super.onDestroy()
        ramHandler.removeCallbacksAndMessages(null)
        cpuUpdateHandler.removeCallbacksAndMessages(null)
        cpuHandlerThread.quitSafely()
        gateRunnable?.let { ramHandler.removeCallbacks(it) }

        cpuInitThread?.let { if (it.isAlive) it.interrupt() }
        cpuInitThread = null
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
            .withEndAction {
                GlitchEffect.apply(titleNothing, 800)
            }
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
        val statusBarCard = findViewById<View>(R.id.category_status_bar)
        val appsCard = findViewById<View>(R.id.category_apps)
        val glyphsCard = findViewById<View>(R.id.category_glyphs)
        
        // Start with invisible
        lockscreenCard.alpha = 0f
        lockscreenCard.translationY = 50f
        miscCard.alpha = 0f
        miscCard.translationY = 50f
        statusBarCard.alpha = 0f
        statusBarCard.translationY = 50f
        appsCard.alpha = 0f
        appsCard.translationY = 50f
        glyphsCard.alpha = 0f
        glyphsCard.translationY = 50f
        
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

        // Animate status bar card with further stagger
        statusBarCard.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(400)
            .setStartDelay(450)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()

        // Animate apps card with further stagger
        appsCard.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(400)
            .setStartDelay(550)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()

        // Animate glyphs card with further stagger
        glyphsCard.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(400)
            .setStartDelay(650)
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
            try {
                val os = DataOutputStream(process.outputStream)
                os.writeBytes("killall com.android.systemui\n")
                os.writeBytes("exit\n")
                os.flush()
                os.close()
                process.waitFor()
            } finally {
                process.destroy()
            }
            // Schedule app auto-restart in background, then kill this process
            PrefsUtil.ensurePrefsAccessible(this)
            scheduleSelfRestart(1200)
            Toast.makeText(this, getString(R.string.toast_systemui_restarting), Toast.LENGTH_SHORT).show()
            finishAffinity()
            android.os.Process.killProcess(android.os.Process.myPid())
            exitProcess(0)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.toast_systemui_restart_failed, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    private fun scheduleSelfRestart(delayMs: Long) {
        try {
            val ctx = applicationContext
            val intent = ctx.packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent(ctx, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val pi = PendingIntent.getActivity(
                ctx,
                9991,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
            )
            val am = ctx.getSystemService(AlarmManager::class.java)
            // Non-wakeup: device is already awake when this fires (user just hit restart).
            am?.setExact(
                AlarmManager.ELAPSED_REALTIME,
                android.os.SystemClock.elapsedRealtime() + delayMs,
                pi
            )
        } catch (_: Throwable) {
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
        summaryView.visibility = View.GONE
        
        // Click listener to open settings with animation
        categoryView.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            applyBackAnimation()
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
        summaryView.visibility = View.GONE
        
        // Click listener to open misc settings with animation
        categoryView.setOnClickListener {
            startActivity(Intent(this, MiscSettingsActivity::class.java))
            applyBackAnimation()
        }
    }

    private fun setupStatusBarCategory() {
        val categoryView = findViewById<View>(R.id.category_status_bar)
        
        // Set icon
        val iconView = categoryView.findViewById<ImageView>(R.id.category_icon)
        iconView.setImageResource(R.drawable.ic_settings_statusbar)
        
        // Use lockscreen color scheme (light blue)
        val bgColor = ContextCompat.getColor(this, R.color.main_preference_color_1)
        val iconColor = ContextCompat.getColor(this, R.color.main_preference_on_color_1)
        iconView.background.setTintList(ColorStateList.valueOf(bgColor))
        iconView.imageTintList = ColorStateList.valueOf(iconColor)
        
        // Set title and summary
        val titleView = categoryView.findViewById<TextView>(R.id.category_title)
        titleView.text = getString(R.string.pref_category_status_bar).uppercase()
        
        val summaryView = categoryView.findViewById<TextView>(R.id.category_summary)
        summaryView.visibility = View.GONE
        
        // Click listener to open status bar settings
        categoryView.setOnClickListener {
            startActivity(Intent(this, StatusBarSettingsActivity::class.java))
            applyBackAnimation()
        }
    }

    private fun setupAppsCategory() {
        val categoryView = findViewById<View>(R.id.category_apps)
        
        // Set icon
        val iconView = categoryView.findViewById<ImageView>(R.id.category_icon)
        iconView.setImageResource(R.drawable.ic_settings_apps)
        
        // Reuse Misc colors for consistency for now
        val bgTint = ContextCompat.getColor(this, R.color.main_preference_color_2)
        val iconTint = ContextCompat.getColor(this, R.color.main_preference_on_color_2)
        
        iconView.background.setTintList(ColorStateList.valueOf(bgTint))
        iconView.imageTintList = ColorStateList.valueOf(iconTint)
        
        // Set title and summary
        val titleView = categoryView.findViewById<TextView>(R.id.category_title)
        titleView.text = getString(R.string.pref_category_apps).uppercase()
        
        val summaryView = categoryView.findViewById<TextView>(R.id.category_summary)
        summaryView.visibility = View.GONE
        
        // Click listener -> Apps hub
        categoryView.setOnClickListener {
            startActivity(Intent(this, AppsActivity::class.java))
            applyBackAnimation()
        }
    }

    private fun setupGlyphsCategory() {
        val categoryView = findViewById<View>(R.id.category_glyphs)
        
        // Set icon
        val iconView = categoryView.findViewById<ImageView>(R.id.category_icon)
        iconView.setImageResource(R.drawable.ic_settings_glyphs)
        
        // Use pink color scheme (set 3)
        val bgColor = ContextCompat.getColor(this, R.color.main_preference_color_3)
        val iconColor = ContextCompat.getColor(this, R.color.main_preference_on_color_3)
        iconView.background.setTintList(ColorStateList.valueOf(bgColor))
        iconView.imageTintList = ColorStateList.valueOf(iconColor)
        
        // Set title and summary
        val titleView = categoryView.findViewById<TextView>(R.id.category_title)
        titleView.text = getString(R.string.pref_category_glyphs).uppercase()
        
        val summaryView = categoryView.findViewById<TextView>(R.id.category_summary)
        summaryView.visibility = View.GONE
        
        // Click listener -> Glyphs hub
        categoryView.setOnClickListener {
            startActivity(Intent(this, GlyphsActivity::class.java))
            applyBackAnimation()
        }
    }

    private fun setupRamMonitor() {
        ramValue = findViewById(R.id.ram_value)
        cpuValue = findViewById(R.id.cpu_value)
        gpuValue = findViewById(R.id.gpu_value)
        sectionMain = findViewById(R.id.section_main)
        sectionOptions = findViewById(R.id.section_options)

        // Initial static update off the main thread
        cpuUpdateHandler.post {
            val ramText = buildRamText()
            val gpuText = buildGpuText()
            runOnUiThread {
                if (!isDestroyed && !isFinishing) {
                    ramValue.text = ramText
                    gpuValue.text = gpuText
                }
            }
        }
        initCpuUsage() // Initialize CPU reading in background
    }

    /**
     * Start CPU reading immediately in background so we have a value ready
     * when the update cycle begins
     */
    private fun initCpuUsage() {
        // Show loading state immediately (use 0 as placeholder for the %d format)
        cpuValue.text = getString(R.string.cpu_monitor_format, 0, "--°C")

        // Start background reading for initial value
        val thread = Thread {
            try {
                val firstLine = readCpuStatLine()
                val firstSample = if (firstLine != null) parseCpuTotals(firstLine) else null

                if (firstSample != null) {
                    // Wait for second sample
                    try { Thread.sleep(CPU_SAMPLE_DELAY_MS) } catch (_: InterruptedException) { return@Thread }

                    // Exit early if activity is finishing/destroyed before second sample.
                    if (isDestroyed || isFinishing) return@Thread

                    val secondLine = readCpuStatLine()
                    val secondSample = if (secondLine != null) parseCpuTotals(secondLine) else null

                    if (secondSample != null) {
                        val totalDiff = secondSample.first - firstSample.first
                        val idleDiff = secondSample.second - firstSample.second
                        if (totalDiff > 0 && idleDiff >= 0) {
                            val busy = (totalDiff - idleDiff).toDouble()
                            val pct = ((busy / totalDiff.toDouble()) * 100.0).toInt().coerceIn(0, 100)
                            lastCpuUsage = pct

                            // Get temperature and update UI
                            val temp = readCpuTempExact()
                            val tempStr = temp?.let { "${it}°C" } ?: "--°C"
                            runOnUiThread {
                                if (!isDestroyed && !isFinishing) {
                                    cpuValue.text = getString(R.string.cpu_monitor_format, pct, tempStr)
                                }
                            }
                        }
                    }
                }
            } finally {
                cpuInitThread = null
            }
        }
        cpuInitThread = thread
        thread.start()
    }
    
    override fun onResume() {
        super.onResume()
        if (isMainTabSelected) {
            startResourceMonitor()
        }
    }

    override fun onPause() {
        super.onPause()
        stopResourceMonitor()
    }

    private fun startResourceMonitor() {
        stopResourceMonitor() // Ensure no duplicates
        val gate = Runnable {
            // Only start if still on main after the gate window
            if (isMainTabSelected && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                ramHandler.post(ramUpdateRunnable)
            }
        }
        gateRunnable = gate
        ramHandler.postDelayed(gate, MONITOR_START_DELAY_MS)
    }

    private fun stopResourceMonitor() {
        ramHandler.removeCallbacks(ramUpdateRunnable)
        gateRunnable?.let { ramHandler.removeCallbacks(it) }
        gateRunnable = null
    }

    private fun setupTabBar() {
        tabMain = findViewById(R.id.tab_main)
        tabOptions = findViewById(R.id.tab_options)

        tabMain.setOnClickListener { selectTab(true) }
        tabOptions.setOnClickListener { selectTab(false) }

        // Setup AMOLED switch
        val switchAmoled = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_amoled)
        val cardAmoled = findViewById<View>(R.id.card_amoled)
        
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        switchAmoled.isChecked = prefs.getBoolean("pref_amoled_theme", false)
        
        val toggleListener = { _: View ->
            val newState = !switchAmoled.isChecked
            switchAmoled.isChecked = newState
            prefs.edit().putBoolean("pref_amoled_theme", newState).commit()
            PrefsUtil.ensurePrefsAccessible(this)
            scheduleRestartSelf()
        }
        
        cardAmoled.setOnClickListener(toggleListener)
        switchAmoled.setOnClickListener { 
            prefs.edit().putBoolean("pref_amoled_theme", switchAmoled.isChecked).commit()
            PrefsUtil.ensurePrefsAccessible(this)
            scheduleRestartSelf()
        }
    }

    private fun setupLanguageCard() {
        languageValue = findViewById(R.id.language_value)
        val cardLanguage = findViewById<View>(R.id.card_language)
        
        updateLanguageDisplay()
        
        cardLanguage.setOnClickListener {
            showLanguageDialog()
        }
    }

    private fun updateLanguageDisplay() {
        val currentLanguage = LocaleHelper.getLanguage(this)
        val displayText = when (currentLanguage) {
            "en" -> getString(R.string.language_english)
            "tr" -> getString(R.string.language_turkish)
            else -> getString(R.string.language_system_default)
        }
        languageValue.text = displayText
    }

    private fun showLanguageDialog() {
        val languages = arrayOf(
            getString(R.string.language_system_default),
            getString(R.string.language_english),
            getString(R.string.language_turkish)
        )
        val languageCodes = arrayOf("", "en", "tr")
        
        val currentLanguage = LocaleHelper.getLanguage(this)
        val currentIndex = languageCodes.indexOf(currentLanguage).coerceAtLeast(0)
        
        AlertDialog.Builder(this)
            .setTitle(R.string.pref_language_title)
            .setSingleChoiceItems(languages, currentIndex) { dialog, which ->
                val selectedLanguage = languageCodes[which]
                if (selectedLanguage != currentLanguage) {
                    LocaleHelper.setNewLocale(this, selectedLanguage)
                    FontHelper.clearCache() // Clear font cache so correct font is loaded
                    PrefsUtil.ensurePrefsAccessible(this)
                    // Restart app to apply language change
                    scheduleRestartSelf()
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun selectTab(main: Boolean) {
        if (isMainTabSelected == main) return
        isMainTabSelected = main
        
        val selectedColor = getThemeColor(com.google.android.material.R.attr.colorOnSurface)
        val unselectedColor = getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)

        slideSections(showMain = main)

        // No pill; just color emphasis
        animateTextColor(tabMain, if (main) selectedColor else unselectedColor)
        animateTextColor(tabOptions, if (!main) selectedColor else unselectedColor)
        
        // Manage resource updates based on tab
        if (main) {
            startResourceMonitor() // gated start
        } else {
            stopResourceMonitor()
        }
    }

    private fun getThemeColor(attr: Int): Int {
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    private fun slideSections(showMain: Boolean) {
        val toShow = if (showMain) sectionMain else sectionOptions
        val toHide = if (showMain) sectionOptions else sectionMain

        toShow.animate().cancel()
        toHide.animate().cancel()

        // Convert dp to px for consistent travel distance
        val travelDist = dpToPx(40f)
        val inFromX = if (showMain) -travelDist else travelDist
        val outToX = if (showMain) travelDist else -travelDist

        toShow.visibility = View.VISIBLE
        toShow.alpha = 0f
        toShow.translationX = inFromX
        toShow.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(300)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
            .start()

        toHide.animate()
            .alpha(0f)
            .translationX(outToX)
            .setDuration(250)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
            .withEndAction {
                toHide.visibility = View.GONE
                toHide.translationX = 0f
            }
            .start()
    }

    private fun dpToPx(dp: Float): Float {
        return android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP,
            dp,
            resources.displayMetrics
        )
    }

    private fun animateTextColor(tv: TextView, targetColor: Int) {
        val startColor = tv.currentTextColor
        if (startColor == targetColor) return
        val animator = ValueAnimator.ofObject(ArgbEvaluator(), startColor, targetColor)
        animator.duration = 180
        animator.addUpdateListener { tv.setTextColor(it.animatedValue as Int) }
        animator.start()
    }

    private fun buildRamText(): String {
        val am = getSystemService(ActivityManager::class.java) ?: return ""
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val total = info.totalMem
        val free = info.availMem
        val used = total - free
        val percent = if (total > 0) ((used.toDouble() / total) * 100).toInt() else 0
        return getString(R.string.ram_monitor_format, formatBytes(used), formatBytes(total), percent)
    }

    /**
     * Trigger CPU update by reading first sample, then scheduling second sample
     * This syncs CPU updates with the 1-second update cycle
     */
    private fun triggerCpuUpdate() {
        // All /proc/stat reads and the 500 ms inter-sample sleep happen on the
        // background HandlerThread so the main thread is never blocked.
        cpuUpdateHandler.post {
            val firstLine = readCpuStatLine()
            val firstSample = if (firstLine != null) parseCpuTotals(firstLine) else null

            if (firstSample == null) {
                val tempStr = readCpuTempExact()?.let { "${it}°C" } ?: "--°C"
                val shownUsage = if (lastCpuUsage >= 0) lastCpuUsage else 0
                runOnUiThread {
                    if (!isDestroyed && !isFinishing) {
                        cpuValue.text = getString(R.string.cpu_monitor_format, shownUsage, tempStr)
                    }
                }
                return@post
            }

            try { Thread.sleep(CPU_SAMPLE_DELAY_MS) } catch (_: InterruptedException) { return@post }
            if (isDestroyed || isFinishing) return@post

            val secondLine = readCpuStatLine()
            val secondSample = if (secondLine != null) parseCpuTotals(secondLine) else null

            val usage = if (secondSample != null) {
                val totalDiff = secondSample.first - firstSample.first
                val idleDiff = secondSample.second - firstSample.second
                if (totalDiff > 0 && idleDiff >= 0) {
                    val pct = (((totalDiff - idleDiff).toDouble() / totalDiff) * 100.0).toInt().coerceIn(0, 100)
                    lastCpuUsage = pct
                    pct
                } else lastCpuUsage
            } else lastCpuUsage

            val temp = readCpuTempExact()
            val tempStr = temp?.let { "${it}°C" } ?: "--°C"
            val shownUsage = if (usage >= 0) usage else 0
            runOnUiThread {
                if (!isDestroyed && !isFinishing) {
                    cpuValue.text = getString(R.string.cpu_monitor_format, shownUsage, tempStr)
                }
            }
        }
    }

    private fun parseCpuTotals(statContent: String?): Pair<Long, Long>? {
        val line = statContent?.lineSequence()?.firstOrNull { it.trimStart().startsWith("cpu ") } ?: statContent ?: return null
        val parts = line.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (parts.size < 5) return null
        val user = parts[1].toLongOrNull() ?: return null
        val nice = parts[2].toLongOrNull() ?: return null
        val system = parts[3].toLongOrNull() ?: return null
        val idle = parts[4].toLongOrNull() ?: return null
        val iowait = parts.getOrNull(5)?.toLongOrNull() ?: 0
        val irq = parts.getOrNull(6)?.toLongOrNull() ?: 0
        val softirq = parts.getOrNull(7)?.toLongOrNull() ?: 0
        val steal = parts.getOrNull(8)?.toLongOrNull() ?: 0

        val idleAll = idle + iowait
        val nonIdle = user + nice + system + irq + softirq + steal
        val total = idleAll + nonIdle
        return total to idleAll
    }

    private fun readCpuStatLine(): String? {
        // Direct read — /proc/stat is world-readable, no root needed.
        try {
            java.io.File("/proc/stat").takeIf { it.exists() }?.let { file ->
                file.bufferedReader().useLines { seq ->
                    val line = seq.firstOrNull { it.startsWith("cpu ") }
                    if (line != null) return line
                }
            }
        } catch (e: Exception) {
            Log.w(CPU_LOG_TAG, "direct /proc/stat read failed", e)
        }
        // Root shell fallback for locked-down builds
        try {
            val out = RootShell.cat("/proc/stat", timeoutMs = 1500L)
            val line = out?.lineSequence()?.firstOrNull { it.startsWith("cpu ") }
            if (line != null) return line
        } catch (e: Exception) {
            Log.w(CPU_LOG_TAG, "root cat /proc/stat failed", e)
        }
        return null
    }

    private fun buildGpuText(): String {
        val percent = readGpuBusyPercent()
        val temp = readGpuTemp()
        val tempStr = temp?.let { "${it}°C" } ?: "--°C"
        val pctStr = percent?.let { "$it%" } ?: "0%"
        return getString(R.string.gpu_monitor_format, pctStr, tempStr)
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

    private fun readFileSafe(path: String): String? {
        // Prefer root so we don't depend on app sandbox/SELinux visibility for sysfs/proc.
        RootShell.cat(path)?.let { return it }

        // Try direct read
        try {
            java.io.File(path).takeIf { it.exists() }?.let { return it.readText() }
        } catch (_: Exception) { }

        // Fallback: read via ProcessBuilder (no root)
        return try {
            val process = ProcessBuilder("cat", path).redirectErrorStream(true).start()
            process.inputStream.bufferedReader().use { it.readText() }.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
    }

    // Backward compatibility for existing callers
    private fun readFile(path: String): String? = readFileSafe(path)

    @Suppress("DEPRECATION")
    private fun scheduleRestartSelf() {
        if (restarting) return
        restarting = true
        stopResourceMonitor()
        // Ensure prefs are synced before relaunch so LSPosed picks up changes
        PrefsUtil.ensurePrefsAccessible(this)
        ramHandler.postDelayed({
            val launch = Intent(this, MainActivity::class.java)
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(launch)
            overridePendingTransition(0, 0)
            finish()
        }, 200) // small delay to let IO finish
    }

    private fun applyLanguageBasedFont() {
        // Use VT323 for Turkish (has Turkish character support), Ndot57 for other languages
        val typeface = FontHelper.getNothingFont(this)
        val isTurkish = LocaleHelper.isTurkish(this)
        
        // Apply to title views (with larger size for Turkish)
        findViewById<TextView>(R.id.title_nothing)?.apply {
            this.setTypeface(typeface, Typeface.NORMAL)
            if (isTurkish) textSize = 20f // was 18sp
        }
        findViewById<TextView>(R.id.title_xpert)?.apply {
            this.setTypeface(typeface, Typeface.NORMAL)
            if (isTurkish) textSize = 52f // was 48sp
        }
        
        // Apply to tab views (with larger size for Turkish)
        findViewById<TextView>(R.id.tab_main)?.apply {
            this.setTypeface(typeface, Typeface.NORMAL)
            if (isTurkish) textSize = 15f // was 15sp, keep same
        }
        findViewById<TextView>(R.id.tab_options)?.apply {
            this.setTypeface(typeface, Typeface.NORMAL)
            if (isTurkish) textSize = 15f
        }
        
        // Apply to RAM/CPU/GPU monitors
        findViewById<TextView>(R.id.ram_value)?.apply {
            this.setTypeface(typeface, Typeface.NORMAL)
            if (isTurkish) textSize = 17f // was 15sp
        }
        findViewById<TextView>(R.id.cpu_value)?.apply {
            this.setTypeface(typeface, Typeface.NORMAL)
            if (isTurkish) textSize = 17f
        }
        findViewById<TextView>(R.id.gpu_value)?.apply {
            this.setTypeface(typeface, Typeface.NORMAL)
            if (isTurkish) textSize = 17f
        }
        
        // Apply to category cards (with larger size for Turkish)
        applyFontToViewGroup(findViewById(R.id.category_lockscreen), typeface, isTurkish, 26f) // was 24sp
        applyFontToViewGroup(findViewById(R.id.category_misc), typeface, isTurkish, 26f)
        applyFontToViewGroup(findViewById(R.id.category_status_bar), typeface, isTurkish, 26f)
        applyFontToViewGroup(findViewById(R.id.category_apps), typeface, isTurkish, 26f)
        applyFontToViewGroup(findViewById(R.id.category_glyphs), typeface, isTurkish, 26f)
        
        // Apply to Options section (with larger size for Turkish)
        applyFontToViewGroup(findViewById(R.id.section_options), typeface, isTurkish, 26f)
        
        // Apply to language value text
        findViewById<TextView>(R.id.language_value)?.apply {
            this.setTypeface(typeface, Typeface.NORMAL)
            if (isTurkish) textSize = 18f // was 16sp
        }
    }
    
    private fun applyFontToViewGroup(view: View?, typeface: Typeface, isTurkish: Boolean = false, turkishTextSize: Float = 0f) {
        view ?: return
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                if (child is TextView) {
                    child.setTypeface(typeface, Typeface.NORMAL)
                    // Increase text size for Turkish on TextViews that already have the custom font
                    if (isTurkish && turkishTextSize > 0) {
                        // Only apply to titles (bold text style or specific text sizes)
                        val titleThresholdPx = android.util.TypedValue.applyDimension(
                            android.util.TypedValue.COMPLEX_UNIT_SP,
                            20f,
                            resources.displayMetrics
                        )
                        if (child.textSize >= titleThresholdPx) { // Only increase titles, not summaries
                            child.textSize = turkishTextSize
                        }
                    }
                } else if (child is ViewGroup) {
                    applyFontToViewGroup(child, typeface, isTurkish, turkishTextSize)
                }
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        return Formatter.formatShortFileSize(this, bytes)
    }

}
