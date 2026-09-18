package com.bncam.core.isp.raw

enum class RawWhiteAuthorityMode {
    AUTO,
    MANUAL
}

data class RawWhiteAuthorityDecision(
    /** Selected white value in the metadata/manual source domain; domain binding happens later. */
    val sourceWhiteLevel: Int?,
    val source: String,
    val metadataAuthoritative: Boolean,
    val fallbackReason: String,
    val mode: RawWhiteAuthorityMode,
    val manualOverrideActive: Boolean,
    val dynamicMetadataAvailable: Boolean,
    val staticMetadataAvailable: Boolean
) {
    val authorityAvailable: Boolean get() = sourceWhiteLevel != null
}

/**
 * Single selection policy for developed-RAW White Level authority.
 *
 * AUTO precedence is based on the active frame/domain rather than a RAW10-capability heuristic:
 *   1. valid same-frame CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL
 *   2. valid CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL
 *   3. no fabricated sensor white; caller must fail closed or use an explicitly non-sensor path
 *
 * MANUAL accepts only the explicit full-scale presets exposed by BnCam. Invalid manual input never
 * becomes an authority: valid Camera2 metadata is used as a safety fallback, otherwise authority
 * remains unavailable.
 *
 * This policy does not scale or clamp to RAW10/RAW_SENSOR payload domains. RawWhiteDomainBinding
 * owns that conversion so White and Black can be transformed together exactly once.
 */
object RawWhiteAuthorityPolicy {
    private val supportedManualValues = setOf(1023, 4095, 16383, 65535)

    fun parseMode(modeName: String?): RawWhiteAuthorityMode =
        if (modeName.equals("Manual", ignoreCase = true)) {
            RawWhiteAuthorityMode.MANUAL
        } else {
            RawWhiteAuthorityMode.AUTO
        }

    fun isSupportedManualWhite(value: Int?): Boolean =
        value != null && value in supportedManualValues

    fun resolve(
        dynamicWhiteLevel: Int?,
        staticWhiteLevel: Int?,
        requestedMode: RawWhiteAuthorityMode,
        manualWhiteLevel: Int? = null
    ): RawWhiteAuthorityDecision {
        val dynamic = validMetadataWhite(dynamicWhiteLevel)
        val static = validMetadataWhite(staticWhiteLevel)

        if (requestedMode == RawWhiteAuthorityMode.MANUAL) {
            if (isSupportedManualWhite(manualWhiteLevel)) {
                return RawWhiteAuthorityDecision(
                    sourceWhiteLevel = manualWhiteLevel,
                    source = "MANUAL_OVERRIDE: BnCam full-scale preset=$manualWhiteLevel",
                    metadataAuthoritative = false,
                    fallbackReason = "none",
                    mode = requestedMode,
                    manualOverrideActive = true,
                    dynamicMetadataAvailable = dynamic != null,
                    staticMetadataAvailable = static != null
                )
            }

            if (dynamic != null) {
                return RawWhiteAuthorityDecision(
                    sourceWhiteLevel = dynamic,
                    source = "MANUAL_INVALID_DYNAMIC_METADATA_FALLBACK: CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL",
                    metadataAuthoritative = true,
                    fallbackReason = "manual_white_override_invalid_dynamic_metadata_used",
                    mode = requestedMode,
                    manualOverrideActive = false,
                    dynamicMetadataAvailable = true,
                    staticMetadataAvailable = static != null
                )
            }

            if (static != null) {
                return RawWhiteAuthorityDecision(
                    sourceWhiteLevel = static,
                    source = "MANUAL_INVALID_STATIC_METADATA_FALLBACK: CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL",
                    metadataAuthoritative = true,
                    fallbackReason = "manual_white_override_invalid_static_metadata_used",
                    mode = requestedMode,
                    manualOverrideActive = false,
                    dynamicMetadataAvailable = false,
                    staticMetadataAvailable = true
                )
            }

            return unavailable(
                mode = requestedMode,
                reason = "manual_white_override_invalid_and_camera2_white_metadata_unavailable"
            )
        }

        if (dynamic != null) {
            return RawWhiteAuthorityDecision(
                sourceWhiteLevel = dynamic,
                source = "AUTO_DYNAMIC_METADATA: CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL",
                metadataAuthoritative = true,
                fallbackReason = "none",
                mode = RawWhiteAuthorityMode.AUTO,
                manualOverrideActive = false,
                dynamicMetadataAvailable = true,
                staticMetadataAvailable = static != null
            )
        }

        if (static != null) {
            return RawWhiteAuthorityDecision(
                sourceWhiteLevel = static,
                source = "AUTO_STATIC_METADATA: CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL",
                metadataAuthoritative = true,
                fallbackReason = if (dynamicWhiteLevel == null) {
                    "dynamic_white_metadata_unavailable_static_used"
                } else {
                    "dynamic_white_metadata_invalid_static_used"
                },
                mode = RawWhiteAuthorityMode.AUTO,
                manualOverrideActive = false,
                dynamicMetadataAvailable = false,
                staticMetadataAvailable = true
            )
        }

        return unavailable(
            mode = RawWhiteAuthorityMode.AUTO,
            reason = when {
                dynamicWhiteLevel != null || staticWhiteLevel != null ->
                    "camera2_white_metadata_invalid"
                else -> "camera2_white_metadata_unavailable"
            }
        )
    }

    private fun validMetadataWhite(value: Int?): Int? =
        value?.takeIf { it in 1..65535 }

    private fun unavailable(
        mode: RawWhiteAuthorityMode,
        reason: String
    ) = RawWhiteAuthorityDecision(
        sourceWhiteLevel = null,
        source = "WHITE_AUTHORITY_UNAVAILABLE",
        metadataAuthoritative = false,
        fallbackReason = reason,
        mode = mode,
        manualOverrideActive = false,
        dynamicMetadataAvailable = false,
        staticMetadataAvailable = false
    )
}
