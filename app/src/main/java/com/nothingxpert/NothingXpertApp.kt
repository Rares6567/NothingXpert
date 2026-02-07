package com.nothingxpert

import android.app.Application
import android.content.Context

class NothingXpertApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.setLocale(base))
    }

    override fun onCreate() {
        super.onCreate()
        // Ensure locale is set
        LocaleHelper.setLocale(this)
    }
}
