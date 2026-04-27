package com.nothingxpert

import android.content.Context
import android.content.SharedPreferences
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class GlyphsActivity : BaseActivity() {

    companion object {
        private const val PREF_GLYPH_TEST_MODEL_OVERRIDE = "pref_glyph_test_model_override"
        private const val MODEL_OVERRIDE_AUTO = "auto"
        private const val MODEL_OVERRIDE_PHONE_1 = "phone1"
        private const val MODEL_OVERRIDE_PHONE_2 = "phone2"

        private fun isPhysicalPhone1(): Boolean = Build.DEVICE?.lowercase() == "spacewar"
        private fun isPhysicalPhone2(): Boolean = Build.DEVICE?.lowercase() == "pong"

        // Hardware-facing checks should stay physical by default.
        fun isPhone1(): Boolean = isPhysicalPhone1()
        fun isPhone2(): Boolean = isPhysicalPhone2()
        fun isSupportedDevice(): Boolean = isPhone1() || isPhone2()

        // UI-facing checks can be overridden from the hidden test picker.
        fun isPhone1ForGlyphUi(context: Context?): Boolean {
            return when (getTestModelOverride(context)) {
                MODEL_OVERRIDE_PHONE_1 -> true
                MODEL_OVERRIDE_PHONE_2 -> false
                else -> isPhysicalPhone1()
            }
        }

        fun isPhone2ForGlyphUi(context: Context?): Boolean {
            return when (getTestModelOverride(context)) {
                MODEL_OVERRIDE_PHONE_1 -> false
                MODEL_OVERRIDE_PHONE_2 -> true
                else -> isPhysicalPhone2()
            }
        }

        fun isSupportedDeviceForGlyphUi(context: Context?): Boolean {
            return isPhone1ForGlyphUi(context) || isPhone2ForGlyphUi(context)
        }

        fun getDeviceName(activity: BaseActivity): String {
            return when {
                isPhone1() -> activity.getString(R.string.glyphs_device_phone1)
                isPhone2() -> activity.getString(R.string.glyphs_device_phone2)
                else -> Build.MODEL ?: "Unknown"
            }
        }

        fun getDeviceNameForGlyphUi(activity: BaseActivity): String {
            return when {
                isPhone1ForGlyphUi(activity) -> activity.getString(R.string.glyphs_device_phone1)
                isPhone2ForGlyphUi(activity) -> activity.getString(R.string.glyphs_device_phone2)
                else -> Build.MODEL ?: "Unknown"
            }
        }

        fun isTestModelOverrideActive(context: Context?): Boolean {
            return getTestModelOverride(context) != MODEL_OVERRIDE_AUTO
        }

        fun getTestModelOverride(context: Context?): String {
            val appContext = context?.applicationContext ?: getProcessAppContext() ?: return MODEL_OVERRIDE_AUTO
            val prefs = getModulePrefs(appContext)
            return prefs.getString(PREF_GLYPH_TEST_MODEL_OVERRIDE, MODEL_OVERRIDE_AUTO) ?: MODEL_OVERRIDE_AUTO
        }

        fun setTestModelOverride(context: Context, overrideValue: String) {
            val normalized = when (overrideValue) {
                MODEL_OVERRIDE_PHONE_1,
                MODEL_OVERRIDE_PHONE_2 -> overrideValue
                else -> MODEL_OVERRIDE_AUTO
            }
            getModulePrefs(context.applicationContext)
                .edit()
                .putString(PREF_GLYPH_TEST_MODEL_OVERRIDE, normalized)
                .commit()
        }

        private fun getModulePrefs(context: Context): SharedPreferences {
            return try {
                context.getSharedPreferences("${HookEntry.MODULE_PKG}_preferences", Context.MODE_PRIVATE)
            } catch (_: Exception) {
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            }
        }

        private fun getProcessAppContext(): Context? {
            return try {
                val activityThread = Class.forName("android.app.ActivityThread")
                val currentApplication = activityThread.getMethod("currentApplication")
                val app = currentApplication.invoke(null) as? android.app.Application
                app?.applicationContext
            } catch (_: Throwable) {
                null
            }
        }
    }

    private var modelNameTapCount = 0
    private var lastModelNameTapMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this) {
            finish()
            applyForwardAnimation()
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_glyphs)

        val appBar = findViewById<AppBarLayout>(R.id.appbar)
        ViewCompat.setOnApplyWindowInsetsListener(appBar) { view, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.updatePadding(top = statusBarInsets.top)
            insets
        }

        // Handle navigation bar / gesture insets so the last row isn't overlapped
        val contentScroll = findViewById<android.widget.ScrollView>(R.id.content_scroll)
        ViewCompat.setOnApplyWindowInsetsListener(contentScroll) { view, insets ->
            val navInsets = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.systemGestures()
            )
            view.updatePadding(bottom = navInsets.bottom)
            insets
        }

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        toolbar.title = getString(R.string.pref_category_glyphs)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupDeviceBanner()
        setupFeatureRows()
    }

    private fun setupDeviceBanner() {
        val deviceName = findViewById<TextView>(R.id.device_name)
        val deviceStatus = findViewById<TextView>(R.id.device_status)
        val unsupportedWarning = findViewById<TextView>(R.id.unsupported_warning)
        val featuresContainer = findViewById<View>(R.id.features_container)

        val uiDeviceName = getDeviceNameForGlyphUi(this)
        val isUiSupported = isSupportedDeviceForGlyphUi(this)

        if (isUiSupported) {
            deviceName.text = uiDeviceName
            deviceStatus.text = if (isTestModelOverrideActive(this)) {
                getString(R.string.glyphs_device_testing_as, uiDeviceName)
            } else {
                getString(R.string.glyphs_device_detected, uiDeviceName)
            }
            unsupportedWarning.visibility = View.GONE
            featuresContainer.visibility = View.VISIBLE
        } else {
            deviceName.text = Build.MODEL ?: "Unknown Device"
            deviceStatus.text = Build.DEVICE ?: ""
            unsupportedWarning.visibility = View.VISIBLE
            featuresContainer.visibility = View.GONE
        }

        // Hidden tester shortcut: triple tap model name to switch Glyph UI model.
        deviceName.setOnClickListener { handleDeviceNameTap() }
    }

    private fun handleDeviceNameTap() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastModelNameTapMs > 900L) {
            modelNameTapCount = 0
        }
        modelNameTapCount += 1
        lastModelNameTapMs = now

        if (modelNameTapCount >= 3) {
            modelNameTapCount = 0
            showTestModelPicker()
        }
    }

    private fun showTestModelPicker() {
        val options = arrayOf(
            getString(R.string.glyphs_test_model_auto),
            getString(R.string.glyphs_device_phone1),
            getString(R.string.glyphs_device_phone2)
        )

        var selectedIndex = when (getTestModelOverride(this)) {
            MODEL_OVERRIDE_PHONE_1 -> 1
            MODEL_OVERRIDE_PHONE_2 -> 2
            else -> 0
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.glyphs_test_model_title)
            .setSingleChoiceItems(options, selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val overrideValue = when (selectedIndex) {
                    1 -> MODEL_OVERRIDE_PHONE_1
                    2 -> MODEL_OVERRIDE_PHONE_2
                    else -> MODEL_OVERRIDE_AUTO
                }
                setTestModelOverride(this, overrideValue)
                setupDeviceBanner()
                Toast.makeText(
                    this,
                    getString(R.string.glyphs_test_model_applied, options[selectedIndex]),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .show()
    }

    private fun setupFeatureRows() {
        // Per App Glyph Notification row
        val row = findViewById<View>(R.id.row_glyph_notif)
        val icon = row.findViewById<ImageView>(R.id.category_icon)
        val title = row.findViewById<TextView>(R.id.category_title)
        val summary = row.findViewById<TextView>(R.id.category_summary)

        icon.setImageResource(R.drawable.ic_settings_glyphs)
        val bgTint = ContextCompat.getColor(this, R.color.main_preference_color_3)
        val iconTint = ContextCompat.getColor(this, R.color.main_preference_on_color_3)
        icon.background.setTint(bgTint)
        icon.imageTintList = android.content.res.ColorStateList.valueOf(iconTint)

        title.text = getString(R.string.glyph_notif_title).uppercase()
        summary.text = getString(R.string.glyph_notif_summary)

        row.setOnClickListener {
            startActivity(Intent(this, GlyphNotifSettingsActivity::class.java))
            applyBackAnimation()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        applyForwardAnimation()
        return true
    }
}
