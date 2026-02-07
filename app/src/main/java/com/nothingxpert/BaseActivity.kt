package com.nothingxpert

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

abstract class BaseActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.setLocale(newBase))
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
}
