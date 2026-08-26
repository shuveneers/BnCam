package com.bncam.core.quality

/**
 * Resolves two deliberately separate contracts:
 *  - physicalNoiseMode controls availability of the sensor S/O variance model;
 *  - spectraProcessingMode controls SPECTRA Context Fusion pixel processing.
 *
 * A valid Camera2 profile is useful to the conventional RAW baseline even when SPECTRA is Off.
 * SPECTRA therefore never owns or erases physical sensor calibration.
 */
data class NoiseModelAuthorityDecision(
    val physicalNoiseMode: String,
    val spectraProcessingMode: String
)

object NoiseModelAuthorityPolicy {
    private const val OFF = "Off"
    private const val AUTO = "Auto"
    private const val MANUAL = "Manual"

    fun resolve(
        lensNoiseMode: String,
        spectraRequested: Boolean,
        cameraNoiseProfileAvailable: Boolean,
        manualNoiseProfileAvailable: Boolean
    ): NoiseModelAuthorityDecision {
        val requestedLensMode = when {
            lensNoiseMode.equals(MANUAL, ignoreCase = true) -> MANUAL
            lensNoiseMode.equals(AUTO, ignoreCase = true) -> AUTO
            else -> OFF
        }

        // An explicit Manual request must never silently substitute Camera2 Auto. If its profile is
        // missing/invalid, the existing validation path reports that failure. Otherwise a valid
        // Camera2 model is the physical baseline regardless of the SPECTRA profile toggle.
        val physicalMode = when {
            requestedLensMode == MANUAL -> MANUAL
            requestedLensMode == AUTO -> AUTO
            cameraNoiseProfileAvailable -> AUTO
            else -> OFF
        }

        val physicalModelAvailable = when (physicalMode) {
            MANUAL -> manualNoiseProfileAvailable
            AUTO -> cameraNoiseProfileAvailable
            else -> false
        }
        val spectraMode = if (spectraRequested && physicalModelAvailable) physicalMode else OFF
        return NoiseModelAuthorityDecision(
            physicalNoiseMode = physicalMode,
            spectraProcessingMode = spectraMode
        )
    }
}
