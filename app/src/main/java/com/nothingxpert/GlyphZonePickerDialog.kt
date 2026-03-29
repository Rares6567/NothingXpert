package com.nothingxpert

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nothingxpert.ui.GlyphZoneView

/**
 * Dialog that shows an interactive glyph zone picker.
 * Users tap zones on a phone silhouette to select/deselect them.
 */
class GlyphZonePickerDialog : DialogFragment() {

    companion object {
        private const val TAG = "GlyphZonePickerDialog"
        private const val ARG_DEVICE_TYPE = "device_type"
        private const val ARG_SELECTED_ZONES = "selected_zones"

        private var onZonesSelected: ((Set<String>) -> Unit)? = null

        fun show(
            fm: FragmentManager,
            deviceType: GlyphZoneView.DeviceType,
            preSelectedZones: Set<String> = emptySet(),
            onResult: (Set<String>) -> Unit
        ) {
            onZonesSelected = onResult
            val dialog = GlyphZonePickerDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_DEVICE_TYPE, deviceType.name)
                    putStringArrayList(ARG_SELECTED_ZONES, ArrayList(preSelectedZones))
                }
            }
            dialog.show(fm, TAG)
        }
    }

    private lateinit var glyphZoneView: GlyphZoneView
    private lateinit var selectedZonesLabel: TextView
    private var currentDeviceType: GlyphZoneView.DeviceType = GlyphZoneView.DeviceType.PHONE_2

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val context = requireContext()
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_glyph_zone_picker, null)

        glyphZoneView = view.findViewById(R.id.glyph_zone_view)
        selectedZonesLabel = view.findViewById(R.id.selected_zones_label)

        // Set device type
        val deviceTypeName = arguments?.getString(ARG_DEVICE_TYPE) ?: GlyphZoneView.DeviceType.PHONE_2.name
        val deviceType = GlyphZoneView.DeviceType.valueOf(deviceTypeName)
        currentDeviceType = deviceType
        glyphZoneView.deviceType = deviceType

        // Pre-select zones
        val preSelectedRaw = arguments?.getStringArrayList(ARG_SELECTED_ZONES)?.toSet() ?: emptySet()
        val preSelected = normalizeLegacyZones(preSelectedRaw, deviceType)
        // Zones are built on layout, so post the selection
        glyphZoneView.post {
            glyphZoneView.setSelectedZoneIds(preSelected)
            updateLabel(preSelected)
            GlyphNotificationService.setPickerPreviewZones(preSelected)
        }

        glyphZoneView.onZoneSelectionChanged = { zones ->
            updateLabel(zones)
            GlyphNotificationService.setPickerPreviewZones(zones)
        }

        return MaterialAlertDialogBuilder(context)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onZonesSelected?.invoke(glyphZoneView.getSelectedZoneIds())
                GlyphNotificationService.clearPickerPreview()
                onZonesSelected = null
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                GlyphNotificationService.clearPickerPreview()
                onZonesSelected = null
            }
            .create()
    }

    private fun updateLabel(zones: Set<String>) {
        if (zones.isEmpty()) {
            selectedZonesLabel.text = getString(R.string.glyph_notif_no_zones_selected)
        } else {
            selectedZonesLabel.text = zones
                .sortedBy { zoneSortKey(it) }
                .joinToString(" · ") { zoneDisplayName(it) }
        }
    }

    private fun normalizeLegacyZones(
        zones: Set<String>,
        deviceType: GlyphZoneView.DeviceType
    ): Set<String> {
        if (deviceType != GlyphZoneView.DeviceType.PHONE_2) return zones

        val normalized = zones.toMutableSet()
        if (normalized.remove("STRIP")) {
            normalized.add("STRIP_LEFT")
            normalized.add("STRIP_RIGHT")
        }
        if (normalized.remove("CURVE")) {
            normalized.add("CURVE_LEFT")
            normalized.add("CURVE_BOTTOM_LEFT")
            normalized.add("CURVE_BOTTOM_RIGHT")
            normalized.add("CURVE_RIGHT")
        }
        return normalized
    }

    private fun zoneSortKey(zone: String): Int {
        if (currentDeviceType == GlyphZoneView.DeviceType.PHONE_1) {
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
            "CURVE_LEFT" -> 5
            "CURVE_BOTTOM_LEFT" -> 6
            "CURVE_BOTTOM_RIGHT" -> 7
            "CURVE_RIGHT" -> 8
            "USB" -> 9
            "BOTTOM" -> 10
            else -> 100
        }
    }

    private fun zoneDisplayName(zone: String): String {
        if (currentDeviceType == GlyphZoneView.DeviceType.PHONE_1) {
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
            "CURVE_LEFT" -> "C3 (20)"
            "CURVE_BOTTOM_LEFT" -> "C4 (21)"
            "CURVE_BOTTOM_RIGHT" -> "C5 (22)"
            "CURVE_RIGHT" -> "C6 (23)"
            "USB" -> "D1_1-D1_8 (25-32)"
            "BOTTOM" -> "E1 (24)"
            else -> zone
        }
    }

    override fun onDestroyView() {
        GlyphNotificationService.clearPickerPreview()
        super.onDestroyView()
        onZonesSelected = null
    }

    override fun onDismiss(dialog: DialogInterface) {
        GlyphNotificationService.clearPickerPreview()
        super.onDismiss(dialog)
    }
}
