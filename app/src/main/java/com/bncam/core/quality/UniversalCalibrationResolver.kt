package com.bncam.core.quality

import com.bncam.data.baseline.CalibrationProvenance
import com.bncam.data.baseline.ResolvedBlackLevelState
import com.bncam.data.baseline.ResolvedColorTransformState
import com.bncam.data.baseline.ResolvedNoiseModelState

object UniversalCalibrationResolver {

    /**
     * Resolves positional black levels (pos00, pos01, pos10, pos11) following the AUTO calibration hierarchy:
     * 1. Dynamic per-frame metadata (if available)
     * 2. Static CameraCharacteristics metadata
     * 3. Measured per-lens baseline
     * 4. Conservative generic fallback
     */
    fun resolvePositionalBlackLevels(
        dynamicPattern: FloatArray?,
        staticPattern: FloatArray?,
        measuredPattern: FloatArray?,
        cfaPattern: String = "RGGB",
        whiteLevel: Float = 1023.0f
    ): ResolvedBlackLevelState {
        return when {
            dynamicPattern != null && dynamicPattern.size >= 4 -> {
                ResolvedBlackLevelState(
                    pos00 = dynamicPattern[0],
                    pos01 = dynamicPattern[1],
                    pos10 = dynamicPattern[2],
                    pos11 = dynamicPattern[3],
                    whiteLevel = whiteLevel,
                    cfaPattern = cfaPattern,
                    provenance = CalibrationProvenance.DYNAMIC_FRAME_METADATA
                )
            }
            staticPattern != null && staticPattern.size >= 4 -> {
                ResolvedBlackLevelState(
                    pos00 = staticPattern[0],
                    pos01 = staticPattern[1],
                    pos10 = staticPattern[2],
                    pos11 = staticPattern[3],
                    whiteLevel = whiteLevel,
                    cfaPattern = cfaPattern,
                    provenance = CalibrationProvenance.STATIC_CAMERA_METADATA
                )
            }
            measuredPattern != null && measuredPattern.size >= 4 -> {
                ResolvedBlackLevelState(
                    pos00 = measuredPattern[0],
                    pos01 = measuredPattern[1],
                    pos10 = measuredPattern[2],
                    pos11 = measuredPattern[3],
                    whiteLevel = whiteLevel,
                    cfaPattern = cfaPattern,
                    provenance = CalibrationProvenance.MEASURED_PER_LENS
                )
            }
            else -> {
                // Conservative generic fallback: 64.0 for all 4 positions
                ResolvedBlackLevelState(
                    pos00 = 64.0f,
                    pos01 = 64.0f,
                    pos10 = 64.0f,
                    pos11 = 64.0f,
                    whiteLevel = whiteLevel,
                    cfaPattern = cfaPattern,
                    provenance = CalibrationProvenance.GENERIC_CONSERVATIVE_FALLBACK
                )
            }
        }
    }

    /**
     * Resolves noise model parameters A and B following the AUTO hierarchy.
     */
    fun resolveNoiseModel(
        dynamicA: Float?,
        dynamicB: Float?,
        measuredA: Float?,
        measuredB: Float?
    ): ResolvedNoiseModelState {
        return when {
            dynamicA != null && dynamicB != null -> {
                ResolvedNoiseModelState(
                    noiseA = dynamicA,
                    noiseB = dynamicB,
                    provenance = CalibrationProvenance.DYNAMIC_FRAME_METADATA
                )
            }
            measuredA != null && measuredB != null -> {
                ResolvedNoiseModelState(
                    noiseA = measuredA,
                    noiseB = measuredB,
                    provenance = CalibrationProvenance.MEASURED_PER_LENS
                )
            }
            else -> {
                // Conservative generic fallback
                ResolvedNoiseModelState(
                    noiseA = 0.001f,
                    noiseB = 0.00005f,
                    provenance = CalibrationProvenance.GENERIC_CONSERVATIVE_FALLBACK
                )
            }
        }
    }

    /**
     * Resolves 3x3 color transform matrix following the AUTO hierarchy.
     */
    fun resolveColorTransform(
        staticMatrix: List<Float>?,
        illuminantName: String = "AUTO"
    ): ResolvedColorTransformState {
        return if (staticMatrix != null && staticMatrix.size == 9) {
            ResolvedColorTransformState(
                matrix3x3 = staticMatrix,
                illuminant = illuminantName,
                provenance = CalibrationProvenance.STATIC_CAMERA_METADATA
            )
        } else {
            ResolvedColorTransformState(
                matrix3x3 = listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
                illuminant = "IDENTITY_FALLBACK",
                provenance = CalibrationProvenance.GENERIC_CONSERVATIVE_FALLBACK
            )
        }
    }
}
