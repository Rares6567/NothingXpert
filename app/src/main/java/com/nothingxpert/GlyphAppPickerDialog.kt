package com.nothingxpert

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText

/**
 * Dialog that shows installed launcher apps for the user to pick one.
 */
class GlyphAppPickerDialog : DialogFragment() {

    companion object {
        private const val TAG = "GlyphAppPickerDialog"
        private const val ARG_EXCLUDED = "excluded_packages"

        private var onAppSelected: ((String, String) -> Unit)? = null

        fun show(
            fm: FragmentManager,
            excludedPackages: Set<String> = emptySet(),
            onResult: (packageName: String, appLabel: String) -> Unit
        ) {
            onAppSelected = onResult
            val dialog = GlyphAppPickerDialog().apply {
                arguments = Bundle().apply {
                    putStringArrayList(ARG_EXCLUDED, ArrayList(excludedPackages))
                }
            }
            dialog.show(fm, TAG)
        }
    }

    data class AppItem(
        val label: String,
        val packageName: String,
        val icon: android.graphics.drawable.Drawable
    )

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var searchInput: TextInputEditText
    private var fullList: List<AppItem> = emptyList()
    private lateinit var adapter: AppPickerAdapter

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val context = requireContext()
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_glyph_app_picker, null)

        recyclerView = view.findViewById(R.id.recycler_apps)
        progressBar = view.findViewById(R.id.progress_bar)
        searchInput = view.findViewById(R.id.search_input)

        recyclerView.layoutManager = LinearLayoutManager(context)
        adapter = AppPickerAdapter(emptyList()) { app ->
            onAppSelected?.invoke(app.packageName, app.label)
            onAppSelected = null
            dismiss()
        }
        recyclerView.adapter = adapter

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterApps(s?.toString() ?: "")
            }
        })

        loadApps()

        return MaterialAlertDialogBuilder(context)
            .setTitle(R.string.glyph_notif_select_app)
            .setView(view)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                onAppSelected = null
            }
            .create()
    }

    private fun loadApps() {
        progressBar.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE

        val excluded = arguments?.getStringArrayList(ARG_EXCLUDED)?.toSet() ?: emptySet()

        Thread {
            val pm = requireContext().packageManager
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN, null)
            intent.addCategory(android.content.Intent.CATEGORY_LAUNCHER)

            val apps = pm.queryIntentActivities(intent, 0)

            val appList = apps.mapNotNull { resolveInfo ->
                val packageName = resolveInfo.activityInfo.packageName
                // Hide self and already-mapped apps
                if (packageName == requireContext().packageName) return@mapNotNull null
                if (excluded.contains(packageName)) return@mapNotNull null

                AppItem(
                    label = resolveInfo.loadLabel(pm).toString(),
                    packageName = packageName,
                    icon = resolveInfo.loadIcon(pm)
                )
            }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }

            requireActivity().runOnUiThread {
                if (isAdded) {
                    fullList = appList
                    adapter.updateData(appList)
                    progressBar.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    private fun filterApps(query: String) {
        if (query.isBlank()) {
            adapter.updateData(fullList)
            return
        }
        val q = query.lowercase()
        val filtered = fullList.filter {
            it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
        }
        adapter.updateData(filtered)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        onAppSelected = null
    }

    // --- Adapter ---

    class AppPickerAdapter(
        private var apps: List<AppItem>,
        private val onClick: (AppItem) -> Unit
    ) : RecyclerView.Adapter<AppPickerAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.app_icon)
            val label: TextView = view.findViewById(R.id.app_label)
            val packageName: TextView = view.findViewById(R.id.app_package)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_glyph_app_picker, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = apps[position]
            holder.label.text = app.label
            holder.packageName.text = app.packageName
            holder.icon.setImageDrawable(app.icon)
            holder.itemView.setOnClickListener { onClick(app) }
        }

        override fun getItemCount() = apps.size

        fun updateData(newList: List<AppItem>) {
            apps = newList
            notifyDataSetChanged()
        }
    }
}
