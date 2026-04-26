package com.nothingxpert.util

import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Persistent root shell used to read privileged proc/sys nodes efficiently.
 *
 * Scope:
 * - The shell is reused only inside the current process (app process vs SystemUI process).
 * - Calls are serialized to avoid output interleaving.
 */
object RootShell {
    private const val TAG = "NothingXpertRootShell"

    private val lock = Any()

    private var process: Process? = null
    private var stdin: BufferedWriter? = null
    private var lines: LinkedBlockingQueue<String>? = null

    data class ExecResult(
        val stdout: String,
        val exitCode: Int,
    )

    /**
     * Wrap [s] in single quotes, escaping embedded single quotes as `'\''`.
     * Safe to interpolate the result directly into a shell command line.
     */
    fun shQuote(s: String): String {
        return "'" + s.replace("'", "'\\''") + "'"
    }

    private fun resetLocked() {
        try {
            process?.destroy()
        } catch (_: Throwable) {
        }
        process = null
        stdin = null
        lines = null
    }

    private fun ensureStartedLocked(): Boolean {
        val p = process
        if (p != null && p.isAlive && stdin != null && lines != null) return true

        resetLocked()

        return try {
            val newProc = ProcessBuilder("su")
                .redirectErrorStream(true)
                .start()

            val newStdin = BufferedWriter(OutputStreamWriter(newProc.outputStream))
            val q = LinkedBlockingQueue<String>()
            val reader = BufferedReader(InputStreamReader(newProc.inputStream))

            Thread {
                try {
                    while (true) {
                        val line = reader.readLine() ?: break
                        q.put(line)
                    }
                } catch (_: Throwable) {
                } finally {
                    synchronized(lock) {
                        // If this is still our process, mark it dead for next call.
                        if (process === newProc) resetLocked()
                    }
                }
            }.apply {
                name = "NothingXpert-RootShell-Reader"
                isDaemon = true
                start()
            }

            process = newProc
            stdin = newStdin
            lines = q
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to start su shell: $t")
            resetLocked()
            false
        }
    }

    fun exec(cmd: String, timeoutMs: Long = 1500L): ExecResult? {
        // Unique marker so we know when the command finished and what the exit code was.
        val marker = "NX_END_${System.nanoTime()}"

        // Important: keep marker as a *literal* (single-quoted) in the shell to avoid surprises.
        // Also: Kotlin string interpolation would try to expand $ec, so we use "\$ec".
        val wrapped =
            "( $cmd ); ec=$?; printf '\\n%s:%s\\n' '${marker}' \"\$ec\"\n"

        synchronized(lock) {
            if (!ensureStartedLocked()) return null
            val w = stdin ?: return null
            val q = lines ?: return null

            try {
                w.write(wrapped)
                w.flush()
            } catch (t: Throwable) {
                Log.w(TAG, "su write failed, resetting: $t")
                resetLocked()
                return null
            }

            val out = ArrayList<String>(32)
            val deadlineNs = System.nanoTime() + timeoutMs * 1_000_000L

            while (true) {
                val remainingNs = deadlineNs - System.nanoTime()
                if (remainingNs <= 0) {
                    Log.w(TAG, "su exec timeout, resetting (cmd=$cmd)")
                    resetLocked()
                    return null
                }

                val line = try {
                    q.poll(remainingNs, TimeUnit.NANOSECONDS)
                } catch (_: InterruptedException) {
                    null
                } ?: run {
                    Log.w(TAG, "su exec poll timeout, resetting (cmd=$cmd)")
                    resetLocked()
                    return null
                }

                if (line.startsWith("$marker:")) {
                    val code = line.substringAfter(':').trim().toIntOrNull() ?: -1
                    return ExecResult(stdout = out.joinToString("\n"), exitCode = code)
                }

                out.add(line)
            }
        }
    }

    fun cat(path: String, timeoutMs: Long = 1500L): String? {
        val res = exec("cat -- ${shQuote(path)} 2>/dev/null", timeoutMs) ?: return null
        if (res.exitCode != 0) return null
        return res.stdout
    }

    fun close() {
        synchronized(lock) {
            resetLocked()
        }
    }
}

