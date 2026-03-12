package com.nothingxpert

import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import com.crossbowffs.remotepreferences.RemotePreferenceFile
import com.crossbowffs.remotepreferences.RemotePreferenceProvider
import java.io.File
import java.io.FileNotFoundException

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

    @Throws(FileNotFoundException::class)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val path = uri.path
        val file = when (path) {
            "/$DEPTH_SUBJECT_PATH" -> resolveDpFile(DEPTH_SUBJECT_FILE)
            "/$DEPTH_RENDERED_LOCK_WALLPAPER_PATH" -> resolveDpFile(DEPTH_RENDERED_LOCK_WALLPAPER_FILE)
            "/$DEPTH_RENDERED_SYSTEM_WALLPAPER_PATH" -> resolveDpFile(DEPTH_RENDERED_SYSTEM_WALLPAPER_FILE)
            else -> null
        }

        if (file != null) {
            val wantsWrite = mode.contains("w")
            if (wantsWrite) {
                if (!canWriteFromCaller()) {
                    throw SecurityException("Caller not allowed")
                }
                file.parentFile?.mkdirs()
                return ParcelFileDescriptor.open(
                    file,
                    ParcelFileDescriptor.MODE_CREATE or
                        ParcelFileDescriptor.MODE_TRUNCATE or
                        ParcelFileDescriptor.MODE_WRITE_ONLY
                )
            }

            if (!canReadFromCaller()) {
                throw SecurityException("Caller not allowed")
            }
            if (!file.exists()) {
                throw FileNotFoundException(file.absolutePath)
            }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        return super.openFile(uri, mode)
    }

    override fun getType(uri: Uri): String? {
        if (uri.path == "/$DEPTH_SUBJECT_PATH" ||
            uri.path == "/$DEPTH_RENDERED_LOCK_WALLPAPER_PATH" ||
            uri.path == "/$DEPTH_RENDERED_SYSTEM_WALLPAPER_PATH"
        ) {
            return "image/png"
        }
        return super.getType(uri)
    }

    private fun resolveDpFile(fileName: String): File {
        val ctx = context ?: throw FileNotFoundException("No context")
        val dpCtx = ctx.createDeviceProtectedStorageContext()
        return File(dpCtx.filesDir, fileName)
    }

    private fun canReadFromCaller(): Boolean {
        val ctx = context ?: return false
        val appPackage = ctx.packageName
        val caller = callingPackage
        if (caller != null) {
            return caller == appPackage || caller == SYSTEMUI_PACKAGE || caller == SYSTEM_SERVER_PACKAGE
        }
        val callingUid = Binder.getCallingUid()
        val packages = ctx.packageManager.getPackagesForUid(callingUid).orEmpty().toSet()
        return packages.contains(appPackage) ||
            packages.contains(SYSTEMUI_PACKAGE) ||
            packages.contains(SYSTEM_SERVER_PACKAGE)
    }

    private fun canWriteFromCaller(): Boolean {
        val ctx = context ?: return false
        val appPackage = ctx.packageName
        val caller = callingPackage
        if (caller != null) {
            return caller == appPackage || caller == SYSTEMUI_PACKAGE
        }
        val callingUid = Binder.getCallingUid()
        val packages = ctx.packageManager.getPackagesForUid(callingUid).orEmpty().toSet()
        return packages.contains(appPackage) || packages.contains(SYSTEMUI_PACKAGE)
    }

    companion object {
        const val AUTHORITY = "com.nothingxpert"
        const val PREF_FILE = "com.nothingxpert_preferences"
        const val DEPTH_SUBJECT_FILE = "depth_wallpaper_subject.png"
        const val DEPTH_SUBJECT_PATH = "depth_subject"
        const val DEPTH_RENDERED_LOCK_WALLPAPER_FILE = "depth_wallpaper_rendered_lock.png"
        const val DEPTH_RENDERED_LOCK_WALLPAPER_PATH = "depth_wallpaper_rendered_lock"
        const val DEPTH_RENDERED_SYSTEM_WALLPAPER_FILE = "depth_wallpaper_rendered_system.png"
        const val DEPTH_RENDERED_SYSTEM_WALLPAPER_PATH = "depth_wallpaper_rendered_system"
        const val DEPTH_SUBJECT_URI = "content://$AUTHORITY/$DEPTH_SUBJECT_PATH"
        const val DEPTH_SUBJECT_VERSION_PARAM = "v"
        private const val SYSTEMUI_PACKAGE = "com.android.systemui"
        private const val SYSTEM_SERVER_PACKAGE = "android"

        fun buildDepthSubjectUri(version: Long): String {
            return Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath(DEPTH_SUBJECT_PATH)
                .appendQueryParameter(DEPTH_SUBJECT_VERSION_PARAM, version.toString())
                .build()
                .toString()
        }

        fun buildRenderedWallpaperUri(sourceFlag: Int): String {
            val path = when (sourceFlag) {
                android.app.WallpaperManager.FLAG_LOCK -> DEPTH_RENDERED_LOCK_WALLPAPER_PATH
                else -> DEPTH_RENDERED_SYSTEM_WALLPAPER_PATH
            }
            return Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath(path)
                .build()
                .toString()
        }
    }
}
