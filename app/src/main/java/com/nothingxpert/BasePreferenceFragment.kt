package com.nothingxpert

import android.content.Context
import androidx.preference.PreferenceFragmentCompat

abstract class BasePreferenceFragment : PreferenceFragmentCompat() {

    override fun onAttach(context: Context) {
        super.onAttach(LocaleHelper.setLocale(context))
    }
}
