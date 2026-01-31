package com.nothingxpert.ui.preferences

import android.view.ViewGroup
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import androidx.recyclerview.widget.RecyclerView
import com.nothingxpert.R

object PreferenceUtils {
    @JvmStatic
    fun setFirstAndLastItemMargin(holder: PreferenceViewHolder) {
        val layoutParams = holder.itemView.layoutParams as ViewGroup.MarginLayoutParams
        
        // Cast to RecyclerView.ViewHolder to access RecyclerView methods
        val rvHolder = holder as RecyclerView.ViewHolder
        val position = rvHolder.adapterPosition

        // Set margin for the first item
        if (position == 0) {
            layoutParams.topMargin = MiscUtils.dpToPx(12)
        } else if (position != RecyclerView.NO_POSITION) {
            // Set margin for all other items
            layoutParams.topMargin = 0
            layoutParams.bottomMargin = MiscUtils.dpToPx(2)
        }

        holder.itemView.layoutParams = layoutParams
    }

    @JvmStatic
    fun setBackgroundResource(preference: Preference, holder: PreferenceViewHolder) {
        val parent = preference.parent ?: return

        val visiblePreferences = mutableListOf<Preference>()

        for (i in 0 until parent.preferenceCount) {
            val pref = parent.getPreference(i)
            if (pref.key != null && pref.isVisible && pref !is MaterialPreferenceCategory) {
                visiblePreferences.add(pref)
            }
        }

        val itemCount = visiblePreferences.size
        val position = visiblePreferences.indexOf(preference)

        when {
            itemCount == 1 -> {
                holder.itemView.setBackgroundResource(R.drawable.container_single)
            }
            itemCount > 1 -> {
                when (position) {
                    0 -> holder.itemView.setBackgroundResource(R.drawable.container_top)
                    itemCount - 1 -> holder.itemView.setBackgroundResource(R.drawable.container_bottom)
                    else -> holder.itemView.setBackgroundResource(R.drawable.container_mid)
                }
            }
        }

        holder.itemView.clipToOutline = true
        holder.isDividerAllowedAbove = false
        holder.isDividerAllowedBelow = false
    }
}
