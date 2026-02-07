package com.nothingxpert

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat

object FontHelper {
    
    private var ndotTypeface: Typeface? = null
    private var vt323Typeface: Typeface? = null
    
    /**
     * Returns the appropriate Nothing-style font based on current language.
     * Uses Ndot57 for English, VT323 for Turkish (which has Turkish character support).
     * Also checks system language if app is set to "System Default".
     */
    fun getNothingFont(context: Context): Typeface {
        return if (LocaleHelper.isTurkish(context)) {
            getVT323(context)
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
     * Returns VT323 font (dot matrix style with Turkish support)
     */
    fun getVT323(context: Context): Typeface {
        if (vt323Typeface == null) {
            vt323Typeface = ResourcesCompat.getFont(context, R.font.vt323)
        }
        return vt323Typeface ?: Typeface.DEFAULT
    }
    
    /**
     * Clears cached typefaces (call when language changes)
     */
    fun clearCache() {
        ndotTypeface = null
        vt323Typeface = null
    }
}
