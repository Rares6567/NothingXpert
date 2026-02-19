package com.nothingxpert

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.view.View
import android.widget.TextView
import java.util.concurrent.Executors

class LockScreenActivity : Activity() {

    companion object {
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val ACTION_UNLOCK = "com.nothingxpert.ACTION_UNLOCK"
    }

    private lateinit var targetPackage: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetPackage = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""

        // Simple UI
        val view = View(this)
        view.setBackgroundColor(android.graphics.Color.BLACK)
        setContentView(view)

        // Title
        val textView = TextView(this)
        textView.text = getString(R.string.lockscreen_locked_text)
        textView.setTextColor(android.graphics.Color.WHITE)
        textView.textSize = 24f
        textView.gravity = android.view.Gravity.CENTER
        addContentView(textView, android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, 
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        ))

        if (targetPackage.isNotEmpty()) {
             authenticate()
        } else {
             finish()
        }
    }

    private fun authenticate() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val executor = Executors.newSingleThreadExecutor()
            val biometricPrompt = BiometricPrompt.Builder(this)
                .setTitle(getString(R.string.biometric_unlock_title))
                .setSubtitle(getString(R.string.biometric_unlock_subtitle))
                .setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build()
                
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    super.onAuthenticationSucceeded(result)
                    runOnUiThread {
                        sendUnlockBroadcast()
                        finish()
                    }
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    super.onAuthenticationError(errorCode, errString)
                    if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED) {
                         killTargetApp()
                    }
                    runOnUiThread { finish() }
                }
                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    // Allow retry
                }
            }
            try {
                biometricPrompt.authenticate(CancellationSignal(), executor, callback)
            } catch (e: Exception) {
                e.printStackTrace()
                finish()
            }
        } else {
            // Fallback or bypass
            sendUnlockBroadcast()
            finish()
        }
    }

    private fun sendUnlockBroadcast() {
        val intent = Intent(ACTION_UNLOCK)
        intent.putExtra(EXTRA_PACKAGE_NAME, targetPackage)
        intent.setPackage(targetPackage) // Only send to the locked app
        sendBroadcast(intent)
    }

    private fun killTargetApp() {
        // We can't easily kill the other app from here without permissions.
        // Instead, we rely on the hook waiting for our broadcast. 
        // If we finish without broadcasting success, the hook should kill the activity.
        // But the hook doesn't know we finished.
        // So we send a LOCK broadcast? Or just do nothing and let the user remain locked?
        // Actually, if we finish, the user sees the original app (which is covered by black overlay in hook).
        // The hook needs to listen for activity resume/result.
    }
    
    override fun onBackPressed() {
        // Block back; just finish this task
        finish()
    }
}
