package com.bncam.data.settings

import com.bncam.core.quality.NoiseModelResolver
import com.bncam.core.quality.NoiseModelSource

/**
 * User-facing policy for the physical noise-model settings page.
 *
 * This deliberately contains no capture wiring. Phase 3 only exposes/persists the new
 * physical model configuration while the production authority remains on the legacy route.
 */
object PhysicalNoiseModelUiPolicy {
    val selectableSources: List<NoiseModelSource> = listOf(
        NoiseModelSource.OEM,
        NoiseModelSource.SYSTEM,
        NoiseModelSource.MANUAL,
        NoiseModelSource.PRESET
    )

    fun sourceLabel(source: NoiseModelSource): String = when (source) {
        NoiseModelSource.OEM -> "OEM"
        NoiseModelSource.SYSTEM -> "System"
        NoiseModelSource.MANUAL -> "Manual"
        NoiseModelSource.PRESET -> "Preset"
    }

    fun sourceFromLabel(label: String): NoiseModelSource = when (label.trim().lowercase()) {
        "oem" -> NoiseModelSource.OEM
        "manual" -> NoiseModelSource.MANUAL
        "preset" -> NoiseModelSource.PRESET
        else -> NoiseModelSource.SYSTEM
    }

    fun dynamicIsoAvailable(source: NoiseModelSource): Boolean = source != NoiseModelSource.OEM

    fun summary(settings: LensPhysicalNoiseModelSettings): String {
        val sourceText = when (settings.source) {
            NoiseModelSource.OEM -> "OEM · direct Camera2 S/O"
            NoiseModelSource.SYSTEM -> if (settings.systemModel != null) {
                "System · model available"
            } else {
                "System · model not calibrated"
            }
            NoiseModelSource.MANUAL -> if (settings.manualModel != null) {
                "Manual · configured"
            } else {
                "Manual · incomplete"
            }
            NoiseModelSource.PRESET -> settings.selectedPresetId?.let { "Preset · $it" }
                ?: "Preset · none selected"
        }

        if (!dynamicIsoAvailable(settings.source) || !settings.dynamicIsoEnabled) return sourceText
        return "$sourceText · Dynamic ISO ${formatCoefficient(settings.dynamicIsoCoefficient)}"
    }

    /**
     * Reset only user-selectable controls. A learned/system model belongs to device calibration
     * state and must survive a UI reset. Legacy migration provenance is also retained.
     */
    fun resetUserControls(settings: LensPhysicalNoiseModelSettings): LensPhysicalNoiseModelSettings {
        return LensPhysicalNoiseModelSettings(
            source = NoiseModelSource.SYSTEM,
            dynamicIsoEnabled = NoiseModelResolver.DEFAULT_DYNAMIC_ISO_ENABLED,
            dynamicIsoCoefficient = NoiseModelResolver.DEFAULT_DYNAMIC_ISO_COEFFICIENT,
            systemModel = settings.systemModel,
            systemModelOrigin = settings.systemModelOrigin,
            manualModel = null,
            selectedPresetId = null,
            migration = settings.migration
        ).sanitized()
    }

    private fun formatCoefficient(value: Double): String = String.format(java.util.Locale.US, "%.2f", value)
}
