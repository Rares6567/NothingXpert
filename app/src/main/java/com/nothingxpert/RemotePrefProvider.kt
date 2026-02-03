package com.nothingxpert

import com.crossbowffs.remotepreferences.RemotePreferenceFile
import com.crossbowffs.remotepreferences.RemotePreferenceProvider

class RemotePrefProvider : RemotePreferenceProvider(
    AUTHORITY,
    arrayOf(RemotePreferenceFile(PREF_FILE, true))
) {
    companion object {
        const val AUTHORITY = "com.nothingxpert"
        const val PREF_FILE = "com.nothingxpert_preferences"
    }
}
