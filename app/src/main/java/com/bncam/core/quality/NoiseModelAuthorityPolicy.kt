package com.bncam.core.quality

/**
 * Transitional compatibility policy while FinalSensorCalibration still has retired
 * Off/Auto/Manual fields.
 *
 * Production physical authority is no longer selected here. OEM/System/Manual/Preset is resolved
 * later by PhysicalNoiseState. This compatibility lane may expose Camera2/OEM S/O as a temporary
 * baseline only; legacy Manual S/O is deliberately unreachable so single-anchor scaling cannot
 * become capture authority again.
 *
 * [spectraProcessingMode] carries only the profile SPECTRA request intent. Effective SPECTRA
 * execution is decided later by [withSpectraNoiseAdapter] from the resolved physical state.
 */
data class NoiseModelAuthorityDecision(
    val physicalNoiseMode: String,
    val spectraProcessingMode: String
)

object NoiseModelAuthorityPolicy {
    private const val OFF = "Off"
    private const val ON = "On"
    private const val AUTO = "Auto"

    fun resolve(
        lensNoiseMode: String,
        spectraRequested: Boolean,
        cameraNoiseProfileAvailable: Boolean,
        manualNoiseProfileAvailable: Boolean
    ): NoiseModelAuthorityDecision {
        // The old user-selected Off/Auto/Manual mode no longer has production authority. Keep only
        // Camera2/OEM as an interim baseline for legacy FinalSensorCalibration construction; the
        // PhysicalNoiseState bridge replaces it before RAW merge/render.
        val physicalMode = if (cameraNoiseProfileAvailable) AUTO else OFF
        val spectraMode = if (spectraRequested) ON else OFF

        @Suppress("UNUSED_VARIABLE")
        val retiredLegacyInputs = lensNoiseMode to manualNoiseProfileAvailable

        return NoiseModelAuthorityDecision(
            physicalNoiseMode = physicalMode,
            spectraProcessingMode = spectraMode
        )
    }
}
