package com.nothingxpert

import android.content.Intent
import android.os.Bundle
import androidx.activity.addCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar

class AppsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this) {
            finish()
            applyForwardAnimation()
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_apps)

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
        toolbar.title = getString(R.string.pref_category_apps)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Configure App Lock row
        val row = findViewById<android.view.View>(R.id.row_app_lock)
        val icon = row.findViewById<android.widget.ImageView>(R.id.category_icon)
        val title = row.findViewById<android.widget.TextView>(R.id.category_title)
        val summary = row.findViewById<android.widget.TextView>(R.id.category_summary)

        icon.setImageResource(R.drawable.ic_settings_apps)
        val bgTint = androidx.core.content.ContextCompat.getColor(this, R.color.main_preference_color_1)
        val iconTint = androidx.core.content.ContextCompat.getColor(this, R.color.main_preference_on_color_1)
        icon.background.setTint(bgTint)
        icon.imageTintList = android.content.res.ColorStateList.valueOf(iconTint)

        title.text = getString(R.string.app_lock_title).uppercase()
        summary.text = getString(R.string.app_lock_summary)

        row.setOnClickListener {
            startActivity(Intent(this, AppLockSettingsActivity::class.java))
            applyBackAnimation()
        }

        // Configure Undismissable Notifications row
        val notifRow = findViewById<android.view.View>(R.id.row_undismissable_notifs)
        val notifIcon = notifRow.findViewById<android.widget.ImageView>(R.id.category_icon)
        val notifTitle = notifRow.findViewById<android.widget.TextView>(R.id.category_title)
        val notifSummary = notifRow.findViewById<android.widget.TextView>(R.id.category_summary)

        notifIcon.setImageResource(R.drawable.ic_settings_apps)
        val notifBgTint = androidx.core.content.ContextCompat.getColor(this, R.color.main_preference_color_2)
        val notifIconTint = androidx.core.content.ContextCompat.getColor(this, R.color.main_preference_on_color_2)
        notifIcon.background.setTint(notifBgTint)
        notifIcon.imageTintList = android.content.res.ColorStateList.valueOf(notifIconTint)

        notifTitle.text = getString(R.string.pref_undismissable_notifs_title).uppercase()
        notifSummary.text = getString(R.string.pref_undismissable_notifs_summary)

        notifRow.setOnClickListener {
            startActivity(Intent(this, UndismissableNotifsSettingsActivity::class.java))
            applyBackAnimation()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        applyForwardAnimation()
        return true
    }
}
