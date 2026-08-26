package com.bncam.data.baseline

enum class CalibrationProvenance {
    DYNAMIC_FRAME_METADATA,
    STATIC_CAMERA_METADATA,
    MEASURED_PER_LENS,
    MANUAL_CALIBRATION,
    GENERIC_CONSERVATIVE_FALLBACK,
    UNAVAILABLE
}

enum class CameraTopologyType {
    STANDALONE_LOGICAL_CAMERA,
    LOGICAL_MULTI_CAMERA,
    PHYSICAL_CAMERA_MEMBER
}

data class ResolvedBlackLevelState(
    val pos00: Float,
    val pos01: Float,
    val pos10: Float,
    val pos11: Float,
    val whiteLevel: Float,
    val cfaPattern: String, // RGGB, GRBG, GBRG, BGGR
    val provenance: CalibrationProvenance
)

data class ResolvedNoiseModelState(
    val noiseA: Float,
    val noiseB: Float,
    val provenance: CalibrationProvenance
)

data class ResolvedColorTransformState(
    val matrix3x3: List<Float>,
    val illuminant: String,
    val provenance: CalibrationProvenance
)
