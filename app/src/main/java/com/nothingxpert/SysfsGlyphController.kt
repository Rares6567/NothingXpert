package com.nothingxpert

import android.util.Log
import com.nothingxpert.util.RootShell

/**
 * Direct sysfs-based Glyph LED controller for Nothing Phone (1).
 *
 * Bypasses the Ketchum SDK entirely and writes to the aw210xx LED driver
 * sysfs nodes via root.  Used as a fallback when Ketchum is not installed.
 *
 * Phone (1) has 15 hardware LED channels (matching the Ketchum SDK):
 *   0     = A1 (Camera)
 *   1     = B1 (Diagonal)
 *   2-5   = C1-C4 (Battery segments)
 *   6     = E1 (Bottom)
 *   7-14  = D1_1-D1_8 (Center / USB dot ring)
 *
 * The 5 user-facing zones map onto those 15 channels.
 * The global brightness is set via `setting_br` (0-4095).
 */
object SysfsGlyphController {

    private const val TAG = "SysfsGlyphCtrl"
    private const val DEFAULT_BRIGHTNESS = 2625
    private const val MAX_BRIGHTNESS = 4095

    /** Number of hardware LED channels on Phone (1). */
    private const val PHONE1_CHANNEL_COUNT = 15

    /** Number of user-facing zones on Phone (1). */
    const val PHONE1_ZONE_COUNT = 5

    private enum class WriteMode { GLYPH_FRAME_CSV, GLYPH_FRAME_SPACE, INDIVIDUAL, ALL_WHITE }

    @Volatile
    private var sysfsBase: String? = null

    @Volatile
    private var available = false

    @Volatile
    private var writeMode: WriteMode = WriteMode.ALL_WHITE

    /**
     * Probe the sysfs tree for the aw210xx LED driver, determine the best
     * write strategy, and cache both.
     * Call once from a background thread.
     *
     * @return `true` if the driver was found.
     */
    fun init(): Boolean {
        val base = discoverSysfsBase()
        sysfsBase = base
        available = base != null
        if (available) {
            writeMode = probeWriteMode(base!!)
            Log.d(TAG, "aw210xx sysfs base: $base, writeMode=$writeMode")
        } else {
            Log.w(TAG, "aw210xx sysfs not found")
        }
        return available
    }

    /**
     * Try each write strategy with a zero frame to discover which one the driver accepts.
     */
    private fun probeWriteMode(base: String): WriteMode {
        val zeroFrame = IntArray(PHONE1_CHANNEL_COUNT)
        val csv = zeroFrame.joinToString(",")
        if (sysfsWrite("$base/glyph_frame", csv)) return WriteMode.GLYPH_FRAME_CSV

        val spaceSep = zeroFrame.joinToString(" ")
        if (sysfsWrite("$base/glyph_frame", spaceSep)) return WriteMode.GLYPH_FRAME_SPACE

        if (sysfsWrite("$base/led0_br", "0")) return WriteMode.INDIVIDUAL

        // all_white_leds_br is always available on Phone (1).
        return WriteMode.ALL_WHITE
    }

    fun isAvailable(): Boolean = available

    /**
     * Set the global LED brightness (0-4095).
     */
    fun setBrightness(value: Int) {
        val base = sysfsBase ?: return
        val clamped = value.coerceIn(0, MAX_BRIGHTNESS)
        sysfsWrite("$base/setting_br", clamped.toString())
    }

    /**
     * Activate specific zones at full intensity.
     *
     * @param zones boolean array of size [PHONE1_ZONE_COUNT].
     *              Index mapping:
     *              0 = A1 (Camera)
     *              1 = B1 (Diagonal)
     *              2 = C1-C4 (Battery)
     *              3 = D1 (Center / USB dot ring)
     *              4 = E1 (Bottom)
     */
    fun activateZones(zones: BooleanArray, brightness: Int = DEFAULT_BRIGHTNESS) {
        val base = sysfsBase ?: return
        setBrightness(brightness)
        val frame = expandZonesToChannels(zones)
        writeFrame(base, frame)
    }

    /**
     * Write a raw 15-channel frame (each value 0-4095).
     */
    fun setFrame(frame: IntArray, brightness: Int = DEFAULT_BRIGHTNESS) {
        val base = sysfsBase ?: return
        setBrightness(brightness)
        writeFrame(base, frame)
    }

    /**
     * Turn off all LEDs and reset brightness.
     */
    fun turnOff() {
        val base = sysfsBase ?: return
        // Explicitly zero out all_white_leds_br in case that's the active write path.
        sysfsWrite("$base/all_white_leds_br", "0")
        writeFrame(base, IntArray(PHONE1_CHANNEL_COUNT))
        sysfsWrite("$base/setting_br", "0")
    }

    /**
     * Expand 5 user-facing zone booleans into the 15-channel hardware frame.
     */
    private fun expandZonesToChannels(zones: BooleanArray): IntArray {
        val frame = IntArray(PHONE1_CHANNEL_COUNT)
        // Zone 0 → ch 0: A1 (Camera)
        if (zones.getOrElse(0) { false }) frame[0] = MAX_BRIGHTNESS
        // Zone 1 → ch 1: B1 (Diagonal)
        if (zones.getOrElse(1) { false }) frame[1] = MAX_BRIGHTNESS
        // Zone 2 → ch 2-5: C1-C4 (Battery)
        if (zones.getOrElse(2) { false }) {
            for (ch in 2..5) frame[ch] = MAX_BRIGHTNESS
        }
        // Zone 3 → ch 7-14: D1_1-D1_8 (Center / USB dot ring)
        if (zones.getOrElse(3) { false }) {
            for (ch in 7..14) frame[ch] = MAX_BRIGHTNESS
        }
        // Zone 4 → ch 6: E1 (Bottom)
        if (zones.getOrElse(4) { false }) frame[6] = MAX_BRIGHTNESS
        return frame
    }

    // ────────────────────────────────────────────

    private fun writeFrame(base: String, frame: IntArray) {
        when (writeMode) {
            WriteMode.GLYPH_FRAME_CSV -> {
                sysfsWrite("$base/glyph_frame", frame.joinToString(","))
            }
            WriteMode.GLYPH_FRAME_SPACE -> {
                sysfsWrite("$base/glyph_frame", frame.joinToString(" "))
            }
            WriteMode.INDIVIDUAL -> {
                for (i in frame.indices) {
                    sysfsWrite("$base/led${i}_br", frame[i].coerceIn(0, MAX_BRIGHTNESS).toString())
                }
            }
            WriteMode.ALL_WHITE -> {
                // all_white_leds_br is all-or-nothing: a single brightness for every LED.
                val anyActive = frame.any { it > 0 }
                sysfsWrite("$base/all_white_leds_br", if (anyActive) MAX_BRIGHTNESS.toString() else "0")
            }
        }
    }

    private fun sysfsWrite(path: String, value: String): Boolean {
        return try {
            val cmd = "echo ${RootShell.shQuote(value)} > ${RootShell.shQuote(path)}"
            val res = RootShell.exec(cmd, timeoutMs = 800L)
            if (res?.exitCode == 0) {
                true
            } else {
                Log.d(TAG, "sysfs write failed: $path <- $value (exit=${res?.exitCode})")
                false
            }
        } catch (e: Exception) {
            Log.d(TAG, "sysfs write error: $path: ${e.message}")
            false
        }
    }

    private fun discoverSysfsBase(): String? {
        // Try well-known paths first (fast).
        val candidates = listOf(
            "/sys/class/leds/aw210xx_led",
            "/sys/devices/platform/soc/984000.i2c/i2c-0/0-0020/leds/aw210xx_led",
            "/sys/devices/platform/soc/984000.i2c/i2c-0/0-003a/leds/aw210xx_led"
        )
        for (path in candidates) {
            val res = RootShell.exec("[ -d ${RootShell.shQuote(path)} ] && echo yes", timeoutMs = 800L)
            if (res?.stdout?.trim() == "yes") return path
        }

        // Fallback: search.
        val search = RootShell.exec(
            "find /sys/class/leds -maxdepth 1 -name 'aw210xx*' 2>/dev/null | head -1",
            timeoutMs = 2000L
        )
        val found = search?.stdout?.trim()
        if (!found.isNullOrBlank() && found.startsWith("/sys")) return found

        // Last resort: look for the setting_br attribute anywhere under aw210xx.
        val deep = RootShell.exec(
            "find /sys/devices -name 'setting_br' -path '*aw210xx*' 2>/dev/null | head -1",
            timeoutMs = 3000L
        )
        val deepPath = deep?.stdout?.trim()
        if (!deepPath.isNullOrBlank() && deepPath.startsWith("/sys")) {
            // Return the parent directory.
            return deepPath.substringBeforeLast('/')
        }

        return null
    }
}
