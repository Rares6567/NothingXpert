package com.nothingxpert

import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.addCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar

class UndismissableNotifsSettingsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this) {
            finish()
            applyForwardAnimation()
        }

        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContentView(R.layout.activity_settings) // Reuse settings layout

        // Handle status bar insets
        val appBar = findViewById<AppBarLayout>(R.id.appbar)
        ViewCompat.setOnApplyWindowInsetsListener(appBar) { view, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.updatePadding(top = statusBarInsets.top)
            insets
        }

        // Handle navigation bar / gesture insets so the last preference row isn't overlapped
        val settingsContainer = findViewById<FrameLayout>(R.id.settings_container)
        ViewCompat.setOnApplyWindowInsetsListener(settingsContainer) { view, insets ->
            val navInsets = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.systemGestures()
            )
            view.updatePadding(bottom = navInsets.bottom)
            insets
        }

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        // Match other sub-screens: toolbar shows category name, content shows feature title.
        toolbar.setTitle(R.string.pref_category_apps)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, UndismissableNotifsSettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        applyForwardAnimation()
        return true
    }
}
