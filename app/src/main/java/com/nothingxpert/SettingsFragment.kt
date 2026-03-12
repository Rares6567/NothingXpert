package com.nothingxpert

import android.app.WallpaperManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.preference.Preference
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SettingsFragment : BasePreferenceFragment(), SharedPreferences.OnSharedPreferenceChangeListener {

    private val segmentExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesMode = Context.MODE_PRIVATE
        preferenceManager.sharedPreferencesName = "${requireContext().packageName}_preferences"
        setPreferencesFromResource(R.xml.preferences, rootKey)
        makePrefsReadable(requireContext())
        updateDepthStatus()
    }

    override fun onResume() {
        super.onResume()
        preferenceScreen.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onPause() {
        preferenceScreen.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        mainHandler.postDelayed({
            if (isAdded) makePrefsReadable(requireContext())
        }, 100)

        if (key == "pref_depth_wallpaper_enabled") {
            val enabled = sharedPreferences?.getBoolean(key, false) ?: false
            if (enabled) {
                requestWallpaperPermissionAndExtract()
            } else {
                // Clear the extracted image
                sharedPreferences?.edit()?.remove("pref_depth_wallpaper_image_path")?.apply()
                updateDepthStatus()
                mainHandler.postDelayed({
                    if (isAdded) makePrefsReadable(requireContext())
                }, 100)
            }
        } else if (key == "pref_depth_wallpaper_crop") {
            val enabled = sharedPreferences?.getBoolean("pref_depth_wallpaper_enabled", false) ?: false
            if (enabled) {
                extractWallpaperSubject()
            }
        }
    }

    private fun requestWallpaperPermissionAndExtract() {
        // Extraction now uses a root file fallback first, so no runtime permission gate is needed.
        extractWallpaperSubject()
    }

    private fun extractWallpaperSubject() {
        val ctx = context ?: return

        val statusPref = findPreference<Preference>("pref_depth_wallpaper_status")
        mainHandler.post {
            statusPref?.summary = getString(R.string.pref_depth_wallpaper_extracting)
        }

        segmentExecutor.submit {
            try {
                // Ensure ML Kit subject segmentation module is installed
                val segmenterClient = SubjectSegmentation.getClient(
                    SubjectSegmenterOptions.Builder()
                        .enableForegroundBitmap()
                        .build()
                )
                val moduleInstallClient = ModuleInstall.getClient(ctx)
                val installRequest = ModuleInstallRequest.newBuilder()
                    .addApi(segmenterClient)
                    .build()

                Log.d("NothingXpert", "Checking ML Kit subject segmentation module...")
                val installResponse = Tasks.await(moduleInstallClient.installModules(installRequest), 30, TimeUnit.SECONDS)
                Log.d("NothingXpert", "Module install response: areModulesAlreadyInstalled=${installResponse.areModulesAlreadyInstalled()}")

                val wallpaperBitmap = loadWallpaperBitmap(ctx)

                val bitmap = wallpaperBitmap
                if (bitmap == null) {
                    mainHandler.post {
                        if (isAdded) {
                            statusPref?.summary = getString(R.string.pref_depth_wallpaper_no_wallpaper)
                            Toast.makeText(ctx, R.string.pref_depth_wallpaper_no_wallpaper, Toast.LENGTH_SHORT).show()
                        }
                    }
                    return@submit
                }

                val inputImage = InputImage.fromBitmap(bitmap, 0)

                Log.d("NothingXpert", "Running subject segmentation...")
                val result = Tasks.await(segmenterClient.process(inputImage), 60, TimeUnit.SECONDS)
                val foreground = result.foregroundBitmap

                if (foreground == null) {
                    mainHandler.post {
                        if (isAdded) {
                            statusPref?.summary = getString(R.string.pref_depth_wallpaper_no_subject)
                            Toast.makeText(ctx, R.string.pref_depth_wallpaper_no_subject, Toast.LENGTH_SHORT).show()
                        }
                    }
                    bitmap.recycle()
                    return@submit
                }

                val prefs = preferenceManager.sharedPreferences
                if (prefs == null) {
                    foreground.recycle()
                    bitmap.recycle()
                    return@submit
                }
                val cropPercent = prefs.getInt("pref_depth_wallpaper_crop", 62)
                val croppedForeground = applyForegroundCrop(foreground, cropPercent)

                // Save the foreground cutout
                val dpCtx = ctx.createDeviceProtectedStorageContext()
                val file = File(dpCtx.filesDir, RemotePrefProvider.DEPTH_SUBJECT_FILE)
                FileOutputStream(file).use { out ->
                    croppedForeground.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                croppedForeground.recycle()
                bitmap.recycle()

                val subjectUri = RemotePrefProvider.buildDepthSubjectUri(System.currentTimeMillis())
                prefs.edit().putString("pref_depth_wallpaper_image_path", subjectUri).apply()

                mainHandler.post {
                    if (isAdded) {
                        makePrefsReadable(ctx)
                        updateDepthStatus()
                        Toast.makeText(ctx, R.string.pref_depth_wallpaper_subject_extracted, Toast.LENGTH_SHORT).show()
                    }
                }

                segmenterClient.close()
            } catch (e: Throwable) {
                Log.e("NothingXpert", "Subject extraction failed", e)
                mainHandler.post {
                    if (isAdded) {
                        statusPref?.summary = getString(R.string.pref_depth_wallpaper_extraction_failed)
                        Toast.makeText(ctx, "${getString(R.string.pref_depth_wallpaper_extraction_failed)}: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun applyForegroundCrop(source: Bitmap, visiblePercent: Int): Bitmap {
        val pct = visiblePercent.coerceIn(30, 100)
        if (pct >= 100) return source

        val visibleHeight = ((source.height * pct) / 100f).toInt().coerceIn(1, source.height)
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(output)
        val src = android.graphics.Rect(0, 0, source.width, visibleHeight)
        val dst = android.graphics.Rect(0, 0, source.width, visibleHeight)
        canvas.drawBitmap(source, src, dst, null)
        source.recycle()
        return output
    }

    private fun loadWallpaperBitmap(ctx: Context): Bitmap? {
        val wallpaperManager = WallpaperManager.getInstance(ctx)
        val dpCtx = ctx.createDeviceProtectedStorageContext()

        listOf(
            RemotePrefProvider.DEPTH_RENDERED_LOCK_WALLPAPER_FILE,
            RemotePrefProvider.DEPTH_RENDERED_SYSTEM_WALLPAPER_FILE
        ).forEach { fileName ->
            val file = File(dpCtx.filesDir, fileName)
            if (file.exists() && file.canRead()) {
                val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    Log.d("NothingXpert", "Got wallpaper via rendered cache: $fileName")
                    return normalizeBitmapForProcessing(bitmap)
                }
            }
        }

        // Method 1: ask WallpaperManager for the same bitmap/crop SystemUI uses.
        listOf(WallpaperManager.FLAG_LOCK, WallpaperManager.FLAG_SYSTEM).forEach { which ->
            tryLoadManagedWallpaperBitmap(wallpaperManager, which)?.let {
                return matchBitmapToDisplay(ctx, it)
            }
        }

        // Method 2: root read from raw wallpaper files as a last resort.
        try {
            val candidates = listOf(
                "/data/system/users/0/wallpaper_lock",
                "/data/system/users/0/wallpaper_lock_orig",
                "/data/system/users/0/wallpaper",
                "/data/system/users/0/wallpaper_orig"
            )
            for (src in candidates) {
                val tmp = File(ctx.cacheDir, "wp_${System.nanoTime()}.img")
                try {
                    val cmd = "if [ -r \"$src\" ]; then cat \"$src\" > \"${tmp.absolutePath}\"; fi"
                    val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                    p.waitFor()
                    if (tmp.exists() && tmp.length() > 0) {
                        val bitmap = android.graphics.BitmapFactory.decodeFile(tmp.absolutePath)
                        tmp.delete()
                        if (bitmap != null) {
                            Log.d("NothingXpert", "Got wallpaper via root file: $src")
                            return matchBitmapToDisplay(ctx, normalizeBitmapForProcessing(bitmap))
                        }
                    } else {
                        tmp.delete()
                    }
                } catch (_: Throwable) {
                    tmp.delete()
                }
            }
        } catch (e: Throwable) {
            Log.d("NothingXpert", "Root wallpaper read failed: ${e.message}")
        }

        return null
    }

    private fun tryLoadManagedWallpaperBitmap(
        wallpaperManager: WallpaperManager,
        which: Int
    ): Bitmap? {
        try {
            val method = wallpaperManager.javaClass.getDeclaredMethod(
                "getBitmapAsUser",
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType
            )
            method.isAccessible = true
            val bitmap = method.invoke(
                wallpaperManager,
                currentUserId(),
                false,
                which,
                true
            ) as? Bitmap
            if (bitmap != null) {
                Log.d("NothingXpert", "Got wallpaper via hidden getBitmapAsUser($which)")
                return normalizeBitmapForProcessing(bitmap)
            }
        } catch (e: Throwable) {
            Log.d("NothingXpert", "getBitmapAsUser($which) failed: ${e.message}")
        }

        try {
            val drawable = wallpaperManager.getDrawable(which) ?: wallpaperManager.peekDrawable(which)
            val bitmap = drawableToBitmap(drawable)
            if (bitmap != null) {
                Log.d("NothingXpert", "Got wallpaper via getDrawable($which)")
                return normalizeBitmapForProcessing(bitmap)
            }
        } catch (e: Throwable) {
            Log.d("NothingXpert", "getDrawable($which) failed: ${e.message}")
        }

        try {
            val wallpaperFile = wallpaperManager.getWallpaperFile(which)
            if (wallpaperFile != null) {
                wallpaperFile.use { fd ->
                    val bitmap = android.graphics.BitmapFactory.decodeFileDescriptor(fd.fileDescriptor)
                    if (bitmap != null) {
                        Log.d("NothingXpert", "Got wallpaper via getWallpaperFile($which)")
                        return normalizeBitmapForProcessing(bitmap)
                    }
                }
            }
        } catch (e: Throwable) {
            Log.d("NothingXpert", "getWallpaperFile($which) failed: ${e.message}")
        }

        return null
    }

    private fun drawableToBitmap(drawable: android.graphics.drawable.Drawable?): Bitmap? {
        drawable ?: return null

        if (drawable is BitmapDrawable) {
            val bitmap = drawable.bitmap
            if (bitmap != null && !bitmap.isRecycled) {
                val config = if (bitmap.config == Bitmap.Config.HARDWARE) {
                    Bitmap.Config.ARGB_8888
                } else {
                    bitmap.config
                }
                return bitmap.copy(config, false) ?: bitmap
            }
        }

        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun normalizeBitmapForProcessing(bitmap: Bitmap): Bitmap {
        if (bitmap.config != Bitmap.Config.HARDWARE) {
            return bitmap
        }

        return bitmap.copy(Bitmap.Config.ARGB_8888, false)?.also {
            bitmap.recycle()
        } ?: bitmap
    }

    private fun matchBitmapToDisplay(ctx: Context, bitmap: Bitmap): Bitmap {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
            ?: return bitmap
        val bounds = wm.currentWindowMetrics.bounds
        val targetWidth = bounds.width().coerceAtLeast(1)
        val targetHeight = bounds.height().coerceAtLeast(1)

        if (bitmap.width == targetWidth && bitmap.height == targetHeight) {
            return bitmap
        }

        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true).also { scaled ->
            if (scaled != bitmap && !bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private fun currentUserId(): Int {
        return try {
            val userHandle = android.os.Process.myUserHandle()
            val method = userHandle.javaClass.getDeclaredMethod("getIdentifier")
            method.isAccessible = true
            method.invoke(userHandle) as? Int ?: 0
        } catch (_: Throwable) {
            0
        }
    }

    private fun updateDepthStatus() {
        val prefs = preferenceManager.sharedPreferences ?: return
        val enabled = prefs.getBoolean("pref_depth_wallpaper_enabled", false)
        val path = prefs.getString("pref_depth_wallpaper_image_path", "")
        val statusPref = findPreference<Preference>("pref_depth_wallpaper_status") ?: return

        val hasReadableSubject = when {
            path.isNullOrEmpty() -> false
            path.startsWith("content://") -> {
                try {
                    requireContext().contentResolver.openInputStream(Uri.parse(path))?.use { stream ->
                        val header = ByteArray(1)
                        stream.read(header)
                    }
                    true
                } catch (_: Throwable) {
                    false
                }
            }
            else -> File(path).exists()
        }

        statusPref.summary = when {
            !enabled -> getString(R.string.pref_depth_wallpaper_status_disabled)
            !hasReadableSubject -> getString(R.string.pref_depth_wallpaper_no_subject)
            else -> getString(R.string.pref_depth_wallpaper_status_ready)
        }
    }

    private fun makePrefsReadable(context: Context) {
        PreferenceUtils.fixPermissions(context)
        PrefsUtil.ensurePrefsAccessible(context)
    }
}
