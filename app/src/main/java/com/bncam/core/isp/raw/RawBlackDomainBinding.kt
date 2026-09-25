package com.bncam.core.isp.raw

import com.bncam.core.quality.RenderQualityConfig
import com.bncam.data.settings.BlackLevelRuntimeRegistry
import com.bncam.data.settings.BlackLevelTypes
import kotlin.math.roundToInt

/**
 * Binds the single developed-RAW black authority to a resolved [RawDomainContract] without
 * mutating the physical RAW16/DNG payload contract.
 *
 * Black Level v2 is the only developed-RAW choice owner. If v2 state is temporarily unavailable,
 * the safe fallback is System/Camera2 metadata — never the retired LensHardwareSettings override.
 */
object RawBlackDomainBinding {
    fun bindForQualityConfig(
        contract: RawDomainContract,
        qualityConfig: RenderQualityConfig
    ): RawDomainContract {
        val calibration = qualityConfig.finalCalibration
        val lensId = contract.lensId.takeIf { it.isNotBlank() } ?: qualityConfig.lensHardwareSettings?.lensId
        val runtimeResolution = lensId?.let { BlackLevelRuntimeRegistry.resolve(it) }
        val runtime = runtimeResolution?.settings?.takeIf { runtimeResolution.settingsReady }

        val modeName = runtime?.type ?: BlackLevelTypes.SYSTEM
        val manualRuntime = runtime?.takeIf { it.type == BlackLevelTypes.MANUAL }
        val manualRuntimeSelected = manualRuntime != null
        val fallbackLevels = if (manualRuntime != null) {
            RawLevelOrder.mosaicToCanonical(
                manualRuntime.manualValues.map { it.toFloat() },
                contract.cfaPattern
            )
        } else {
            // Safety fallback is physical calibration only. Never reuse effectiveBlackLevels,
            // because that field belongs to the retired pre-v2 override layer.
            calibration?.base?.baseBlackLevels?.toList() ?: qualityConfig.blackLevels
        }
        val fallbackSource = when {
            manualRuntimeSelected -> "Black Level v2 Manual mosaic override"
            runtime?.type == BlackLevelTypes.DYNAMIC -> "Black Level v2 System baseline fallback"
            runtime?.type == BlackLevelTypes.SYSTEM -> "Black Level v2 exact-frame Camera2 black fallback"
            else -> calibration?.base?.baseBlackLevelSource
                ?: "Black Level v2 state unavailable; System safety fallback (${qualityConfig.blackLevelSource})"
        }

        val fallbackWhiteLevel = if (manualRuntimeSelected) {
            contract.sensorInfoWhiteLevel ?: calibration?.base?.baseWhiteLevel ?: qualityConfig.whiteLevel
        } else {
            calibration?.base?.baseWhiteLevel ?: qualityConfig.whiteLevel
        }

        val bound = bindFromSourceDomain(
            contract = contract,
            modeName = modeName,
            dynamicStrength = runtime?.dynamicStrength ?: 1.0f,
            fallbackCanonicalLevels = fallbackLevels,
            fallbackWhiteLevel = fallbackWhiteLevel,
            fallbackSource = fallbackSource
        )
        if (runtimeResolution != null && !runtimeResolution.settingsReady) {
            return bound.copy(
                validationWarnings = (bound.validationWarnings +
                    "Black Level v2 state unavailable at RAW bind; System safety authority used")
                    .distinct()
            )
        }
        return bound
    }

    fun scaleCanonicalToPayload(
        canonicalLevels: List<Float>,
        sourceWhiteLevel: Int,
        payloadWhiteLevel: Int
    ): List<Float> {
        val safePayloadWhite = payloadWhiteLevel.coerceAtLeast(1)
        val scale = if (sourceWhiteLevel > 0 && sourceWhiteLevel != safePayloadWhite) {
            safePayloadWhite.toFloat() / sourceWhiteLevel.toFloat()
        } else {
            1f
        }
        return List(4) { index ->
            ((canonicalLevels.getOrNull(index) ?: 0f) * scale)
                .takeIf { it.isFinite() }
                ?.coerceIn(0f, safePayloadWhite.coerceAtLeast(2) - 1f)
                ?: 0f
        }
    }

    fun bindFromSourceDomain(
        contract: RawDomainContract,
        modeName: String?,
        fallbackCanonicalLevels: List<Float>,
        fallbackWhiteLevel: Int,
        fallbackSource: String,
        dynamicStrength: Float = 1.0f
    ): RawDomainContract = bindDevelopedAuthority(
        contract = contract,
        requestedMode = RawBlackAuthorityPolicy.parseMode(modeName),
        dynamicStrength = dynamicStrength,
        fallbackCanonicalLevelsInPayloadDomain = scaleCanonicalToPayload(
            canonicalLevels = fallbackCanonicalLevels,
            sourceWhiteLevel = fallbackWhiteLevel,
            payloadWhiteLevel = contract.payloadWhiteLevel
        ),
        fallbackSource = fallbackSource
    )

    fun bindDevelopedAuthority(
        contract: RawDomainContract,
        requestedMode: RawBlackAuthorityMode,
        fallbackCanonicalLevelsInPayloadDomain: List<Float>,
        fallbackSource: String,
        dynamicStrength: Float = 1.0f
    ): RawDomainContract {
        val dynamic = contract.sensorDynamicBlackLevelValues.validMosaicLevels()
        val staticMetadataMissing = contract.validationWarnings.any { warning ->
            warning.contains("SENSOR_BLACK_LEVEL_PATTERN missing", ignoreCase = true)
        }
        val static = contract.sensorBlackLevelPatternValues
            .validMosaicLevels()
            ?.takeUnless { staticMetadataMissing }

        val decision = RawBlackAuthorityPolicy.resolveSystemDynamic(
            systemMosaicLevels = static,
            systemSource = "CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN",
            dynamicMosaicLevels = dynamic,
            dynamicSource = "CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL",
            cfaPattern = contract.cfaPattern,
            fallbackCanonicalLevels = fallbackCanonicalLevelsInPayloadDomain,
            fallbackSource = fallbackSource,
            requestedMode = requestedMode,
            dynamicStrength = dynamicStrength
        )
        val safeWhite = contract.payloadWhiteLevel.coerceAtLeast(2)
        val developed = decision.canonicalLevels.map { value ->
            value.takeIf { it.isFinite() }
                ?.coerceIn(0f, safeWhite - 1f)
                ?: 0f
        }

        val cleanedWarnings = contract.validationWarnings.filterNot { warning ->
            warning.startsWith("P0 metadata-first black authority suppressed lens black override") ||
                warning.startsWith("Dynamic black requested but same-frame")
        }.toMutableList()
        when {
            decision.manualOverrideActive -> cleanedWarnings +=
                "Developed RAW black authority=MANUAL; physical RAW16/DNG black metadata remains unchanged"
            decision.mode == RawBlackAuthorityMode.DYNAMIC && !decision.dynamicMetadataAvailable -> cleanedWarnings +=
                "Dynamic black requested but same-frame SENSOR_DYNAMIC_BLACK_LEVEL unavailable; ${decision.fallbackReason}"
            decision.mode == RawBlackAuthorityMode.DYNAMIC && !decision.systemMetadataAvailable -> cleanedWarnings +=
                "Dynamic black blend uses controlled System fallback base because SENSOR_BLACK_LEVEL_PATTERN is unavailable"
            decision.mode == RawBlackAuthorityMode.SYSTEM &&
                !decision.dynamicMetadataAvailable && !decision.systemMetadataAvailable -> cleanedWarnings +=
                "Same-frame dynamic and static System black metadata unavailable; ${decision.fallbackReason}"
            decision.mode == RawBlackAuthorityMode.SYSTEM &&
                !decision.dynamicMetadataAvailable && decision.systemMetadataAvailable -> cleanedWarnings +=
                "Same-frame SENSOR_DYNAMIC_BLACK_LEVEL unavailable; static SENSOR_BLACK_LEVEL_PATTERN used"
        }

        return contract.copy(
            // Never replace nativeBlackLevels/payloadBlackLevels/chosenBlackLevelSource here.
            // Those describe the physical RAW16/DNG payload contract.
            developedRawBlackLevels = developed,
            developedRawBlackLevelSource = decision.source,
            developedBlackMetadataAuthoritative = decision.metadataAuthoritative,
            developedBlackFallbackReason = decision.fallbackReason,
            manualOverrideUsed = contract.manualOverrideUsed || decision.manualOverrideActive,
            validationWarnings = cleanedWarnings.distinct()
        )
    }

    /** Diagnostic projection only; native/DNG payload values stay integer Camera2-domain values. */
    fun developedMosaicRounded(contract: RawDomainContract): List<Int> =
        RawLevelOrder.canonicalToMosaic(contract.developedRawBlackLevels, contract.cfaPattern)
            .map { it.roundToInt().coerceIn(0, contract.payloadWhiteLevel.coerceAtLeast(2) - 1) }

    private fun List<Float>?.validMosaicLevels(): List<Float>? = this
        ?.takeIf { it.size >= 4 }
        ?.take(4)
        ?.takeIf { levels -> levels.all { it.isFinite() && it >= 0f } }
}
