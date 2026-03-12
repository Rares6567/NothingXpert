package com.nothingxpert.ui.preferences

import android.content.Context
import android.util.AttributeSet
import androidx.preference.PreferenceViewHolder
import androidx.preference.SeekBarPreference
import com.nothingxpert.R

class MaterialSeekBarPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.seekBarPreferenceStyle,
    defStyleRes: Int = 0
) : SeekBarPreference(context, attrs, defStyleAttr, defStyleRes) {

    init {
        layoutResource = R.layout.custom_preference_seekbar
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        PreferenceUtils.setFirstAndLastItemMargin(holder)
        PreferenceUtils.setBackgroundResource(this, holder)
    }
}
