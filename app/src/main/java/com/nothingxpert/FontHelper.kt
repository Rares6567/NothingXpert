package com.nothingxpert

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat

object FontHelper {
    
    private var ndotTypeface: Typeface? = null
    private var ndot77Typeface: Typeface? = null

    /**
     * Returns the appropriate Nothing-style font based on current language.
     * Uses Ndot57 for English, Ndot77JPExtended for Turkish (which has Turkish character support).
     * Also checks system language if app is set to "System Default".
     */
    fun getNothingFont(context: Context): Typeface {
        return if (LocaleHelper.isTurkish(context)) {
            getNdot77(context)
        } else {
            getNdot57(context)
        }
    }

    /**
     * Returns Ndot57 font (original Nothing font - English only)
     */
    fun getNdot57(context: Context): Typeface {
        if (ndotTypeface == null) {
            ndotTypeface = ResourcesCompat.getFont(context, R.font.ndot57)
        }
        return ndotTypeface ?: Typeface.DEFAULT
    }

    /**
     * Returns Ndot77JPExtended font (Nothing-style dot font with Turkish character support)
     */
    fun getNdot77(context: Context): Typeface {
        if (ndot77Typeface == null) {
            ndot77Typeface = ResourcesCompat.getFont(context, R.font.ndot77jpextended)
        }
        return ndot77Typeface ?: Typeface.DEFAULT
    }

    /**
     * Applies the locale-appropriate Nothing font to all TextViews in a view hierarchy.
     * Use this for adapter-inflated views that aren't covered by BaseActivity.
     */
    fun applyToView(context: Context, view: View) {
        val typeface = getNothingFont(context)
        applyRecursive(view, typeface)
    }

    private fun applyRecursive(view: View, typeface: Typeface) {
        if (view is TextView) {
            view.setTypeface(typeface, Typeface.NORMAL)
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyRecursive(view.getChildAt(i), typeface)
            }
        }
    }

    /**
     * Clears cached typefaces (call when language changes)
     */
    fun clearCache() {
        ndotTypeface = null
        ndot77Typeface = null
    }
}
