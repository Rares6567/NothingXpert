package com.nothingxpert

import android.os.Binder
import com.crossbowffs.remotepreferences.RemotePreferenceFile
import com.crossbowffs.remotepreferences.RemotePreferenceProvider

class RemotePrefProvider : RemotePreferenceProvider(
    AUTHORITY,
    arrayOf(RemotePreferenceFile(PREF_FILE, true))
) {
    override fun checkAccess(prefFileName: String, prefKey: String, write: Boolean): Boolean {
        val ctx = context ?: return false
        val appPackage = ctx.packageName
        val caller = callingPackage
        if (caller != null) {
            return when (caller) {
                appPackage -> true
                SYSTEMUI_PACKAGE, SYSTEM_SERVER_PACKAGE -> !write
                else -> false
            }
        }

        val callingUid = Binder.getCallingUid()
        val packages = ctx.packageManager.getPackagesForUid(callingUid).orEmpty().toSet()
        if (packages.contains(appPackage)) return true
        if (write) return false
        return packages.contains(SYSTEMUI_PACKAGE) || packages.contains(SYSTEM_SERVER_PACKAGE)
    }

    companion object {
        const val AUTHORITY = "com.nothingxpert"
        const val PREF_FILE = "com.nothingxpert_preferences"
        private const val SYSTEMUI_PACKAGE = "com.android.systemui"
        private const val SYSTEM_SERVER_PACKAGE = "android"
    }
}
