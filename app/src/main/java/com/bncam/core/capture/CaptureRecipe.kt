package com.bncam.core.capture

import com.bncam.core.quality.RenderQualityPreferencesSnapshot
import com.bncam.core.tracing.StableJson
import com.bncam.data.settings.ResolvedLensHardwareSettings

data class CaptureDebugSettings(
    val shotLoggingEnabled: Boolean,
    val saveLocationData: Boolean,
    val logSummary: Boolean,
    val logActiveMode: Boolean,
    val logProfileSettings: Boolean,
    val logFrameAnalysis: Boolean,
    val logWarnings: Boolean,
    val logPipelineDebug: Boolean,
    val logVendorInjection: Boolean
)

data class CaptureOutputSettings(
    val photoPrefix: String,
    val saveLocation: String,
    val mirrorFrontPreview: Boolean,
    val watermarkEnabled: Boolean,
    val watermarkStyle: String,
    val watermarkSignature: String,
    val watermarkAuthor: Boolean,
    val exifSaveSignature: Boolean,
    val exifExtraData: Boolean
)

data class CaptureSelectionSettings(
    val basePosition: String,
    val baseCandidateCount: Int,
    val baseIncludeInMerge: Boolean,
    val baseBias: String,
    val baseTemporalBias: Float,
    val selectionRequestedFrames: Int,
    val frameBias: String,
    val acceptAll: Boolean,
    val rejectDuplicates: Boolean,
    val useAlignableOnly: Boolean,
    val discardFirst: Boolean,
    val preferRecent: Boolean,
    val ignoreStale: Boolean
)

data class CaptureMergeSettings(
    val subPixel: Boolean,
    val linearInterpolation: Boolean,
    val strictness: Float,
    val maximumShiftPixels: Int
)

data class CaptureExecutionSettings(
    val profileName: String,
    val cameraSoundEnabled: Boolean,
    val flashMode: String,
    val opticalStabilization: Boolean,
    val hotPixelMode: String,
    val noiseReductionHint: String,
    val edgeModeHint: String,
    val tonemapHint: String,
    val antiBanding: String,
    val debug: CaptureDebugSettings,
    val output: CaptureOutputSettings,
    val selection: CaptureSelectionSettings,
    val merge: CaptureMergeSettings,
    val lensHardwareSettings: ResolvedLensHardwareSettings,
    val renderPreferences: RenderQualityPreferencesSnapshot,
    val captureExposurePreferences: CaptureExposurePreferences = CaptureExposurePreferences()
)

data class CapabilityResolutionRecord(
    val capability: String,
    val requested: String,
    val supported: String,
    val resolved: String,
    val reason: String
)

enum class DngSource {
    NOT_APPLICABLE,
    ANCHOR_RAW,
    SELECTED_RAW,
    FUSED_RAW
}

/**
 * Immutable shutter-time capture snapshot. Capture runners receive this object and must not
 * re-resolve captured settings from DataStore.
 */
class CaptureRecipe private constructor(
    val schemaVersion: Int,
    val applicationVersion: String,
    val activeProfileIdentifier: String,
    val profileVersionHash: String,
    val logicalCameraId: String,
    val physicalCameraId: String?,
    val lensIdentifier: String,
    val frameSource: FrameOrigin,
    val captureMode: CaptureMode,
    val outputPolicy: OutputPolicy,
    val pipelineGenerationId: Int,
    val requestedFrameCount: Int,
    val effectiveFrameCount: Int,
    val bufferCapacity: Int,
    val warmBufferResolution: FrameCapacityResolution,
    val candidateCountResolution: FrameCapacityResolution,
    val processingFrameResolution: FrameCapacityResolution,
    val dngMasterFrameResolution: FrameCapacityResolution,
    val dngSource: DngSource,
    val frameSelectionMethod: MethodResolution,
    val anchorSelectionMethod: MethodResolution,
    val alignmentMethod: MethodResolution,
    val fusionMethod: MethodResolution,
    val demosaicMethod: MethodResolution,
    val exposureStrategy: String,
    val computationalHdrRequested: Boolean,
    val computationalHdrRouteEnabled: Boolean,
    val computationalHdrResolutionReason: String,
    val hdrEnhancedFrameSetting: String,
    val phoneAssistanceSensorsEnabled: Boolean,
    val computeBackendId: String,
    val hardwareOverrideFingerprint: String,
    val thermalState: String,
    val captureTimestampEpochMs: Long,
    val capabilityResolutions: List<CapabilityResolutionRecord>,
    val executionSettings: CaptureExecutionSettings
) {
    init {
        require(schemaVersion > 0)
        require(activeProfileIdentifier.isNotBlank())
        require(profileVersionHash.isNotBlank())
        require(logicalCameraId.isNotBlank())
        require(lensIdentifier.isNotBlank())
        require(bufferCapacity > 0)
        require(effectiveFrameCount > 0)
    }

    fun toJson(): String = StableJson.encode(
        linkedMapOf(
            "schemaVersion" to schemaVersion,
            "applicationVersion" to applicationVersion,
            "activeProfileIdentifier" to activeProfileIdentifier,
            "profileVersionHash" to profileVersionHash,
            "logicalCameraId" to logicalCameraId,
            "physicalCameraId" to physicalCameraId,
            "lensIdentifier" to lensIdentifier,
            "frameSource" to frameSource.name,
            "captureMode" to captureMode.name,
            "outputPolicy" to outputPolicy.name,
            "pipelineGenerationId" to pipelineGenerationId,
            "requestedFrameCount" to requestedFrameCount,
            "effectiveFrameCount" to effectiveFrameCount,
            "bufferCapacity" to bufferCapacity,
            "dngSource" to dngSource.name,
            "frameCapacity" to linkedMapOf(
                "warmBuffer" to warmBufferResolution.asJsonMap(),
                "selectableCandidates" to candidateCountResolution.asJsonMap(),
                "processingFrames" to processingFrameResolution.asJsonMap(),
                "dngMasterFrames" to dngMasterFrameResolution.asJsonMap()
            ),
            "methods" to linkedMapOf(
                "frameSelection" to frameSelectionMethod.asJsonMap(),
                "anchorSelection" to anchorSelectionMethod.asJsonMap(),
                "alignment" to alignmentMethod.asJsonMap(),
                "fusion" to fusionMethod.asJsonMap(),
                "demosaic" to demosaicMethod.asJsonMap()
            ),
            "exposureStrategy" to exposureStrategy,
            "captureExposurePreferences" to executionSettings.captureExposurePreferences.debugMap(),
            "computationalHdr" to linkedMapOf(
                "requested" to computationalHdrRequested,
                "routeEnabled" to computationalHdrRouteEnabled,
                "resolutionReason" to computationalHdrResolutionReason,
                "hdrEnhancedFrameSetting" to hdrEnhancedFrameSetting
            ),
            "phoneAssistanceSensorsEnabled" to phoneAssistanceSensorsEnabled,
            "computeBackendId" to computeBackendId,
            "ispSettings" to linkedMapOf(
                "profileId" to executionSettings.renderPreferences.profileId,
                "jpegQuality" to executionSettings.renderPreferences.jpegQuality,
                "demosaicRequested" to
                    executionSettings.renderPreferences.demosaic.requestedMode.displayName,
                "demosaicResolved" to
                    executionSettings.renderPreferences.demosaic.resolvedAlgorithm.name,
                "toneCurvePreset" to executionSettings.renderPreferences.curves.tonePreset,
                "toneCurveNodes" to executionSettings.renderPreferences.curves.toneNodes,
                "gammaCurvePreset" to executionSettings.renderPreferences.curves.gammaPreset,
                "gammaCurveNodes" to executionSettings.renderPreferences.curves.gammaNodes,
                "sectionCurvePreset" to executionSettings.renderPreferences.curves.sectionPreset,
                "sectionCurveNodes" to executionSettings.renderPreferences.curves.sectionNodes,
                "awb" to linkedMapOf(
                    "mode" to executionSettings.renderPreferences.profileAwb.mode,
                    "brand" to executionSettings.renderPreferences.profileAwb.brand,
                    "preset" to executionSettings.renderPreferences.profileAwb.preset,
                    "kelvin" to executionSettings.renderPreferences.profileAwb.kelvin,
                    "model" to executionSettings.renderPreferences.profileAwb.illuminantModel,
                    "tint" to executionSettings.renderPreferences.profileAwb.tint
                ),
                "spectraProfile" to executionSettings.renderPreferences.spectraProfileMap(),
                "colorProfile" to executionSettings.renderPreferences.colorProfileMap(),
                "liveViewfinderTuning" to linkedMapOf(
                    "whiteBalanceKelvin" to executionSettings.renderPreferences.liveViewfinderTuning.whiteBalanceKelvin,
                    "saturationOffset" to executionSettings.renderPreferences.liveViewfinderTuning.saturationOffset,
                    "contrastOffset" to executionSettings.renderPreferences.liveViewfinderTuning.contrastOffset,
                    "revision" to executionSettings.renderPreferences.liveViewfinderTuning.revision
                ),
                "resolvedSettings" to
                    executionSettings.renderPreferences.resolvedIspSettings.activeSettings
                        .sortedBy { it.key }
                        .map {
                        linkedMapOf(
                            "key" to it.key,
                            "runtimeScope" to it.runtimeScope.name,
                            "active" to it.active,
                            "value" to it.value.asDebugString(),
                            "rawValue" to it.value.toString(),
                            "mappedRuntimeValue" to it.mappedRuntimeValue
                        )
                    }
            ),
            "cameraRequestSettings" to linkedMapOf(
                "cameraSoundEnabled" to executionSettings.cameraSoundEnabled,
                "flashMode" to executionSettings.flashMode,
                "opticalStabilization" to executionSettings.opticalStabilization,
                "hotPixelMode" to executionSettings.hotPixelMode,
                "noiseReductionHint" to executionSettings.noiseReductionHint,
                "edgeModeHint" to executionSettings.edgeModeHint,
                "tonemapHint" to executionSettings.tonemapHint,
                "antiBanding" to executionSettings.antiBanding
            ),
            "selectionSettings" to linkedMapOf(
                "basePosition" to executionSettings.selection.basePosition,
                "baseCandidateCount" to executionSettings.selection.baseCandidateCount,
                "baseIncludeInMerge" to executionSettings.selection.baseIncludeInMerge,
                "baseBias" to executionSettings.selection.baseBias,
                "baseTemporalBias" to executionSettings.selection.baseTemporalBias,
                "selectionRequestedFrames" to
                    executionSettings.selection.selectionRequestedFrames,
                "frameBias" to executionSettings.selection.frameBias,
                "acceptAll" to executionSettings.selection.acceptAll,
                "rejectDuplicates" to executionSettings.selection.rejectDuplicates,
                "useAlignableOnly" to executionSettings.selection.useAlignableOnly,
                "discardFirst" to executionSettings.selection.discardFirst,
                "preferRecent" to executionSettings.selection.preferRecent,
                "ignoreStale" to executionSettings.selection.ignoreStale
            ),
            "mergeSettings" to linkedMapOf(
                "subPixel" to executionSettings.merge.subPixel,
                "linearInterpolation" to executionSettings.merge.linearInterpolation,
                "strictness" to executionSettings.merge.strictness,
                "maximumShiftPixels" to executionSettings.merge.maximumShiftPixels
            ),
            "outputSettings" to linkedMapOf(
                "photoPrefixHash" to stablePrivateValueHash(executionSettings.output.photoPrefix),
                "saveLocation" to
                    if (
                        executionSettings.output.saveLocation.isBlank() ||
                        executionSettings.output.saveLocation.equals("DCIM/BnCam", ignoreCase = true)
                    ) {
                        "DEFAULT_MEDIASTORE"
                    } else {
                        "CUSTOM_CONFIGURED_REDACTED"
                    },
                "mirrorFrontPreview" to executionSettings.output.mirrorFrontPreview,
                "watermarkEnabled" to executionSettings.output.watermarkEnabled,
                "watermarkStyle" to executionSettings.output.watermarkStyle,
                "watermarkSignatureHash" to
                    stablePrivateValueHash(executionSettings.output.watermarkSignature),
                "watermarkAuthor" to executionSettings.output.watermarkAuthor,
                "exifSaveSignature" to executionSettings.output.exifSaveSignature,
                "exifExtraData" to executionSettings.output.exifExtraData
            ),
            "debugSettings" to linkedMapOf(
                "shotLoggingEnabled" to executionSettings.debug.shotLoggingEnabled,
                "saveLocationData" to executionSettings.debug.saveLocationData,
                "logSummary" to executionSettings.debug.logSummary,
                "logActiveMode" to executionSettings.debug.logActiveMode,
                "logProfileSettings" to executionSettings.debug.logProfileSettings,
                "logFrameAnalysis" to executionSettings.debug.logFrameAnalysis,
                "logWarnings" to executionSettings.debug.logWarnings,
                "logPipelineDebug" to executionSettings.debug.logPipelineDebug,
                "logVendorInjection" to executionSettings.debug.logVendorInjection
            ),
            "hardwareOverrides" to linkedMapOf(
                "fingerprint" to hardwareOverrideFingerprint,
                "manualEffectActive" to
                    executionSettings.lensHardwareSettings.anyManualEffectActive,
                "warningCount" to executionSettings.lensHardwareSettings.warnings.size
            ),
            "noiseModelRequest" to linkedMapOf(
                "requestedNoiseModelMode" to (if (executionSettings.renderPreferences.noiseTuning.spectraEnabled) "Auto" else "Off"),
                "requestedDynamicIsoCoefficient" to executionSettings.lensHardwareSettings.dynamicIsoCoeff,
                "requestedNoiseModelCalibrationAdjustment" to executionSettings.lensHardwareSettings.noiseModelCalibrationAdjustment,
                "requestedDynamicChromaAuthorityAdjustment" to executionSettings.lensHardwareSettings.dynamicChromaAuthorityAdjustment,
                "requestedDynamicLumaAuthorityAdjustment" to executionSettings.lensHardwareSettings.dynamicLumaAuthorityAdjustment,
                "requestedCalibrationFactor" to executionSettings.lensHardwareSettings.noiseModelCalibrationFactor,
                "requestedChromaUserScale" to executionSettings.lensHardwareSettings.chromaUserScale,
                "requestedLumaUserScale" to executionSettings.lensHardwareSettings.lumaUserScale,
                "requestedOuterRingAuthority" to executionSettings.lensHardwareSettings.outerRingAuthority,
                "stableLensKey" to executionSettings.lensHardwareSettings.lensId,
                "noiseModelResolutionStatus" to "pending"
            ),
            "thermalState" to thermalState,
            "captureTimestampEpochMs" to captureTimestampEpochMs,
            "capabilityResolutions" to capabilityResolutions.map {
                linkedMapOf(
                    "capability" to it.capability,
                    "requested" to it.requested,
                    "supported" to it.supported,
                    "resolved" to it.resolved,
                    "reason" to it.reason
                )
            }
        )
    )

    companion object {
        const val CURRENT_SCHEMA_VERSION = 3

        fun create(input: CaptureRecipeInput): CaptureRecipe = CaptureRecipe(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            applicationVersion = input.applicationVersion,
            activeProfileIdentifier = input.activeProfileIdentifier,
            profileVersionHash = input.profileVersionHash,
            logicalCameraId = input.logicalCameraId,
            physicalCameraId = input.physicalCameraId,
            lensIdentifier = input.lensIdentifier,
            frameSource = input.frameSource,
            captureMode = input.captureMode,
            outputPolicy = input.outputPolicy,
            pipelineGenerationId = input.pipelineGenerationId,
            requestedFrameCount = input.requestedFrameCount,
            effectiveFrameCount = input.processingFrameResolution.effectiveValue,
            bufferCapacity = input.warmBufferResolution.effectiveValue,
            warmBufferResolution = input.warmBufferResolution,
            candidateCountResolution = input.candidateCountResolution,
            processingFrameResolution = input.processingFrameResolution,
            dngMasterFrameResolution = input.dngMasterFrameResolution,
            dngSource = input.dngSource,
            frameSelectionMethod = input.frameSelectionMethod,
            anchorSelectionMethod = input.anchorSelectionMethod,
            alignmentMethod = input.alignmentMethod,
            fusionMethod = input.fusionMethod,
            demosaicMethod = input.demosaicMethod,
            exposureStrategy = input.exposureStrategy,
            computationalHdrRequested = input.computationalHdrRequested,
            computationalHdrRouteEnabled = input.computationalHdrRouteEnabled,
            computationalHdrResolutionReason = input.computationalHdrResolutionReason,
            hdrEnhancedFrameSetting = input.hdrEnhancedFrameSetting,
            phoneAssistanceSensorsEnabled = input.phoneAssistanceSensorsEnabled,
            computeBackendId = input.computeBackendId,
            hardwareOverrideFingerprint = input.hardwareOverrideFingerprint,
            thermalState = input.thermalState,
            captureTimestampEpochMs = input.captureTimestampEpochMs,
            capabilityResolutions = input.capabilityResolutions.toList(),
            executionSettings = input.executionSettings.frozenCopy()
        )
    }
}

data class CaptureRecipeInput(
    val applicationVersion: String,
    val activeProfileIdentifier: String,
    val profileVersionHash: String,
    val logicalCameraId: String,
    val physicalCameraId: String?,
    val lensIdentifier: String,
    val frameSource: FrameOrigin,
    val captureMode: CaptureMode,
    val outputPolicy: OutputPolicy,
    val pipelineGenerationId: Int,
    val requestedFrameCount: Int,
    val warmBufferResolution: FrameCapacityResolution,
    val candidateCountResolution: FrameCapacityResolution,
    val processingFrameResolution: FrameCapacityResolution,
    val dngMasterFrameResolution: FrameCapacityResolution,
    val dngSource: DngSource,
    val frameSelectionMethod: MethodResolution,
    val anchorSelectionMethod: MethodResolution,
    val alignmentMethod: MethodResolution,
    val fusionMethod: MethodResolution,
    val demosaicMethod: MethodResolution,
    val exposureStrategy: String,
    val computationalHdrRequested: Boolean = false,
    val computationalHdrRouteEnabled: Boolean = false,
    val computationalHdrResolutionReason: String = "not_requested",
    val hdrEnhancedFrameSetting: String = "Auto",
    val phoneAssistanceSensorsEnabled: Boolean,
    val computeBackendId: String,
    val hardwareOverrideFingerprint: String,
    val thermalState: String,
    val captureTimestampEpochMs: Long,
    val capabilityResolutions: List<CapabilityResolutionRecord>,
    val executionSettings: CaptureExecutionSettings
)

private fun FrameCapacityResolution.asJsonMap(): Map<String, Any> = linkedMapOf(
    "purpose" to purpose.name,
    "requested" to requestedValue,
    "configuredMaximum" to configuredMaximum,
    "runtimeSafeMaximum" to runtimeSafeMaximum,
    "productMaximum" to productMaximum,
    "effective" to effectiveValue,
    "reason" to resolutionReason.name,
    "limitingReasons" to limitingReasons.map { it.name }
)

private fun MethodResolution.asJsonMap(): Map<String, Any?> = linkedMapOf(
    "requested" to requestedId,
    "requestedAvailability" to requestedAvailability?.name,
    "supported" to supported,
    "resolved" to resolvedId,
    "fallback" to fallback,
    "reason" to reason
)

private fun CaptureExecutionSettings.frozenCopy(): CaptureExecutionSettings {
    val frozenLens = lensHardwareSettings.copy(
        warnings = lensHardwareSettings.warnings.toList(),
        noiseA = lensHardwareSettings.noiseA.toList(),
        noiseB = lensHardwareSettings.noiseB.toList(),
        noiseC = lensHardwareSettings.noiseC.toList(),
        noiseD = lensHardwareSettings.noiseD.toList(),
        manualBlackLevels = lensHardwareSettings.manualBlackLevels.toList(),
        manualColorMatrix = lensHardwareSettings.manualColorMatrix.toList()
    )
    val frozenRender = renderPreferences.copy(
        resolvedIspSettings = renderPreferences.resolvedIspSettings.copy(
            activeSettings = renderPreferences.resolvedIspSettings.activeSettings.toList(),
            hiddenSettings = renderPreferences.resolvedIspSettings.hiddenSettings.toList()
        ),
        curves = renderPreferences.curves.copy(
            toneNodes = renderPreferences.curves.toneNodes.toList(),
            gammaNodes = renderPreferences.curves.gammaNodes.toList(),
            sectionNodes = renderPreferences.curves.sectionNodes.toList()
        )
    )
    return copy(
        lensHardwareSettings = frozenLens,
        renderPreferences = frozenRender
    )
}


internal fun RenderQualityPreferencesSnapshot.spectraProfileMap(): Map<String, Any> = linkedMapOf(
    "neuralMasterAuthority" to noiseTuning.neuralDenoiseStrength,
    "spectraLuma" to noiseTuning.spectraLuma,
    "spectraChroma" to noiseTuning.spectraChroma,
    "spectraDetailProtection" to noiseTuning.spectraDetailProtection,
    "spectraLowFrequency" to noiseTuning.spectraLowFrequency
)

internal fun RenderQualityPreferencesSnapshot.noiseReductionProfileMap(): Map<String, Any> = linkedMapOf(
    "luminance" to noiseReductionTuning.luminance,
    "luminanceDetail" to noiseReductionTuning.luminanceDetail,
    "luminanceContrast" to noiseReductionTuning.luminanceContrast,
    "color" to noiseReductionTuning.color,
    "colorDetail" to noiseReductionTuning.colorDetail,
    "colorSmoothness" to noiseReductionTuning.colorSmoothness
)

internal fun RenderQualityPreferencesSnapshot.colorProfileMap(): Map<String, Any> = linkedMapOf(
    "vibrance" to colorTuning.vibrance,
    "saturation" to colorTuning.saturation,
    "contrast" to colorTuning.contrast
)

/** Deterministic payload used by CaptureRecipeFactory for profile-version identity. */
internal fun RenderQualityPreferencesSnapshot.profileVersionMap(): Map<String, Any?> = linkedMapOf(
    "profileId" to profileId,
    "frameSourceFormat" to frameSourceFormat,
    "captureMode" to captureMode.name,
    "jpegQuality" to jpegQuality,
    "demosaicRequested" to demosaic.requestedMode.displayName,
    "demosaicResolved" to demosaic.resolvedAlgorithm.name,
    "demosaicFallback" to demosaic.fallbackOccurred,
    "curves" to linkedMapOf(
        "tonePreset" to curves.tonePreset,
        "toneNodes" to curves.toneNodes,
        "gammaPreset" to curves.gammaPreset,
        "gammaNodes" to curves.gammaNodes,
        "sectionPreset" to curves.sectionPreset,
        "sectionNodes" to curves.sectionNodes
    ),
    "awb" to linkedMapOf(
        "mode" to profileAwb.mode,
        "brand" to profileAwb.brand,
        "preset" to profileAwb.preset,
        "kelvin" to profileAwb.kelvin,
        "illuminantModel" to profileAwb.illuminantModel,
        "tint" to profileAwb.tint
    ),
    "spectraProfile" to spectraProfileMap(),
    "noiseReductionProfile" to noiseReductionProfileMap(),
    "colorProfile" to colorProfileMap(),
    "resolvedIspSettings" to resolvedIspSettings.activeSettings
        .sortedBy { it.key }
        .map { setting ->
            linkedMapOf(
                "key" to setting.key,
                "runtimeScope" to setting.runtimeScope.name,
                "active" to setting.active,
                "visible" to setting.visible,
                "source" to setting.source,
                "value" to setting.value.asDebugString(),
                "rawValue" to setting.value.toString(),
                "actualTechnicalValue" to setting.actualTechnicalValue,
                "mappedRuntimeValue" to setting.mappedRuntimeValue,
                "appliedStage" to setting.appliedStage
            )
        }
)

private fun stablePrivateValueHash(value: String): String {
    if (value.isBlank()) return "none"
    return java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(8)
        .joinToString("") { "%02x".format(it) }
}
