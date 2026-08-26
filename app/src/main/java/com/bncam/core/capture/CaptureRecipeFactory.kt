package com.bncam.core.capture

import com.bncam.core.engine.CaptureStrategy
import com.bncam.core.quality.RenderQualityConfig
import com.bncam.core.tracing.StableJson
import com.bncam.data.settings.CaptureSettingKeys
import com.bncam.data.settings.SettingsRepository
import java.security.MessageDigest
import kotlinx.coroutines.flow.first

data class CaptureRecipeRequest(
    val applicationVersion: String,
    val profileId: String,
    val profileDefaultName: String,
    val captureStrategy: CaptureStrategy,
    val frameSourceFormat: Int,
    val frameOrigin: FrameOrigin,
    val outputPolicy: OutputPolicy,
    val logicalCameraId: String,
    val physicalCameraId: String?,
    val lensId: String,
    val pipelineGenerationId: Int,
    val runtimeSafeWarmBufferCapacity: Int,
    val thermalState: String,
    val captureTimestampEpochMs: Long,
    val capabilityResolutions: List<CapabilityResolutionRecord>,
    val computationalHdrRequested: Boolean = false,
    val computationalHdrRouteEnabled: Boolean = false,
    val computationalHdrResolutionReason: String = "not_requested",
    val requestedFrameCountOverride: Int? = null
)

object CaptureRecipeFactory {
    suspend fun create(
        repository: SettingsRepository,
        request: CaptureRecipeRequest
    ): CaptureRecipe {
        val captureMode = CaptureMode.from(request.captureStrategy)
        val hdrEnhancedFrameSetting = if (request.captureStrategy == CaptureStrategy.HDR_ENHANCED) {
            repository.hdrEnhancedFrameSettingFlow.first()
        } else {
            "Auto"
        }
        val requestedFrameCount = when {
            request.captureStrategy == CaptureStrategy.HDR_ENHANCED ->
                hdrEnhancedFrameSetting.toIntOrNull()?.takeIf { it > 0 } ?: 15
            request.computationalHdrRouteEnabled -> 3
            captureMode == CaptureMode.SINGLE -> repository.getProfileInt(
                request.profileId,
                CaptureSettingKeys.BASE_CANDIDATES,
                3
            ).first()
            request.requestedFrameCountOverride != null ->
                request.requestedFrameCountOverride.coerceAtLeast(1)
            else -> requestedMultiFrameCount(repository, request.profileId, request.frameOrigin)
        }
        val requestedDngMasterFrameCount =
            requestedDngMasterFrameCount(
                repository = repository,
                origin = request.frameOrigin,
                captureMode = captureMode,
                outputPolicy = request.outputPolicy,
                computationalHdrRouteEnabled = request.computationalHdrRouteEnabled
            )
        val warmResolution = FrameCapacityPolicy.resolveWarmBuffer(
            origin = request.frameOrigin,
            runtimeSafeMaximum = request.runtimeSafeWarmBufferCapacity,
            captureMode = captureMode
        )
        val candidateResolution = FrameCapacityPolicy.resolveSelectableCandidates(
            origin = request.frameOrigin,
            captureMode = captureMode,
            requestedValue = requestedFrameCount,
            runtimeSafeMaximum = warmResolution.effectiveValue
        )
        val processingResolution = FrameCapacityPolicy.resolveProcessingFrames(
            origin = request.frameOrigin,
            captureMode = captureMode,
            requestedValue = requestedFrameCount,
            runtimeSafeMaximum = warmResolution.effectiveValue
        )
        val dngResolution = FrameCapacityPolicy.resolveDngMasterFrames(
            origin = request.frameOrigin,
            captureMode = captureMode,
            requestedValue = requestedDngMasterFrameCount,
            runtimeSafeMaximum = warmResolution.effectiveValue,
            outputPolicy = request.outputPolicy
        )
        val dngSource = DngSourceResolver.planned(
            outputPolicy = request.outputPolicy,
            captureMode = captureMode,
            effectiveDngMasterFrameCount = dngResolution.effectiveValue
        )

        val profileName =
            repository.getProfileNameFlow(request.profileId, request.profileDefaultName).first()
        val phoneSensors = repository.phoneAssistanceSensorsFlow.first()
        val requestedAlignment =
            repository.getProfileMultiFrameAlignmentMethodFlow(request.profileId).first()
        val requestedFusion =
            repository.getProfileMultiFrameFusionMethodFlow(request.profileId).first()
        val alignment = if (captureMode == CaptureMode.MULTI) {
            MultiFrameAlignmentRegistry.resolve(requestedAlignment, request.frameOrigin)
        } else {
            notApplicableMethod("not_applicable_single_frame")
        }
        val fusion = if (request.captureStrategy == CaptureStrategy.HDR_ENHANCED) {
            MethodResolution(
                requestedId = MultiFrameFusionRegistry.HDR_ENHANCED_TEMPORAL.id,
                requestedAvailability = MultiFrameFusionRegistry.HDR_ENHANCED_TEMPORAL.availability,
                supported = request.frameOrigin != FrameOrigin.YUV,
                resolvedId = MultiFrameFusionRegistry.HDR_ENHANCED_TEMPORAL.id,
                fallback = false,
                reason = "hdr_enhanced_deliberate_raw_temporal_stack"
            )
        } else if (request.computationalHdrRouteEnabled) {
            MethodResolution(
                requestedId = MultiFrameFusionRegistry.BRACKETED_HDR.id,
                requestedAvailability = MultiFrameFusionRegistry.BRACKETED_HDR.availability,
                supported = true,
                resolvedId = MultiFrameFusionRegistry.BRACKETED_HDR.id,
                fallback = false,
                reason = "legacy_computational_hdr_route"
            )
        } else if (captureMode == CaptureMode.MULTI) {
            MultiFrameFusionRegistry.resolve(requestedFusion, request.frameOrigin)
        } else {
            MethodResolution(
                requestedId = "anchor_only",
                requestedAvailability = MethodAvailability.IMPLEMENTED,
                supported = true,
                resolvedId = MultiFrameFusionRegistry.ANCHOR_ONLY.id,
                fallback = false,
                reason = "single_frame_has_no_multi_frame_fusion"
            )
        }
        val lensHardware = repository.readLensHardwareSettingsSnapshot(request.lensId)
        val renderPreferences = RenderQualityConfig.snapshotPreferences(
            repo = repository,
            profileId = request.profileId,
            frameSourceFormat = request.frameSourceFormat,
            captureMode = request.captureStrategy
        )
        val demosaicSelection = renderPreferences.demosaic
        val demosaic = MethodResolution(
            requestedId = demosaicSelection.requestedMode.displayName,
            requestedAvailability = MethodAvailability.IMPLEMENTED,
            supported = true,
            resolvedId = demosaicSelection.resolvedDebugName,
            fallback = demosaicSelection.fallbackOccurred,
            reason = if (demosaicSelection.fallbackOccurred) {
                demosaicSelection.fallbackReason
            } else {
                demosaicSelection.resolveReason
            }
        )
        val exposureStrategy =
            repository.getProfileMultiFrameExposureStrategyFlow(request.profileId).first()
        val captureExposurePreferences = CaptureExposurePreferences.fromPersisted(
            priorityMode = repository.getProfileString(
                request.profileId, CaptureSettingKeys.EXPOSURE_PRIORITY_MODE, "Balanced"
            ).first(),
            shutterMultiplier = repository.getProfileFloat(
                request.profileId, CaptureSettingKeys.SHUTTER_PRIORITY_MULTIPLIER, 1.0f
            ).first(),
            isoMultiplier = repository.getProfileFloat(
                request.profileId, CaptureSettingKeys.ISO_PRIORITY_MULTIPLIER, 1.0f
            ).first(),
            captureEvBias = repository.getProfileFloat(
                request.profileId, CaptureSettingKeys.CAPTURE_EV_BIAS, 0.0f
            ).first(),
            shotBiasExposure = repository.getProfileString(
                request.profileId, CaptureSettingKeys.SHOT_BIAS_EXPOSURE, "Auto"
            ).first(),
            maxFrameExposure = repository.getProfileString(
                request.profileId, CaptureSettingKeys.SHOT_BIAS_MAX_FRAME_EXPOSURE, "Max exposure time"
            ).first()
        )

        val executionSettings = CaptureExecutionSettings(
            profileName = profileName,
            cameraSoundEnabled = repository.cameraSoundsFlow.first(),
            flashMode = repository.flashModeFlow.first(),
            opticalStabilization = repository.opticalStabilizationFlow.first(),
            hotPixelMode = repository.hotPixelModeFlow.first(),
            noiseReductionHint = repository.noiseReductionHintFlow.first(),
            edgeModeHint = repository.edgeModeHintFlow.first(),
            tonemapHint = repository.tonemapHintFlow.first(),
            antiBanding = repository.antiBandingFlow.first(),
            debug = CaptureDebugSettings(
                shotLoggingEnabled = repository.enableShotLoggerFlow.first(),
                saveLocationData = repository.saveLocationDataFlow.first(),
                logSummary = repository.logSummaryFlow.first(),
                logActiveMode = repository.logActiveModeFlow.first(),
                logProfileSettings = repository.logProfileSettingsFlow.first(),
                logFrameAnalysis = repository.logFrameAnalysisFlow.first(),
                logWarnings = repository.logWarningsFlow.first(),
                logPipelineDebug = repository.logPipelineDebugFlow.first(),
                logVendorInjection = repository.logVendorInjectionFlow.first()
            ),
            output = CaptureOutputSettings(
                photoPrefix = repository.photoPrefixFlow.first(),
                saveLocation = repository.saveLocationFlow.first(),
                mirrorFrontPreview = repository.mirrorFrontPreviewFlow.first(),
                watermarkEnabled = repository.watermarkEnabledFlow.first(),
                watermarkStyle = repository.watermarkStyleFlow.first(),
                watermarkSignature = repository.watermarkSignatureFlow.first(),
                watermarkAuthor = repository.watermarkAddAuthorTopRightFlow.first(),
                exifSaveSignature = repository.exifSaveSignatureFlow.first(),
                exifExtraData = repository.exifExtraDataFlow.first()
            ),
            selection = CaptureSelectionSettings(
                basePosition = repository.getProfileString(
                    request.profileId,
                    CaptureSettingKeys.BASE_POSITION,
                    "Auto"
                ).first(),
                baseCandidateCount = requestedFrameCount,
                baseIncludeInMerge = repository.getProfileBoolean(
                    request.profileId,
                    CaptureSettingKeys.BASE_INCLUDE_IN_MERGE,
                    true
                ).first(),
                baseBias = repository.getProfileString(
                    request.profileId,
                    CaptureSettingKeys.BASE_BIAS,
                    if (captureMode == CaptureMode.SINGLE) "Overall Best Score" else "Auto"
                ).first(),
                baseTemporalBias = repository.getProfileFloat(
                    request.profileId,
                    CaptureSettingKeys.BASE_TEMPORAL_BIAS,
                    0f
                ).first(),
                selectionRequestedFrames = repository.getProfileInt(
                    request.profileId,
                    CaptureSettingKeys.SELECTION_REQUESTED_FRAMES,
                    5
                ).first(),
                frameBias = repository.getProfileString(
                    request.profileId,
                    CaptureSettingKeys.SELECTION_FRAME_BIAS,
                    "Auto"
                ).first(),
                acceptAll = repository.getProfileBoolean(
                    request.profileId,
                    CaptureSettingKeys.SELECTION_ACCEPT_ALL,
                    false
                ).first(),
                rejectDuplicates = repository.getProfileBoolean(
                    request.profileId,
                    CaptureSettingKeys.SELECTION_REJECT_DUPES,
                    true
                ).first(),
                useAlignableOnly = repository.getProfileBoolean(
                    request.profileId,
                    CaptureSettingKeys.SELECTION_ALIGNABLE_ONLY,
                    true
                ).first(),
                discardFirst = repository.getProfileBoolean(
                    request.profileId,
                    CaptureSettingKeys.SELECTION_DISCARD_FIRST,
                    true
                ).first(),
                preferRecent = repository.getProfileBoolean(
                    request.profileId,
                    CaptureSettingKeys.SELECTION_PREFER_RECENT,
                    false
                ).first(),
                ignoreStale = repository.getProfileBoolean(
                    request.profileId,
                    CaptureSettingKeys.SELECTION_IGNORE_STALE,
                    true
                ).first()
            ),
            merge = CaptureMergeSettings(
                subPixel = repository.getProfileBoolean(
                    request.profileId,
                    CaptureSettingKeys.MERGE_SUBPIXEL,
                    true
                ).first(),
                linearInterpolation = repository.getProfileBoolean(
                    request.profileId,
                    CaptureSettingKeys.MERGE_LINEAR_INTERPOLATION,
                    true
                ).first(),
                strictness = repository.getProfileFloat(
                    request.profileId,
                    CaptureSettingKeys.MERGE_STRICTNESS,
                    0.8f
                ).first(),
                maximumShiftPixels = repository.getProfileInt(
                    request.profileId,
                    CaptureSettingKeys.MERGE_MAX_SHIFT,
                    150
                ).first()
            ),
            lensHardwareSettings = lensHardware,
            renderPreferences = renderPreferences,
            captureExposurePreferences = captureExposurePreferences
        )

        val frameSelection = FrameSelectionRegistry.resolvedFor(captureMode)
        val anchorSelection = AnchorSelectionRegistry.resolvedFor(captureMode)
        val profileHash = sha256(
            StableJson.encode(
                linkedMapOf(
                    "profileId" to request.profileId,
                    "profileName" to profileName,
                    "frameOrigin" to request.frameOrigin.name,
                    "captureMode" to captureMode.name,
                    "outputPolicy" to request.outputPolicy.name,
                    "requestedFrameCount" to requestedFrameCount,
                    "requestedAlignment" to requestedAlignment,
                    "requestedFusion" to requestedFusion,
                    "exposureStrategy" to exposureStrategy,
                    "captureExposurePreferences" to captureExposurePreferences.debugMap(),
                    "computationalHdrRequested" to request.computationalHdrRequested,
                    "computationalHdrRouteEnabled" to request.computationalHdrRouteEnabled,
                    "computationalHdrResolutionReason" to request.computationalHdrResolutionReason,
                    "renderPreferences" to renderPreferences.profileVersionMap(),
                    "lensHardwareFingerprint" to lensHardware.fingerprint()
                )
            )
        )

        return CaptureRecipe.create(
            CaptureRecipeInput(
                applicationVersion = request.applicationVersion,
                activeProfileIdentifier = request.profileId,
                profileVersionHash = profileHash,
                logicalCameraId = request.logicalCameraId,
                physicalCameraId = request.physicalCameraId,
                lensIdentifier = request.lensId,
                frameSource = request.frameOrigin,
                captureMode = captureMode,
                outputPolicy = request.outputPolicy,
                pipelineGenerationId = request.pipelineGenerationId,
                requestedFrameCount = requestedFrameCount,
                warmBufferResolution = warmResolution,
                candidateCountResolution = candidateResolution,
                processingFrameResolution = processingResolution,
                dngMasterFrameResolution = dngResolution,
                dngSource = dngSource,
                frameSelectionMethod = frameSelection.asResolution(),
                anchorSelectionMethod = anchorSelection.asResolution(),
                alignmentMethod = alignment,
                fusionMethod = fusion,
                demosaicMethod = demosaic,
                exposureStrategy = exposureStrategy,
                computationalHdrRequested = request.computationalHdrRequested,
                computationalHdrRouteEnabled = request.computationalHdrRouteEnabled,
                computationalHdrResolutionReason = request.computationalHdrResolutionReason,
                hdrEnhancedFrameSetting = hdrEnhancedFrameSetting,
                phoneAssistanceSensorsEnabled = phoneSensors,
                computeBackendId =
                    com.bncam.core.compute.ComputeBackendRegistry
                        .activeCaptureBackend()
                        .id,
                hardwareOverrideFingerprint = lensHardware.fingerprint(),
                thermalState = request.thermalState,
                captureTimestampEpochMs = request.captureTimestampEpochMs,
                capabilityResolutions = request.capabilityResolutions.toList(),
                executionSettings = executionSettings
            )
        )
    }

    private suspend fun requestedMultiFrameCount(
        repository: SettingsRepository,
        profileId: String,
        origin: FrameOrigin
    ): Int {
        val (currentKey, legacyJpegKey, defaultValue) = when (origin) {
            FrameOrigin.YUV -> listOf(
                CaptureSettingKeys.FUSION_FRAMES_YUV,
                CaptureSettingKeys.LEGACY_JPEG_FRAMES_YUV,
                "8"
            )
            FrameOrigin.RAW10 -> listOf(
                CaptureSettingKeys.FUSION_FRAMES_RAW10,
                CaptureSettingKeys.LEGACY_JPEG_FRAMES_RAW10,
                "8"
            )
            FrameOrigin.RAW_SENSOR -> listOf(
                CaptureSettingKeys.FUSION_FRAMES_RAW_SENSOR,
                CaptureSettingKeys.LEGACY_JPEG_FRAMES_RAW_SENSOR,
                "5"
            )
        }
        val current = repository.getProfileInt(profileId, currentKey, -1).first()
        if (current > 0) return current
        val legacyJpeg = repository.getProfileInt(profileId, legacyJpegKey, -1).first()
        if (legacyJpeg > 0) return legacyJpeg
        return defaultValue.toInt()
    }

    private suspend fun requestedDngMasterFrameCount(
        repository: SettingsRepository,
        origin: FrameOrigin,
        captureMode: CaptureMode,
        outputPolicy: OutputPolicy,
        computationalHdrRouteEnabled: Boolean = false
    ): Int {
        if (!outputPolicy.producesRaw || origin == FrameOrigin.YUV) return 0
        if (computationalHdrRouteEnabled || captureMode == CaptureMode.SINGLE) return 1
        val outputDng = repository.getOutputModeSettingsFlow().first().getConfigForOutputPolicy(outputPolicy)
        if (outputDng.dngSourcePolicy == com.bncam.data.settings.DngSourcePolicy.ANCHOR_RAW) return 1
        return outputDng.dngMasterFrameCount.coerceIn(1, FrameCapacityPolicy.maximumDngMasterFrames(origin))
    }

    private fun MultiFrameAlgorithmDescriptor.asResolution() = MethodResolution(
        requestedId = id,
        requestedAvailability = availability,
        supported = true,
        resolvedId = id,
        fallback = false,
        reason = "current_capture_execution"
    )

    private fun notApplicableMethod(reason: String) = MethodResolution(
        requestedId = "not_applicable",
        requestedAvailability = null,
        supported = true,
        resolvedId = "not_applicable",
        fallback = false,
        reason = reason
    )

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
