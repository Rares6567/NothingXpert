package com.nothingxpert

import android.content.Context
import java.io.File

object PrefsUtil {
    private fun relaxDir(dir: File) {
        if (!dir.exists()) return
        dir.setReadable(true, false)
        dir.setExecutable(true, false)
    }

    fun makeWorldReadable(context: Context) {
        try {
            val dataDir = File(context.applicationInfo.dataDir)
            val prefsDir = File(dataDir, "shared_prefs")
            val file = File(prefsDir, "${context.packageName}_preferences.xml")
            relaxDir(dataDir)
            relaxDir(dataDir.parentFile ?: File("/data/user/0"))
            if (prefsDir.exists()) relaxDir(prefsDir)
            if (file.exists()) {
                file.setReadable(true, false)
                file.setWritable(true, false)
            }
        } catch (_: Throwable) {
        }
    }

    fun syncToDeviceProtected(context: Context) {
        try {
            val deContext = context.createDeviceProtectedStorageContext()
            val cePrefs = File("${context.applicationInfo.dataDir}/shared_prefs/${context.packageName}_preferences.xml")
            val deDir = File(deContext.dataDir, "shared_prefs")
            val dePrefs = File(deDir, "${context.packageName}_preferences.xml")
            if (!deDir.exists()) deDir.mkdirs()
            relaxDir(deContext.dataDir)
            relaxDir(deContext.dataDir.parentFile ?: File("/data/user_de/0"))
            relaxDir(deDir)
            if (cePrefs.exists()) {
                cePrefs.copyTo(dePrefs, overwrite = true)
                dePrefs.setReadable(true, false)
                dePrefs.setWritable(true, false)
            }
        } catch (_: Throwable) {
        }
    }

    fun ensurePrefsAccessible(context: Context) {
        makeWorldReadable(context)
        syncToDeviceProtected(context)
    }
}
