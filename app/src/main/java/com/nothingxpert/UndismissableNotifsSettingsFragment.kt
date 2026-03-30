package com.nothingxpert

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nothingxpert.HookEntry.Companion.MODULE_PKG
import com.nothingxpert.HookEntry.Companion.UNDISMISSABLE_PACKAGES

class UndismissableNotifsSettingsFragment : Fragment() {

    override fun onAttach(context: Context) {
        super.onAttach(LocaleHelper.setLocale(context))
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var prefs: SharedPreferences
    private lateinit var adapter: UndismissableNotifsAdapter
    private var fullList: List<AppInfo> = emptyList()
    private var sortDescending = false
    private var showSelectedOnly = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            prefs = requireContext().getSharedPreferences("${MODULE_PKG}_preferences", Context.MODE_PRIVATE)
        } catch (e: Exception) {
            prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_app_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recyclerView = view.findViewById(R.id.recycler_view)
        progressBar = view.findViewById(R.id.progress_bar)

        // Update title and summary
        view.findViewById<TextView>(R.id.app_lock_title).setText(R.string.pref_undismissable_notifs_title)
        view.findViewById<TextView>(R.id.app_lock_summary).setText(R.string.pref_undismissable_notifs_picker_summary)

        recyclerView.layoutManager = LinearLayoutManager(context)

        loadApps()
    }

    private fun loadApps() {
        progressBar.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE

        Thread {
            val pm = requireContext().packageManager
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN, null)
            intent.addCategory(android.content.Intent.CATEGORY_LAUNCHER)

            val apps = pm.queryIntentActivities(intent, 0)

            val appList = apps.mapNotNull { resolveInfo ->
                val packageName = resolveInfo.activityInfo.packageName
                if (packageName == requireContext().packageName) return@mapNotNull null // hide self

                AppInfo(
                    label = resolveInfo.loadLabel(pm).toString(),
                    packageName = packageName,
                    icon = resolveInfo.loadIcon(pm)
                )
            }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }

            requireActivity().runOnUiThread {
                if (isAdded) {
                    val selectedSet = prefs.getStringSet(UNDISMISSABLE_PACKAGES, emptySet()) ?: emptySet()
                    fullList = appList
                    adapter = UndismissableNotifsAdapter(appList, selectedSet.toMutableSet()) { pkg, isSelected ->
                        val currentSet = prefs.getStringSet(UNDISMISSABLE_PACKAGES, emptySet())?.toMutableSet() ?: mutableSetOf()
                        if (isSelected) {
                            currentSet.add(pkg)
                        } else {
                            currentSet.remove(pkg)
                        }
                        prefs.edit().putStringSet(UNDISMISSABLE_PACKAGES, currentSet).commit()
                        PrefsUtil.ensurePrefsAccessible(requireContext())
                    }
                    recyclerView.adapter = adapter
                    progressBar.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    fun toggleSort() {
        sortDescending = !sortDescending
        applyFilters()
    }

    fun toggleFilter() {
        showSelectedOnly = !showSelectedOnly
        val toastMsg = if (showSelectedOnly) {
            getString(R.string.undismissable_notifs_filter_selected)
        } else {
            getString(R.string.undismissable_notifs_filter_all)
        }
        android.widget.Toast.makeText(requireContext(), toastMsg, android.widget.Toast.LENGTH_SHORT).show()
        applyFilters()
    }

    private fun applyFilters() {
        val selectedSet = prefs.getStringSet(UNDISMISSABLE_PACKAGES, emptySet()) ?: emptySet()
        var list = fullList
        if (showSelectedOnly) {
            list = list.filter { selectedSet.contains(it.packageName) }
        }
        list = if (sortDescending) list.sortedByDescending { it.label.lowercase() } else list.sortedBy { it.label.lowercase() }
        adapter.updateData(list, selectedSet)
    }

    data class AppInfo(val label: String, val packageName: String, val icon: android.graphics.drawable.Drawable)

    class UndismissableNotifsAdapter(
        private var apps: List<AppInfo>,
        private val selectedPackages: MutableSet<String>,
        private val onToggle: (String, Boolean) -> Unit
    ) : RecyclerView.Adapter<UndismissableNotifsAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.app_icon)
            val label: TextView = view.findViewById(R.id.app_label)
            val packagename: TextView = view.findViewById(R.id.app_package)
            val switch: com.google.android.material.materialswitch.MaterialSwitch = view.findViewById(R.id.app_switch)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_lock, parent, false)
            FontHelper.applyToView(parent.context, view)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = apps[position]
            holder.label.text = app.label
            holder.packagename.text = app.packageName
            holder.icon.setImageDrawable(app.icon)

            // Remove listener to avoid triggering during recycle
            holder.switch.setOnCheckedChangeListener(null)
            holder.switch.isChecked = selectedPackages.contains(app.packageName)

            holder.switch.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selectedPackages.add(app.packageName) else selectedPackages.remove(app.packageName)
                onToggle(app.packageName, isChecked)
            }
            // Also toggle on row click
            holder.itemView.setOnClickListener {
                holder.switch.isChecked = !holder.switch.isChecked
            }
        }

        override fun getItemCount() = apps.size

        fun updateData(newList: List<AppInfo>, selectedSet: Set<String>) {
            apps = newList
            selectedPackages.clear()
            selectedPackages.addAll(selectedSet)
            notifyDataSetChanged()
        }
    }
}
