package com.nothingxpert

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
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
        
        // Animate on startup
        animateTitleOnStartup()
        animateCardsOnStartup()
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

}
