package com.bncam.core.isp.raw10

/** Pure decision layer used by DngWriter before any DNG bytes are emitted. */
object RawDngAuthorityContract {
    data class Input(
        val calibrationPresent: Boolean,
        val bindingSafe: Boolean,
        val authorityProfileMatch: Boolean,
        val characteristicsSourceMatches: Boolean,
        val captureResultSourceMatches: Boolean,
        val runtimeCameraIdMatches: Boolean,
        val frameTimestampMatches: Boolean
    )

    data class Decision(
        val safeForDng: Boolean,
        val reason: String
    )

    fun evaluate(input: Input): Decision {
        val reason = when {
            !input.calibrationPresent -> "CALIBRATION_UNAVAILABLE"
            !input.bindingSafe -> "CALIBRATION_BINDING_UNSAFE"
            !input.authorityProfileMatch -> "AUTHORITY_PROFILE_MISMATCH"
            !input.characteristicsSourceMatches -> "CHARACTERISTICS_SOURCE_MISMATCH"
            !input.captureResultSourceMatches -> "CAPTURE_RESULT_SOURCE_MISMATCH"
            !input.runtimeCameraIdMatches -> "RUNTIME_CAMERA_ID_MISMATCH"
            !input.frameTimestampMatches -> "FRAME_TIMESTAMP_MISMATCH"
            else -> "none"
        }
        return Decision(reason == "none", reason)
    }
}
