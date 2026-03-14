package com.nothingxpert

import android.app.Application
import android.content.Context
import com.google.android.material.color.DynamicColors

class NothingXpertApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.setLocale(base))
    }

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        // Ensure locale is set
        LocaleHelper.setLocale(this)
    }
}
