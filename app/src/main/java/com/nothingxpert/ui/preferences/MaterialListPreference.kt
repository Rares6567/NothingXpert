package com.nothingxpert.ui.preferences

import android.content.Context
import android.util.AttributeSet
import androidx.preference.ListPreference
import androidx.preference.PreferenceViewHolder
import com.nothingxpert.R

class MaterialListPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.dialogPreferenceStyle,
    defStyleRes: Int = 0
) : ListPreference(context, attrs, defStyleAttr, defStyleRes) {

    init {
        layoutResource = R.layout.custom_preference_switch
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        PreferenceUtils.setFirstAndLastItemMargin(holder)
        PreferenceUtils.setBackgroundResource(this, holder)
    }
}
