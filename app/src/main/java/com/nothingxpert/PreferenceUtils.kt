package com.nothingxpert

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import java.io.File

object PreferenceUtils {
    @SuppressLint("SetWorldReadable")
    fun fixPermissions(context: Context) {
        try {
            val packageName = context.packageName
            val fileName = "${packageName}_preferences.xml"
            // Use device protected storage path if possible, or fallback to standard data dir
            // The checks in HookEntry use /data/user_de/0/... so we should ensure that file is readable if it exists
            // But usually prefs are in /data/data/...
            
            val dataDir = context.applicationInfo.dataDir
            val prefsDir = File(dataDir, "shared_prefs")
            val prefsFile = File(prefsDir, "$fileName")
            
            if (prefsDir.exists()) {
                prefsDir.setReadable(true, false)
                prefsDir.setExecutable(true, false)
            }
            
            if (prefsFile.exists()) {
                val success = prefsFile.setReadable(true, false) // readable by all, ownerOnly=false
                if (!success) {
                    Log.w("NothingXpert", "Failed to set world readable: $prefsFile")
                } else {
                    Log.d("NothingXpert", "Fixed permissions for: $prefsFile")
                }
            }
            
            // Also try to fix permissions for device encrypted storage if it exists (where Xposed sometimes looks)
            try {
                 val userDeDir = File("/data/user_de/0/$packageName/shared_prefs/$fileName")
                 if (userDeDir.exists()) {
                     userDeDir.setReadable(true, false)
                 }
            } catch (_: Exception) {}
            
        } catch (e: Exception) {
            Log.e("NothingXpert", "Error fixing permissions", e)
        }
    }
}
