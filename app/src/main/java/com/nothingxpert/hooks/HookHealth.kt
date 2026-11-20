package com.nothingxpert.hooks

import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.nothingxpert.RemotePrefProvider

/**
 * In-process registry of hook installation results.
 *
 * Every safeHook()/hookSkipped() call records an entry. Once the hooked process
 * has a Context, the entries are shipped to the module app via the
 * RemotePrefProvider call() transport, where the Hook status screen renders them.
 */
object HookHealth {

    data class Entry(
        val process: String,
        val id: String,
        val installed: Boolean,
        val detail: String,
        val at: Long
    )

    @Volatile
    private var process: String = "unknown"

    private val entries = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    fun bindProcess(name: String) {
        if (name.isNotBlank()) process = name
    }

    fun record(id: String, installed: Boolean, detail: String = "ok") {
        entries["$process|$id"] = Entry(process, id, installed, detail, System.currentTimeMillis())
    }

    fun skipped(id: String, reason: String) {
        record(id, false, reason)
    }

    fun snapshot(): List<Entry> {
        return entries.values.sortedWith(compareBy({ it.process }, { it.id }))
    }

    fun flushIfPossible(context: Context?) {
        val appContext = context?.applicationContext ?: return
        val pending = snapshot().filter { it.process == process }
        if (pending.isEmpty()) return
        val ids = ArrayList<String>(pending.size)
        val details = ArrayList<String>(pending.size)
        pending.forEach {
            ids.add(it.id)
            val state = if (it.installed) "1" else "0"
            details.add(state + "\u0000" + it.detail + "\u0000" + it.at)
        }
        val extras = Bundle().apply {
            putStringArrayList(RemotePrefProvider.KEY_HOOK_REPORT, ArrayList(listOf(process) + ids))
            putStringArrayList(RemotePrefProvider.KEY_HOOK_DETAILS, details)
        }
        try {
            appContext.contentResolver.call(
                Uri.parse("content://${RemotePrefProvider.AUTHORITY}"),
                RemotePrefProvider.METHOD_REPORT_HOOK_STATUS,
                process,
                extras
            )
        } catch (_: Throwable) {
        }
    }
}
