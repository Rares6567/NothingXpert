package com.nothingxpert.ui.preferences

import android.content.res.Resources

object MiscUtils {
    @JvmStatic
    fun dpToPx(dp: Int): Int {
        return (dp * Resources.getSystem().displayMetrics.density).toInt()
    }
}
