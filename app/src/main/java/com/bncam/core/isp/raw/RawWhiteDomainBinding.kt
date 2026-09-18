package com.bncam.core.isp.raw

import com.bncam.core.quality.RenderQualityConfig
import com.bncam.core.quality.SensorAuthorityUnavailableException
import com.bncam.data.settings.WhiteLevelModes
import com.bncam.data.settings.WhiteLevelRuntimeRegistry

/**
 * Binds the one developed-RAW White Level authority to a resolved [RawDomainContract].
 *
 * Physical/native/payload white values remain untouched for DNG and RAW16 semantics. Only the
 * developed JPEG/noise normalization white is replaced. Manual BnCam full-scale presets are exact
 * developed-domain white code values; they are not remapped to the physical DNG payload white.
 */
object RawWhiteDomainBinding {
    fun bindForQualityConfig(
        contract: RawDomainContract,
        @Suppress("UNUSED_PARAMETER") qualityConfig: RenderQualityConfig
    ): RawDomainContract {
        val runtimeResolution = contract.lensId.takeIf { it.isNotBlank() }
            ?.let { WhiteLevelRuntimeRegistry.resolve(it) }
        val runtime = runtimeResolution?.settings?.takeIf { runtimeResolution.settingsReady }
        val mode = RawWhiteAuthorityPolicy.parseMode(runtime?.mode ?: WhiteLevelModes.AUTO)
        val bound = bindDevelopedAuthority(
            contract = contract,
            requestedMode = mode,
            manualWhiteLevel = runtime?.manualOverrideOrNull()
        )
        if (runtimeResolution != null && !runtimeResolution.settingsReady) {
            return bound.copy(
                validationWarnings = (bound.validationWarnings +
                    "White Level state unavailable at RAW bind; Auto Camera2 authority used")
                    .distinct()
            )
        }
        return bound
    }

    fun bindDevelopedAuthority(
        contract: RawDomainContract,
        requestedMode: RawWhiteAuthorityMode,
        manualWhiteLevel: Int? = null
    ): RawDomainContract {
        val decision = RawWhiteAuthorityPolicy.resolve(
            dynamicWhiteLevel = contract.sensorDynamicWhiteLevel,
            staticWhiteLevel = contract.sensorInfoWhiteLevel,
            requestedMode = requestedMode,
            manualWhiteLevel = manualWhiteLevel
        )
        val selected = decision.sourceWhiteLevel
            ?: throw SensorAuthorityUnavailableException("UNSAFE_TO_PROCESS:WHITE_LEVEL_UNAVAILABLE")

        // Manual BnCam presets patch the developed engine white-level authority as literal code values.
        // Keep the selected value literal in the developed RAW domain. Physical RAW10 canonicalization
        // may still expand its payload to a different white level, but that DNG/master contract must
        // not neutralize the manual developed override by scaling it back to physical full-scale.
        val mapped = selected.coerceIn(1, 65535)

        val maxDevelopedBlack = contract.developedRawBlackLevels
            .filter { it.isFinite() }
            .maxOrNull()
            ?: 0f
        val safePhysicalWhite = contract.payloadWhiteLevel.coerceIn(1, 65535)
        val developedWhite: Int
        val developedSource: String
        val fallbackReason: String
        val manualApplied: Boolean
        val metadataAuthoritative: Boolean

        if (mapped.toFloat() > maxDevelopedBlack) {
            developedWhite = mapped
            developedSource = decision.source
            fallbackReason = decision.fallbackReason
            manualApplied = decision.manualOverrideActive
            metadataAuthoritative = decision.metadataAuthoritative
        } else if (safePhysicalWhite.toFloat() > maxDevelopedBlack) {
            developedWhite = safePhysicalWhite
            developedSource = "WHITE_BLACK_INVARIANT_PHYSICAL_PAYLOAD_FALLBACK: ${contract.chosenWhiteLevelSource}"
            fallbackReason = "selected_white_not_above_developed_black_physical_payload_used"
            manualApplied = false
            metadataAuthoritative = contract.sensorDynamicWhiteLevel != null ||
                contract.sensorInfoWhiteLevel != null
        } else {
            throw SensorAuthorityUnavailableException(
                "UNSAFE_TO_PROCESS:WHITE_LEVEL_NOT_ABOVE_BLACK_LEVEL"
            )
        }

        val scale = if (decision.manualOverrideActive && selected > 0) {
            developedWhite.toFloat() / selected.toFloat()
        } else {
            1f
        }
        val warnings = contract.validationWarnings.toMutableList()
        if (manualApplied) {
            warnings += "Developed RAW white authority=MANUAL; physical RAW16/DNG white metadata remains unchanged"
        }
        if (fallbackReason != "none") {
            warnings += "Developed RAW white fallback: $fallbackReason"
        }

        return contract.copy(
            developedRawWhiteLevel = developedWhite,
            developedRawWhiteLevelSource = developedSource,
            developedWhiteMetadataAuthoritative = metadataAuthoritative,
            developedWhiteFallbackReason = fallbackReason,
            developedWhiteManualOverrideUsed = manualApplied,
            developedWhiteScaleFactor = scale,
            manualOverrideUsed = contract.manualOverrideUsed,
            validationWarnings = warnings.distinct()
        )
    }


}
