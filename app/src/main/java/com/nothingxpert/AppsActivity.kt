package com.nothingxpert

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar

class AppsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        if (prefs.getBoolean("pref_amoled_theme", false)) {
            setTheme(R.style.Theme_NothingXpert_Amoled)
        }
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_apps)

        val appBar = findViewById<AppBarLayout>(R.id.appbar)
        ViewCompat.setOnApplyWindowInsetsListener(appBar) { view, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.updatePadding(top = statusBarInsets.top)
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
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
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
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        return true
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }
}
