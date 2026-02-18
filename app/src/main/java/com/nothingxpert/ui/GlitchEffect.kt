package com.nothingxpert.ui

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import java.util.Random

/**
 * Applies a "glitch" effect to a TextView by randomly replacing characters
 * with symbols from a glitch alphabet, then restoring them.
 */
object GlitchEffect {
    private val GLITCH_CHARS = "!@#$%^&*()_+-=[]{}|;':,.<>/?`~"
    private val random = Random()
    private val handler = Handler(Looper.getMainLooper())

    fun apply(textView: TextView, duration: Long = 600) {
        val originalText = textView.text.toString()
        val length = originalText.length
        if (length == 0) return

        val startTime = System.currentTimeMillis()
        val steps = 15
        val stepDuration = duration / steps

        val runnable = object : Runnable {
            var step = 0
            
            override fun run() {
                if (step >= steps) {
                    textView.text = originalText
                    textView.setTextColor(textView.textColors) // Restore original colors if changed
                    return
                }

                val sb = StringBuilder()
                for (i in 0 until length) {
                    if (random.nextBoolean()) {
                        // 50% chance to glitch a character
                         sb.append(GLITCH_CHARS[random.nextInt(GLITCH_CHARS.length)])
                    } else {
                        sb.append(originalText[i])
                    }
                }
                textView.text = sb.toString()
                
                // Optional: Random color flicker (e.g., Red/Cyan for retro glitch)
                if (step % 2 == 0) {
                   // textView.setTextColor(Color.WHITE)
                } else {
                   // textView.setTextColor(Color.LTGRAY)
                }

                step++
                handler.postDelayed(this, stepDuration)
            }
        }
        handler.post(runnable)
    }
}
