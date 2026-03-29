package com.nothingxpert

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch

class GlyphNotifSettingsActivity : BaseActivity() {

    companion object {
        const val PREF_GLYPH_NOTIF_ENABLED = "pref_glyph_notif_enabled"
        const val PREF_GLYPH_NOTIF_MAPPINGS = "pref_glyph_notif_mappings"
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: TextView
    private lateinit var adapter: MappingAdapter
    private val mappings = mutableListOf<GlyphMapping>()

    data class GlyphMapping(
        val packageName: String,
        val appLabel: String,
        val zones: Set<String>,
        val icon: android.graphics.drawable.Drawable?
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this) {
            finish()
            applyForwardAnimation()
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_glyph_notif_settings)

        val appBar = findViewById<AppBarLayout>(R.id.appbar)
        ViewCompat.setOnApplyWindowInsetsListener(appBar) { view, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.updatePadding(top = statusBarInsets.top)
            insets
        }

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        toolbar.title = getString(R.string.glyph_notif_title)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        try {
            prefs = getSharedPreferences("${HookEntry.MODULE_PKG}_preferences", Context.MODE_PRIVATE)
        } catch (e: Exception) {
            prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        }

        setupNotifAccessBanner()
        setupMasterToggle()
        setupMappingsList()
        setupFab()
    }

    override fun onResume() {
        super.onResume()
        updateNotifAccessBanner()
        refreshMappings()
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val cn = ComponentName(this, GlyphNotificationService::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(cn.flattenToString())
    }

    private fun setupNotifAccessBanner() {
        val banner = findViewById<View>(R.id.notif_access_banner)
        val openSettingsBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_open_notif_settings)

        openSettingsBtn.setOnClickListener {
            startActivity(android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    private fun updateNotifAccessBanner() {
        val banner = findViewById<View>(R.id.notif_access_banner)
        banner.visibility = if (isNotificationListenerEnabled()) View.GONE else View.VISIBLE
    }

    private fun setupMasterToggle() {
        val cardToggle = findViewById<View>(R.id.card_master_toggle)
        val switchMaster = findViewById<MaterialSwitch>(R.id.switch_master)

        switchMaster.isChecked = prefs.getBoolean(PREF_GLYPH_NOTIF_ENABLED, false)

        val toggleAction = {
            val newState = !switchMaster.isChecked
            switchMaster.isChecked = newState
            prefs.edit().putBoolean(PREF_GLYPH_NOTIF_ENABLED, newState).commit()
            PrefsUtil.ensurePrefsAccessible(this)
        }

        cardToggle.setOnClickListener { toggleAction() }
        switchMaster.setOnClickListener {
            prefs.edit().putBoolean(PREF_GLYPH_NOTIF_ENABLED, switchMaster.isChecked).commit()
            PrefsUtil.ensurePrefsAccessible(this)
        }
    }

    private fun setupMappingsList() {
        recyclerView = findViewById(R.id.recycler_mappings)
        emptyState = findViewById(R.id.empty_state)

        adapter = MappingAdapter(
            items = mappings,
            isPhone1 = GlyphsActivity.isPhone1ForGlyphUi(this),
            onDelete = { mapping ->
                // Delete mapping
                removeMappingForPackage(mapping.packageName)
                refreshMappings()
                Toast.makeText(this, R.string.glyph_notif_mapping_deleted, Toast.LENGTH_SHORT).show()
            },
            onEdit = { mapping ->
                // Tap existing mapping to edit selected zones
                showZonePicker(mapping.packageName, mapping.zones)
            }
        )

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun setupFab() {
        val fab = findViewById<FloatingActionButton>(R.id.fab_add)
        fab.setOnClickListener {
            showAppPicker()
        }
    }

    private fun showAppPicker() {
        val existingMappings = getMappingsFromPrefs().toMap()

        GlyphAppPickerDialog.show(supportFragmentManager, emptySet()) { packageName, _ ->
            // App selected, now show zone picker. If app already exists, pre-select current zones.
            val preSelectedZones = existingMappings[packageName] ?: emptySet()
            showZonePicker(packageName, preSelectedZones)
        }
    }

    private fun showZonePicker(packageName: String, preSelectedZones: Set<String> = emptySet()) {
        val deviceType = if (GlyphsActivity.isPhone1ForGlyphUi(this)) {
            com.nothingxpert.ui.GlyphZoneView.DeviceType.PHONE_1
        } else {
            com.nothingxpert.ui.GlyphZoneView.DeviceType.PHONE_2
        }

        GlyphZonePickerDialog.show(supportFragmentManager, deviceType, preSelectedZones) { selectedZones ->
            if (selectedZones.isEmpty()) {
                Toast.makeText(this, R.string.glyph_notif_no_zones_selected, Toast.LENGTH_SHORT).show()
                return@show
            }
            // Save mapping
            addMapping(packageName, selectedZones)
            refreshMappings()
        }
    }

    private fun addMapping(packageName: String, zones: Set<String>) {
        val currentMappings = prefs.getStringSet(PREF_GLYPH_NOTIF_MAPPINGS, emptySet())?.toMutableSet() ?: mutableSetOf()
        // Remove existing mapping for this package if any
        currentMappings.removeAll { it.startsWith("$packageName:") }
        // Add new mapping
        currentMappings.add("$packageName:${zones.joinToString(",")}")
        prefs.edit().putStringSet(PREF_GLYPH_NOTIF_MAPPINGS, currentMappings).commit()
        PrefsUtil.ensurePrefsAccessible(this)
    }

    private fun removeMappingForPackage(packageName: String) {
        val currentMappings = prefs.getStringSet(PREF_GLYPH_NOTIF_MAPPINGS, emptySet())?.toMutableSet() ?: mutableSetOf()
        currentMappings.removeAll { it.startsWith("$packageName:") }
        prefs.edit().putStringSet(PREF_GLYPH_NOTIF_MAPPINGS, currentMappings).commit()
        PrefsUtil.ensurePrefsAccessible(this)
    }

    private fun getMappingsFromPrefs(): List<Pair<String, Set<String>>> {
        val rawMappings = prefs.getStringSet(PREF_GLYPH_NOTIF_MAPPINGS, emptySet()) ?: emptySet()
        return rawMappings.mapNotNull { entry ->
            val parts = entry.split(":", limit = 2)
            if (parts.size == 2) {
                val pkg = parts[0]
                val zones = parts[1].split(",").filter { it.isNotBlank() }.toSet()
                pkg to zones
            } else null
        }
    }

    private fun refreshMappings() {
        mappings.clear()
        val pm = packageManager
        val rawMappings = getMappingsFromPrefs()

        for ((pkg, zones) in rawMappings) {
            val appLabel = try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) { pkg }

            val appIcon = try {
                pm.getApplicationIcon(pkg)
            } catch (e: Exception) { null }

            mappings.add(GlyphMapping(pkg, appLabel, zones, appIcon))
        }

        adapter.notifyDataSetChanged()
        emptyState.visibility = if (mappings.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        applyForwardAnimation()
        return true
    }

    // --- Adapter ---

    class MappingAdapter(
        private val items: List<GlyphMapping>,
        private val isPhone1: Boolean,
        private val onDelete: (GlyphMapping) -> Unit,
        private val onEdit: (GlyphMapping) -> Unit
    ) : RecyclerView.Adapter<MappingAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val appIcon: ImageView = view.findViewById(R.id.app_icon)
            val appName: TextView = view.findViewById(R.id.app_name)
            val zoneLabels: TextView = view.findViewById(R.id.zone_labels)
            val btnDelete: ImageView = view.findViewById(R.id.btn_delete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_glyph_app_mapping, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.appName.text = item.appLabel
            holder.zoneLabels.text = item.zones
                .sortedBy { zoneSortKey(it) }
                .joinToString(" · ") { zoneDisplayName(it) }

            if (item.icon != null) {
                holder.appIcon.setImageDrawable(item.icon)
            } else {
                holder.appIcon.setImageResource(R.drawable.ic_settings_apps)
            }

            holder.itemView.setOnClickListener {
                onEdit(item)
            }

            holder.btnDelete.setOnClickListener {
                onDelete(item)
            }
        }

        private fun zoneSortKey(zone: String): Int {
            if (isPhone1) {
                return when (zone) {
                    "CAMERA" -> 0
                    "DIAGONAL" -> 1
                    "BATTERY" -> 2
                    "CENTER" -> 3
                    "BOTTOM" -> 4
                    else -> 100
                }
            }

            return when (zone) {
                "TOP_LEFT" -> 0
                "TOP_RIGHT" -> 1
                "CAMERA" -> 2
                "STRIP_LEFT" -> 3
                "STRIP_RIGHT" -> 4
                "STRIP" -> 5
                "CURVE_LEFT" -> 6
                "CURVE_BOTTOM_LEFT" -> 7
                "CURVE_BOTTOM_RIGHT" -> 8
                "CURVE_RIGHT" -> 9
                "CURVE" -> 10
                "USB" -> 11
                "BOTTOM" -> 12
                else -> 100
            }
        }

        private fun zoneDisplayName(zone: String): String {
            if (isPhone1) {
                return when (zone) {
                    "CAMERA" -> "A1 (0)"
                    "DIAGONAL" -> "B1 (1)"
                    "BATTERY" -> "C1-C4 (2-5)"
                    "CENTER" -> "D1_1-D1_8 (7-14)"
                    "BOTTOM" -> "E1 (6)"
                    else -> zone
                }
            }

            return when (zone) {
                "TOP_LEFT" -> "A1 (0)"
                "TOP_RIGHT" -> "A2 (1)"
                "CAMERA" -> "B1 (2)"
                "STRIP_LEFT" -> "C2 (19)"
                "STRIP_RIGHT" -> "C1 (3-18)"
                "STRIP" -> "C1+C2 (3-19)"
                "CURVE_LEFT" -> "C3 (20)"
                "CURVE_BOTTOM_LEFT" -> "C4 (21)"
                "CURVE_BOTTOM_RIGHT" -> "C5 (22)"
                "CURVE_RIGHT" -> "C6 (23)"
                "CURVE" -> "C2-C6 (19-23)"
                "USB" -> "D1_1-D1_8 (25-32)"
                "BOTTOM" -> "E1 (24)"
                else -> zone
            }
        }

        override fun getItemCount() = items.size
    }
}
