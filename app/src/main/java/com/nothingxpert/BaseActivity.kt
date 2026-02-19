package com.nothingxpert

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

abstract class BaseActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.setLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply AMOLED theme if enabled (must be before super.onCreate())
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        if (prefs.getBoolean("pref_amoled_theme", false)) {
            setTheme(R.style.Theme_NothingXpert_Amoled)
        }
        super.onCreate(savedInstanceState)
    }

    /**
     * Applies the appropriate Nothing-style font (Ndot57 for EN, VT323 for TR) to all TextViews
     * in the given view hierarchy.
     */
    protected fun applyNothingFontToView(view: View?) {
        view ?: return
        val typeface = FontHelper.getNothingFont(this)
        applyFontToViewRecursive(view, typeface)
    }

    private fun applyFontToViewRecursive(view: View, typeface: Typeface) {
        if (view is TextView) {
            view.typeface = typeface
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyFontToViewRecursive(view.getChildAt(i), typeface)
            }
        }
    }

    /**
     * Apply the standard slide animation for navigating forward
     */
    @Suppress("DEPRECATION")
    protected fun applyForwardAnimation() {
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }

    /**
     * Apply the standard slide animation for navigating backward
     */
    @Suppress("DEPRECATION")
    protected fun applyBackAnimation() {
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }
}
