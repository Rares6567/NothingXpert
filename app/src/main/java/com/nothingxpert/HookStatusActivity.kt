package com.nothingxpert

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.activity.addCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HookStatusActivity : BaseActivity() {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this) {
            finish()
            applyForwardAnimation()
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_hook_status)

        val appBar = findViewById<AppBarLayout>(R.id.appbar)
        ViewCompat.setOnApplyWindowInsetsListener(appBar) { view, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.updatePadding(top = statusBarInsets.top)
            insets
        }

        val list = findViewById<ListView>(R.id.hook_list)
        ViewCompat.setOnApplyWindowInsetsListener(list) { view, insets ->
            val navInsets = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.systemGestures()
            )
            view.updatePadding(bottom = navInsets.bottom)
            insets
        }

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        toolbar.title = getString(R.string.hook_status_title)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        render()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        applyForwardAnimation()
        return true
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val list = findViewById<ListView>(R.id.hook_list) ?: return
        val reports = readReports()
        val lines = reports.map { (process, id, installed, detail, stamp) ->
            val status = if (installed) {
                getString(R.string.hook_status_ok)
            } else {
                getString(R.string.hook_status_failed)
            }
            buildString {
                append(status).append("  ").append(process).append('\n')
                append(id)
                if (detail.isNotBlank() && detail != "ok") {
                    append('\n').append(detail)
                }
                if (stamp > 0L) {
                    append("\n").append(getString(R.string.hook_status_time))
                        .append(' ').append(timeFormat.format(Date(stamp)))
                }
            }
        }
        if (lines.isEmpty()) {
            list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, listOf(getString(R.string.hook_status_empty)))
            list.onItemClickListener = null
            list.divider = null
            return
        }
        list.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, lines) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                (view as? TextView)?.setTextColor(
                    androidx.core.content.ContextCompat.getColor(
                        context,
                        if (reports[position].installed) android.R.color.holo_green_dark else android.R.color.holo_red_dark
                    )
                )
                return view
            }
        }
    }

    private data class Report(
        val process: String,
        val id: String,
        val installed: Boolean,
        val detail: String,
        val stamp: Long
    )

    private fun readReports(): List<Report> {
        val prefs = getSharedPreferences(RemotePrefProvider.HOOK_HEALTH_PREFS, MODE_PRIVATE)
        return prefs.all.entries
            .mapNotNull { (key, value) ->
                val nameParts = (key as? String)?.split('|', limit = 2) ?: return@mapNotNull null
                val parts = (value as? String)?.split('\u0000') ?: return@mapNotNull null
                if (nameParts.size != 2 || parts.size < 3) return@mapNotNull null
                Report(nameParts[0], nameParts[1], parts[0] == "true", parts[1], parts[2].toLongOrNull() ?: 0L)
            }
            .sortedWith(compareBy({ it.process }, { it.id }))
    }
}
