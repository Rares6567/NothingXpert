package com.nothingxpert.ui.preferences

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceViewHolder
import androidx.recyclerview.widget.RecyclerView
import com.nothingxpert.R

class MaterialPreferenceCategory @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.preferenceCategoryStyle,
    defStyleRes: Int = 0
) : PreferenceCategory(context, attrs, defStyleAttr, defStyleRes) {

    init {
        layoutResource = R.layout.custom_preference_category
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        holder.isDividerAllowedAbove = false
        holder.isDividerAllowedBelow = false

        val layoutParams = holder.itemView.layoutParams as ViewGroup.MarginLayoutParams
        
        // Cast to RecyclerView.ViewHolder to access RecyclerView methods
        val rvHolder = holder as RecyclerView.ViewHolder
        val position = rvHolder.adapterPosition

        if (position == 0) {
            layoutParams.topMargin = MiscUtils.dpToPx(12)
            setMinHeight(true, holder.itemView)
        } else if (position != RecyclerView.NO_POSITION) {
            layoutParams.topMargin = 0
            setMinHeight(false, holder.itemView)
        }

        holder.itemView.layoutParams = layoutParams
    }

    private fun setMinHeight(zero: Boolean, view: View) {
        if (zero) {
            view.minimumHeight = 0
        } else {
            val typedValue = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.listPreferredItemHeight, typedValue, true)) {
                val minHeight = TypedValue.complexToDimensionPixelSize(
                    typedValue.data,
                    context.resources.displayMetrics
                )
                view.minimumHeight = minHeight
            }
        }
    }
}
