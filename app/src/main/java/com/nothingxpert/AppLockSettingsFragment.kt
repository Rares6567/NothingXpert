package com.nothingxpert

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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

class AppLockSettingsFragment : Fragment() {

    override fun onAttach(context: Context) {
        super.onAttach(LocaleHelper.setLocale(context))
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var prefs: SharedPreferences
    private lateinit var adapter: AppLockAdapter
    private var fullList: List<AppInfo> = emptyList()
    private var sortDescending = false
    private var showLockedOnly = false

    // TODO: Define locked packages preference key
    private val PREF_LOCKED_PACKAGES = "pref_locked_packages"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            // Attempt to get World Readable prefs if possible, or standard prefs
            // The hook reads from XSharedPreferences, so we just write to standard prefs in MODE_WORLD_READABLE (deprecated but works with Xposed)
            // Or simpler: just use default prefs and make sure the file is readable.
            // Modern Android blocks world-readable. We'll use standard prefs and assume XSharedPreferences handles reading.
            @Suppress("DEPRECATION")
            prefs = requireContext().getSharedPreferences("${MODULE_PKG}_preferences", Context.MODE_PRIVATE)
            // Make world readable just in case (deprecated)
             try {
                 @Suppress("DEPRECATION")
                 prefs = requireContext().getSharedPreferences("${MODULE_PKG}_preferences", Context.MODE_WORLD_READABLE)
             } catch (_: SecurityException) {
                 // ignore
                 prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
             }
        } catch (e: Exception) {
            prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        // We'll create a simple layout for the fragment programmatically or use an XML if we create one.
        // Let's create `fragment_app_list.xml` next step.
        return inflater.inflate(R.layout.fragment_app_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recyclerView = view.findViewById(R.id.recycler_view)
        progressBar = view.findViewById(R.id.progress_bar)

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
                    val lockedSet = prefs.getStringSet(PREF_LOCKED_PACKAGES, emptySet()) ?: emptySet()
                    fullList = appList
                    adapter = AppLockAdapter(appList, lockedSet.toMutableSet()) { pkg, isLocked ->
                        val currentSet = prefs.getStringSet(PREF_LOCKED_PACKAGES, emptySet())?.toMutableSet() ?: mutableSetOf()
                if (isLocked) {
                    currentSet.add(pkg)
                } else {
                    currentSet.remove(pkg)
                }
                prefs.edit().putStringSet(PREF_LOCKED_PACKAGES, currentSet).commit()
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
        showLockedOnly = !showLockedOnly
        val toastMsg = if (showLockedOnly) {
            getString(R.string.app_lock_filter_locked)
        } else {
            getString(R.string.app_lock_filter_all)
        }
        android.widget.Toast.makeText(requireContext(), toastMsg, android.widget.Toast.LENGTH_SHORT).show()
        applyFilters()
    }

    private fun applyFilters() {
        val lockedSet = prefs.getStringSet(PREF_LOCKED_PACKAGES, emptySet()) ?: emptySet()
        var list = fullList
        if (showLockedOnly) {
            list = list.filter { lockedSet.contains(it.packageName) }
        }
        list = if (sortDescending) list.sortedByDescending { it.label.lowercase() } else list.sortedBy { it.label.lowercase() }
        adapter.updateData(list, lockedSet)
    }

    data class AppInfo(val label: String, val packageName: String, val icon: android.graphics.drawable.Drawable)

    class AppLockAdapter(
        private var apps: List<AppInfo>,
        private val lockedPackages: MutableSet<String>,
        private val onToggle: (String, Boolean) -> Unit
    ) : RecyclerView.Adapter<AppLockAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.app_icon)
            val label: TextView = view.findViewById(R.id.app_label)
            val packagename: TextView = view.findViewById(R.id.app_package)
            val switch: com.google.android.material.materialswitch.MaterialSwitch = view.findViewById(R.id.app_switch)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_lock, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = apps[position]
            holder.label.text = app.label
            holder.packagename.text = app.packageName
            holder.icon.setImageDrawable(app.icon)
            
            // Remove listener to avoid triggering during recycle
            holder.switch.setOnCheckedChangeListener(null)
            holder.switch.isChecked = lockedPackages.contains(app.packageName)
            
            holder.switch.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) lockedPackages.add(app.packageName) else lockedPackages.remove(app.packageName)
                onToggle(app.packageName, isChecked)
            }
            // Also toggle on row click
            holder.itemView.setOnClickListener {
                holder.switch.isChecked = !holder.switch.isChecked
            }
        }

        override fun getItemCount() = apps.size

        fun updateData(newList: List<AppInfo>, lockedSet: Set<String>) {
            apps = newList
            lockedPackages.clear()
            lockedPackages.addAll(lockedSet)
            notifyDataSetChanged()
        }
    }
}
