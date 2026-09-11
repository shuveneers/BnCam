package com.bncam.core.capture

/**
 * Final fail-closed boundary before a RAW-derived JPEG/DNG may be published.
 *
 * This gate intentionally does not "repair" colour. It only proves that the RAW pixels,
 * metadata authority, calibration binding and colour interpretation all belong to one coherent
 * sensor route. A failure must remain visible instead of publishing a plausible-looking frame
 * built from foreign/logical-parent metadata.
 */
object RawPublicationIntegrityGate {
    data class Input(
        val provenanceSafe: Boolean,
        val coreMetadataSafe: Boolean,
        val calibrationBindingSafe: Boolean,
        val authorityProfileMatch: Boolean,
        val cfaMatched: Boolean,
        val whiteBalanceValid: Boolean,
        val colorMatrixValid: Boolean,
        val colorMatrixIdentityFallbackUsed: Boolean,
        val blackWhiteRangeValid: Boolean
    )

    data class Decision(
        val safeForPublication: Boolean,
        val reason: String
    )

    fun evaluate(input: Input): Decision {
        val reason = when {
            !input.provenanceSafe -> "RAW_METADATA_PROVENANCE_UNSAFE"
            !input.coreMetadataSafe -> "CORE_RAW_METADATA_UNSAFE"
            !input.calibrationBindingSafe -> "CALIBRATION_BINDING_UNSAFE"
            !input.authorityProfileMatch -> "CALIBRATION_AUTHORITY_PROFILE_MISMATCH"
            !input.cfaMatched -> "CFA_AUTHORITY_MISMATCH"
            !input.whiteBalanceValid -> "WHITE_BALANCE_INVALID"
            !input.colorMatrixValid -> "COLOR_MATRIX_INVALID"
            input.colorMatrixIdentityFallbackUsed -> "COLOR_MATRIX_IDENTITY_FALLBACK_FORBIDDEN"
            !input.blackWhiteRangeValid -> "BLACK_WHITE_NORMALIZATION_INVALID"
            else -> "none"
        }
        return Decision(reason == "none", reason)
    }
}
