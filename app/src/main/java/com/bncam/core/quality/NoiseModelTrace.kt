package com.bncam.core.quality

import android.hardware.camera2.CaptureResult
import com.bncam.core.capture.CaptureRecipe
import com.bncam.core.capture.noiseReductionProfileMap
import com.bncam.core.capture.spectraProfileMap
import com.bncam.core.debug.CapturePerformanceTraceSnapshot
import com.bncam.core.tracing.StableJson

/** Input frame recorded in the capture-local SPECTRA trace. */
data class NoiseModelTraceFrame(
    val captureId: String,
    val frameTimestampNs: Long,
    val lensId: String,
    val calibration: FinalSensorCalibration,
    val captureResult: CaptureResult?
)


data class NoiseModelPublicationState(
    val jpegPublished: Boolean,
    val dngPublished: Boolean,
    val thumbnailPublished: Boolean,
    val publicationResult: String,
    val jpegFailureReason: String? = null,
    val dngFailureReason: String? = null
)

/**
 * Capture-local observability trace.
 *
 * Physical noise authority lives in PhysicalNoiseState; Neural Denoise is the only noise-related
 * RAW pixel mutation owner. This trace may report observer/residual evidence but must never
 * select, fit or mutate physical S/O.
 */
object NoiseModelTrace {
    const val CURRENT_SCHEMA_VERSION = 22

    fun build(
        frames: List<NoiseModelTraceFrame>,
        jniCalibration: FinalSensorCalibration?,
        nativeStats: String,
        fusionStats: String = "",
        dynamicIsoCoefficient: Float,
        captureAttemptId: String? = null,
        recipe: CaptureRecipe? = null,
        runnerPerformance: CapturePerformanceTraceSnapshot? = null,
        publicationState: NoiseModelPublicationState? = null
    ): String {
        val stats = linkedMapOf<String, String>().apply {
            putAll(parseNativeStats(nativeStats))
            putAll(parseNativeStats(fusionStats))
        }
        val anchor = frames.firstOrNull()
        val renderPreferences = recipe?.executionSettings?.renderPreferences
        val profileNoiseTuning = renderPreferences?.noiseTuning ?: ProfileNoiseTuning()
        val residualNoiseState = ResidualNoiseState.fromNativeStats(stats)
        val residualTrace = residualNoiseState.toTraceMap()
        val multiscaleChroma = SpectraChromaBandsTrace.fromNativeStats(stats)
        val visibleChroma = SpectraVisibleChromaTrace.fromNativeStats(stats)
        val anisotropicDetail = SpectraAnisotropicDetailTrace.fromNativeStats(stats)
        val temporalFusion = SpectraTemporalFusionTrace.fromNativeStats(stats)
        val downstreamIsp = SpectraDownstreamIspTrace.fromNativeStats(stats)
        val vulkanVisibleCandidate =
            SpectraVulkanVisibleCandidateTrace.fromNativeStats(stats)
        val vulkanResidentVisibleChroma =
            SpectraVulkanResidentTrace.fromNativeStats(stats)
        val vulkanResidentPreDemosaic =
            SpectraVulkanPreDemosaicTrace.fromNativeStats(stats)
        val vulkanResidentChroma =
            SpectraVulkanResidentChromaTrace.fromNativeStats(stats)
        val vulkanTemporalObserver =
            SpectraVulkanTemporalObserverTrace.fromNativeStats(stats)

        return StableJson.encode(
            linkedMapOf(
                "schemaVersion" to CURRENT_SCHEMA_VERSION,
                "formula" to "variance = S * x + O",
                "canonicalSoOrder" to "R,G1,G2,B",
                "captureIdentity" to captureIdentity(captureAttemptId, recipe, frames),
                "profileSettings" to profileSettings(recipe, dynamicIsoCoefficient),
                "frames" to frames.map(::frameMap),
                "sensorState" to sensorState(anchor, jniCalibration, stats),
                "passes" to (0..3).associate { index -> "pass$index" to passMap(index, stats) },
                "noRegret" to (0..3).associate { index -> "pass$index" to noRegretMap(index, stats) },
                "provenance" to linkedMapOf(
                    "capture" to provenanceMap("spectraCaptureProvenance", stats),
                    "finalPreDemosaic" to provenanceMap("spectraFinalProvenance", stats)
                ),
                "residualNoiseState" to residualTrace,
                "multiscaleChroma" to multiscaleChroma.toTraceMap(),
                "visibleChroma" to visibleChroma.toTraceMap(),
                "vulkanVisibleChromaCandidate" to vulkanVisibleCandidate.toTraceMap(),
                "vulkanResidentVisibleChroma" to vulkanResidentVisibleChroma.toTraceMap(),
                "vulkanResidentPreDemosaic" to vulkanResidentPreDemosaic.toTraceMap(),
                "vulkanResidentChroma" to vulkanResidentChroma.toTraceMap(),
                "vulkanTemporalObserver" to vulkanTemporalObserver.toTraceMap(),
                "anisotropicDetail" to anisotropicDetail.toTraceMap(),
                "temporalFusion" to temporalFusion.toTraceMap(),
                "downstreamIsp" to downstreamIsp.toTraceMap(),
                "performanceBackend" to performanceBackendMap(stats),
                "noisePropagation" to linkedMapOf(
                    "stages" to residualTrace["stages"],
                    "transitions" to residualTrace["transitions"],
                    "curveDerivatives" to residualTrace["curveDerivatives"],
                    "calibration" to residualTrace["calibration"],
                    "visiblePrediction" to residualTrace["visiblePrediction"],
                    "visibleMeasurement" to residualTrace["visibleMeasurement"],
                    "pass2VisibleTarget" to residualTrace["pass2VisibleTarget"],
                    "comparabilityStatus" to residualNoiseState.comparabilityStatus
                ),
                "multiFrame" to multiFrameMap(recipe, stats),
                "timing" to linkedMapOf(
                    "native" to timingMap(stats),
                    "runner" to runnerPerformance?.toTraceMap()
                ),
                "publication" to linkedMapOf(
                    "requestedOutputPolicy" to recipe?.outputPolicy?.name,
                    "traceWriteStage" to if (publicationState == null) {
                        "PRE_FINAL_PUBLICATION_RESOLUTION"
                    } else {
                        "FINAL_PUBLICATION_RESOLVED"
                    },
                    "jpegPublished" to publicationState?.jpegPublished,
                    "dngPublished" to publicationState?.dngPublished,
                    "thumbnailPublished" to publicationState?.thumbnailPublished,
                    "capturePublicationResult" to publicationState?.publicationResult,
                    "jpegFailureReason" to publicationState?.jpegFailureReason,
                    "dngFailureReason" to publicationState?.dngFailureReason,
                    "fallbackPublicationSource" to if (publicationState == null) {
                        "capture status.json / architecture trace"
                    } else {
                        null
                    }
                )
            )
        )
    }

    private fun captureIdentity(
        captureAttemptId: String?,
        recipe: CaptureRecipe?,
        frames: List<NoiseModelTraceFrame>
    ): Map<String, Any?> = linkedMapOf(
        "captureAttemptId" to (captureAttemptId ?: frames.firstOrNull()?.captureId),
        "resolvedLensKey" to frames.firstOrNull()?.calibration?.noiseSnapshot?.lensKey,
        "profileId" to recipe?.activeProfileIdentifier,
        "profileVersionHash" to recipe?.profileVersionHash,
        "sourceFormat" to recipe?.frameSource?.name,
        "captureMode" to recipe?.captureMode?.name,
        "requestedFrameCount" to recipe?.requestedFrameCount,
        "usedFrameCount" to frames.size,
        "pipelineGenerationId" to recipe?.pipelineGenerationId
    )

    private fun profileSettings(
        recipe: CaptureRecipe?,
        dynamicIsoCoefficient: Float
    ): Map<String, Any?> {
        val render = recipe?.executionSettings?.renderPreferences
        val noise = render?.noiseTuning ?: ProfileNoiseTuning()
        val nr = render?.noiseReductionTuning ?: ProfileNoiseReductionTuning()
        return linkedMapOf(
            "spectraMode" to (if (noise.spectraEnabled) "Auto" else "Off"),
            "dynamicIso" to dynamicIsoCoefficient.coerceIn(-1f, 1f),
            "neuralMasterAuthority" to noise.neuralDenoiseStrength,
            "profileSpectraLuma" to noise.spectraLuma,
            "profileSpectraChroma" to noise.spectraChroma,
            "profileSpectraDetailProtection" to noise.spectraDetailProtection,
            "profileSpectraLowFrequency" to noise.spectraLowFrequency,
            "detailNrLuminance" to nr.luminance,
            "detailNrLuminanceDetail" to nr.luminanceDetail,
            "detailNrLuminanceContrast" to nr.luminanceContrast,
            "detailNrColor" to nr.color,
            "detailNrColorDetail" to nr.colorDetail,
            "detailNrColorSmoothness" to nr.colorSmoothness,
            "allSpectraProfileFields" to render?.spectraProfileMap(),
            "allNoiseReductionProfileFields" to render?.noiseReductionProfileMap(),
            "demosaicRequested" to render?.demosaic?.requestedMode?.displayName,
            "demosaicResolved" to render?.demosaic?.resolvedAlgorithm?.name
        )
    }

    private fun frameMap(frame: NoiseModelTraceFrame): Map<String, Any?> {
        val calibration = frame.calibration
        val metadata = frame.captureResult
        return linkedMapOf(
            "captureId" to frame.captureId,
            "frameTimestampNs" to frame.frameTimestampNs,
            "lensId" to frame.lensId,
            "sourceFormat" to calibration.base.frameSource,
            "mode" to calibration.noiseModelMode,
            "cfaArrangement" to calibration.base.cfaName,
            "iso" to (metadata?.get(CaptureResult.SENSOR_SENSITIVITY) ?: calibration.base.sensorSensitivityIso),
            "exposureTimeNs" to (metadata?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: calibration.base.sensorExposureTimeNs),
            "postRawSensitivityBoost" to calibration.base.postRawSensitivityBoost,
            "blackLevelsUsed" to calibration.effectiveBlackLevels.toList(),
            "blackLevelOrder" to "camera2_2x2_mosaic_order",
            "whiteLevelUsed" to calibration.effectiveWhiteLevel,
            "camera2SoValues" to calibration.base.baseNoiseProfile?.toList().orEmpty(),
            "manualSoValues" to calibration.override.manualNoiseValues?.toList().orEmpty(),
            "resolvedSource" to calibration.effectiveNoiseProfileSource,
            "fallbackReason" to calibration.effectiveNoiseProfileFallbackReason.ifBlank { "None" },
            "effectiveSoValues" to calibration.effectiveNoiseProfile?.toList().orEmpty(),
            "modelConfidence" to calibration.noiseSnapshot?.signalModelConfidence
        )
    }

    private fun sensorState(
        anchor: NoiseModelTraceFrame?,
        jniCalibration: FinalSensorCalibration?,
        stats: Map<String, String>
    ): Map<String, Any?> {
        val calibration = anchor?.calibration ?: jniCalibration
        val snapshot = calibration?.noiseSnapshot
        return linkedMapOf(
            "captureIso" to (snapshot?.iso ?: stats.number("actualIso")),
            "postRawSensitivityBoost" to snapshot?.postRawSensitivityBoost,
            "effectiveIso" to (
                stats.number("spectraResidualEffectiveIso") ?: stats.number("effectiveIso")
            ),
            "exposureTimeNs" to snapshot?.exposureTimeNs,
            "cfaPattern" to snapshot?.cfaPattern,
            "cfaName" to snapshot?.cfaName,
            "whiteLevel" to snapshot?.whiteLevel,
            "blackLevels" to snapshot?.blackLevel?.toList(),
            "cameraS" to snapshot?.cameraS?.toList(),
            "cameraO" to snapshot?.cameraO?.toList(),
            "effectiveS" to snapshot?.effectiveS?.toList(),
            "effectiveO" to snapshot?.effectiveO?.toList(),
            "lensShadingApplied" to stats.boolean("lensShadingApplied"),
            "lensShadingGainP10" to stats.number("spectraCaptureProvenanceShadingGainP10"),
            "lensShadingGainP50" to stats.number("spectraCaptureProvenanceShadingGainP50"),
            "lensShadingGainP90" to stats.number("spectraCaptureProvenanceShadingGainP90")
        )
    }

    private fun passMap(index: Int, stats: Map<String, String>): Map<String, Any?> {
        val prefix = "spectraPass$index"
        return linkedMapOf(
            "applied" to stats.boolean("${prefix}Applied"),
            "skipReason" to stats["${prefix}SkipReason"],
            "inputEnergy" to stats.number("${prefix}InputEnergy"),
            "targetFloor" to stats.number("${prefix}TargetFloor"),
            "outputEnergy" to stats.number("${prefix}OutputEnergy"),
            "reductionPercentage" to stats.number("${prefix}ReductionPercentage"),
            "maximumCorrection" to stats.number("${prefix}MaximumCorrection"),
            "processingTimeMs" to stats.number("${prefix}ProcessingTimeMs")
        )
    }

    private fun noRegretMap(index: Int, stats: Map<String, String>): Map<String, Any?> {
        val prefix = "spectraNoRegretP$index"
        return linkedMapOf(
            "totalTiles" to stats.number("${prefix}TotalTiles"),
            "evaluatedTiles" to stats.number("${prefix}EvaluatedTiles"),
            "invalidTiles" to stats.number("${prefix}InvalidTiles"),
            "fullyAcceptedTiles" to stats.number("${prefix}FullyAcceptedTiles"),
            "partiallyAcceptedTiles" to stats.number("${prefix}PartiallyAcceptedTiles"),
            "rejectedTiles" to stats.number("${prefix}RejectedTiles"),
            "rejectionReasons" to linkedMapOf(
                "oversmoothing" to stats.number("${prefix}RejectedOversmooth"),
                "detailLoss" to stats.number("${prefix}RejectedDetailLoss"),
                "meanDrift" to stats.number("${prefix}RejectedMeanDrift"),
                "noImprovement" to stats.number("${prefix}RejectedNoImprovement")
            ),
            "meanAcceptance" to stats.number("${prefix}MeanAcceptance"),
            "acceptanceP10" to stats.number("${prefix}AcceptanceP10"),
            "acceptanceP50" to stats.number("${prefix}AcceptanceP50"),
            "acceptanceP90" to stats.number("${prefix}AcceptanceP90"),
            "attenuatedPixelFraction" to stats.number("${prefix}AttenuatedPixelFraction"),
            "meanColourShift" to stats.number("${prefix}MeanColourShift"),
            "maxColourShift" to stats.number("${prefix}MaxColourShift"),
            "edgePreservationScore" to stats.number("${prefix}EdgePreservationScore"),
            "oversmoothingScore" to stats.number("${prefix}OversmoothingScore")
        )
    }

    private fun provenanceMap(prefix: String, stats: Map<String, String>): Map<String, Any?> = linkedMapOf(
        "tileCount" to stats.number("${prefix}TileCount"),
        "validTileCount" to stats.number("${prefix}ValidTileCount"),
        "meanModelConfidence" to stats.number("${prefix}MeanDiagnosticConfidence"),
        "meanResidualEnergy" to stats.number("${prefix}MeanResidualEnergy"),
        "meanChromaResidualEnergy" to stats.number("${prefix}MeanChromaResidualEnergy"),
        "modelConfidenceP10" to stats.number("${prefix}ConfidenceP10"),
        "modelConfidenceP50" to stats.number("${prefix}ConfidenceP50"),
        "modelConfidenceP90" to stats.number("${prefix}ConfidenceP90"),
        "lensShadingGainP10" to stats.number("${prefix}ShadingGainP10"),
        "lensShadingGainP50" to stats.number("${prefix}ShadingGainP50"),
        "lensShadingGainP90" to stats.number("${prefix}ShadingGainP90"),
        "noiseBudgetP10" to stats.number("${prefix}NoiseBudgetP10"),
        "noiseBudgetP50" to stats.number("${prefix}NoiseBudgetP50"),
        "noiseBudgetP90" to stats.number("${prefix}NoiseBudgetP90"),
        "modelMismatchP10" to stats.number("${prefix}ModelMismatchP10"),
        "modelMismatchP50" to stats.number("${prefix}ModelMismatchP50"),
        "modelMismatchP90" to stats.number("${prefix}ModelMismatchP90")
    )

    private fun multiFrameMap(
        recipe: CaptureRecipe?,
        stats: Map<String, String>
    ): Map<String, Any?> {
        val fusionVarianceScale = stats.number("spectraFusionVarianceScale")?.toDouble()
        val predictedVariance = (0..3).map { index ->
            stats.number("spectraPredictedVar$index")?.toDouble()
        }
        val fusionVarianceCanonical = if (
            fusionVarianceScale != null && predictedVariance.all { it != null }
        ) {
            predictedVariance.map { variance -> variance!! * fusionVarianceScale }
        } else {
            emptyList()
        }
        return linkedMapOf(
            "requestedFrameCount" to recipe?.requestedFrameCount,
            "configuredEffectiveFrameCount" to recipe?.effectiveFrameCount,
            "acceptedFramePairs" to stats.number("spectraAcceptedFramePairs"),
            "rejectedFramePairs" to stats.number("spectraRejectedFramePairs"),
            "alignmentConfidence" to stats.number("spectraAlignmentConfidence"),
            "motionConfidence" to stats.number("spectraMotionConfidence"),
            "observerConfidence" to stats.number("spectraObserverConfidence"),
            "observerSamples" to stats.number("spectraObserverSamples"),
            "temporalCorrelation" to stats.number("spectraTemporalCorrelation"),
            "independentNoiseFraction" to stats.number("spectraIndependentNoiseFraction"),
            "effectiveFrameCount" to stats.number("spectraEffectiveFrameCount"),
            "fusionVarianceScale" to fusionVarianceScale,
            "fusionVarianceDomain" to "PRE_DEMOSAIC_SENSOR_MODEL_R_G1_G2_B",
            "fusionVarianceCanonical" to fusionVarianceCanonical,
            "fusionVariance" to fusionVarianceCanonical.takeIf { it.isNotEmpty() }?.average(),
            "fusionVarianceP10" to stats.number("spectraFusionVarianceP10"),
            "fusionVarianceP50" to stats.number("spectraFusionVarianceP50"),
            "fusionVarianceP90" to stats.number("spectraFusionVarianceP90"),
            "effectiveFrameCountP10" to stats.number("spectraEffectiveFrameCountP10"),
            "effectiveFrameCountP50" to stats.number("spectraEffectiveFrameCountP50"),
            "effectiveFrameCountP90" to stats.number("spectraEffectiveFrameCountP90"),
            "persistentPatternFraction" to stats.number("spectraPersistentPatternFraction"),
            "forwardBackwardConsistency" to stats.number("spectraForwardBackwardConsistency"),
            "repeatedSupportConfidence" to stats.number("spectraRepeatedSupportConfidence"),
            "fitStabilityConfidence" to stats.number("spectraFitStabilityConfidence"),
            "fitStabilityMin" to stats.number("spectraFitStabilityMin"),
            "staticProbabilityP50" to stats.number("spectraStaticProbabilityP50"),
            "localFusionFallbackFraction" to stats.number("spectraLocalFusionFallbackFraction"),
            "observerOnly" to stats.boolean("spectraObserverOnly")
        )
    }

    private fun performanceBackendMap(stats: Map<String, String>): Map<String, Any?> = linkedMapOf(
        "milestone" to stats["spectraPerformanceMilestone"],
        "productionBackend" to stats["spectraProductionBackend"],
        "gpuPrimaryStages" to linkedMapOf(
            "pass1" to stats.boolean("spectraPass1GpuPrimary"),
            "pass2" to stats.boolean("spectraPass2GpuPrimary"),
            "pass3" to stats.boolean("spectraPass3GpuPrimary"),
            "postDemosaic" to stats.boolean("spectraPostDemosaicGpuPrimary")
        ),
        "cpuReferenceRole" to stats["spectraCpuReferenceRole"],
        "legacyQualificationBackend" to stats["spectraLegacyQualificationBackend"],
        "selectedBackend" to stats["spectraPerformanceBackend"],
        "selectionReason" to stats["spectraPerformanceSelectionReason"],
        "neonCompiled" to stats.boolean("spectraPerformanceNeonCompiled"),
        "simdKernelActive" to stats.boolean("spectraPerformanceSimdKernelActive"),
        "simdValidation" to linkedMapOf(
            "performed" to stats.boolean("spectraPerformanceSimdValidationPerformed"),
            "passed" to stats.boolean("spectraPerformanceSimdValidationPassed"),
            "status" to stats["spectraPerformanceSimdValidationStatus"],
            "maximumAbsoluteDelta" to
                stats.number("spectraPerformanceSimdValidationMaximumAbsoluteDelta"),
            "maximumRelativeDelta" to
                stats.number("spectraPerformanceSimdValidationMaximumRelativeDelta"),
            "elapsedMs" to stats.number("spectraPerformanceSimdValidationElapsedMs"),
            "fallbackReason" to stats["spectraPerformanceSimdFallbackReason"]
        ),
        "simdDispatchCount" to stats.number("spectraPerformanceSimdDispatchCount"),
        "simdVectorizedLaneCount" to
            stats.number("spectraPerformanceSimdVectorizedLaneCount"),
        "simdRejectedNonFiniteLaneCount" to
            stats.number("spectraPerformanceSimdRejectedNonFiniteLaneCount"),
        "vulkanPrepared" to stats.boolean("spectraPerformanceVulkanPrepared"),
        "vulkanSelected" to stats.boolean("spectraPerformanceVulkanSelected"),
        "vulkanStatus" to stats["spectraPerformanceVulkanStatus"],
        "deviceProfile" to linkedMapOf(
            "status" to stats["spectraPerformanceDeviceProfileStatus"],
            "recommendation" to stats["spectraPerformanceDeviceProfileRecommendation"],
            "width" to stats.number("spectraPerformanceDeviceProfileWidth"),
            "height" to stats.number("spectraPerformanceDeviceProfileHeight"),
            "spectraEnabled" to
                stats.boolean("spectraPerformanceDeviceProfileSpectraEnabled"),
            "routeKey" to stats["spectraPerformanceDeviceProfileRouteKey"],
            "sampleCount" to stats.number("spectraPerformanceDeviceProfileSampleCount"),
            "qualifiedSampleCount" to
                stats.number("spectraPerformanceDeviceProfileQualifiedSampleCount"),
            "warmupDiscardedCount" to
                stats.number("spectraPerformanceDeviceProfileWarmupDiscardedCount"),
            "ready" to stats.boolean("spectraPerformanceDeviceProfileReady"),
            "medianTotalIspMs" to
                stats.number("spectraPerformanceDeviceProfileMedianTotalIspMs"),
            "p95TotalIspMs" to
                stats.number("spectraPerformanceDeviceProfileP95TotalIspMs"),
            "medianStatisticsMs" to
                stats.number("spectraPerformanceDeviceProfileMedianStatisticsMs"),
            "medianSpectraPassesMs" to
                stats.number("spectraPerformanceDeviceProfileMedianSpectraPassesMs"),
            "medianVisibleChromaMs" to
                stats.number("spectraPerformanceDeviceProfileMedianVisibleChromaMs"),
            "medianDownstreamIspMs" to
                stats.number("spectraPerformanceDeviceProfileMedianDownstreamIspMs"),
            "firstWindowMedianMs" to
                stats.number("spectraPerformanceDeviceProfileFirstWindowMedianMs"),
            "recentWindowMedianMs" to
                stats.number("spectraPerformanceDeviceProfileRecentWindowMedianMs"),
            "thermalDriftRatio" to
                stats.number("spectraPerformanceDeviceProfileThermalDriftRatio"),
            "coefficientOfVariation" to
                stats.number("spectraPerformanceDeviceProfileCoefficientOfVariation"),
            "confidence" to stats.number("spectraPerformanceDeviceProfileConfidence"),
            "thermalStable" to
                stats.boolean("spectraPerformanceDeviceProfileThermalStable"),
            "throttlingDetected" to
                stats.boolean("spectraPerformanceDeviceProfileThrottlingDetected")
        ),
        "vulkanQualification" to linkedMapOf(
            "runtimeReady" to stats.boolean("spectraPerformanceVulkanRuntimeReady"),
            "timestampQueriesSupported" to
                stats.boolean("spectraPerformanceVulkanTimestampQueriesSupported"),
            "vmaReady" to stats.boolean("spectraPerformanceVulkanVmaReady"),
            "productionKernelConnected" to
                stats.boolean("spectraPerformanceVulkanProductionKernelConnected"),
            "benchmarkPerformed" to
                stats.boolean("spectraPerformanceVulkanBenchmarkPerformed"),
            "benchmarkSampleCount" to
                stats.number("spectraPerformanceVulkanBenchmarkSampleCount"),
            "numericalEquivalencePassed" to
                stats.boolean("spectraPerformanceVulkanNumericalEquivalencePassed"),
            "maximumAbsoluteDelta" to
                stats.number("spectraPerformanceVulkanMaximumAbsoluteDelta"),
            "runtimeGatePassed" to
                stats.boolean("spectraPerformanceVulkanRuntimeGatePassed"),
            "kernelGatePassed" to
                stats.boolean("spectraPerformanceVulkanKernelGatePassed"),
            "benchmarkGatePassed" to
                stats.boolean("spectraPerformanceVulkanBenchmarkGatePassed"),
            "numericalGatePassed" to
                stats.boolean("spectraPerformanceVulkanNumericalGatePassed"),
            "latencyGatePassed" to
                stats.boolean("spectraPerformanceVulkanLatencyGatePassed"),
            "transferGatePassed" to
                stats.boolean("spectraPerformanceVulkanTransferGatePassed"),
            "thermalGatePassed" to
                stats.boolean("spectraPerformanceVulkanThermalGatePassed"),
            "memoryGatePassed" to
                stats.boolean("spectraPerformanceVulkanMemoryGatePassed"),
            "measuredSpeedup" to
                stats.number("spectraPerformanceVulkanMeasuredSpeedup"),
            "transferFraction" to
                stats.number("spectraPerformanceVulkanTransferFraction"),
            "minimumRequiredSpeedup" to
                stats.number("spectraPerformanceVulkanMinimumRequiredSpeedup"),
            "maximumTransferFraction" to
                stats.number("spectraPerformanceVulkanMaximumTransferFraction"),
            "maximumAllowedAbsoluteDelta" to
                stats.number("spectraPerformanceVulkanMaximumAllowedAbsoluteDelta"),
            "blockedReason" to stats["spectraPerformanceVulkanBlockedReason"],
            "opponentFeatureKernel" to linkedMapOf(
                "routeKey" to stats["spectraVisibleChromaVulkanOpponentRouteKey"],
                "kernelConnected" to
                    stats.boolean("spectraVisibleChromaVulkanOpponentKernelConnected"),
                "attempted" to stats.boolean("spectraVisibleChromaVulkanOpponentAttempted"),
                "executionSucceeded" to
                    stats.boolean("spectraVisibleChromaVulkanOpponentExecutionSucceeded"),
                "usedForOutput" to
                    stats.boolean("spectraVisibleChromaVulkanOpponentUsedForOutput"),
                "timestampQueryUsed" to
                    stats.boolean("spectraVisibleChromaVulkanOpponentTimestampQueryUsed"),
                "benchmarkPerformed" to
                    stats.boolean("spectraVisibleChromaVulkanOpponentBenchmarkPerformed"),
                "benchmarkSampleCount" to
                    stats.number("spectraVisibleChromaVulkanOpponentBenchmarkSampleCount"),
                "failedSampleCount" to
                    stats.number("spectraVisibleChromaVulkanOpponentFailedSampleCount"),
                "numericalEquivalencePassed" to
                    stats.boolean("spectraVisibleChromaVulkanOpponentNumericalEquivalencePassed"),
                "deviceQualificationSelected" to
                    stats.boolean("spectraVisibleChromaVulkanOpponentDeviceQualificationSelected"),
                "benchmarkAborted" to
                    stats.boolean("spectraVisibleChromaVulkanOpponentBenchmarkAborted"),
                "memoryGatePassed" to
                    stats.boolean("spectraVisibleChromaVulkanOpponentMemoryGatePassed"),
                "currentMaximumAbsoluteDelta" to
                    stats.number("spectraVisibleChromaVulkanOpponentCurrentMaximumAbsoluteDelta"),
                "maximumAbsoluteDelta" to
                    stats.number("spectraVisibleChromaVulkanOpponentMaximumAbsoluteDelta"),
                "currentCpuReferenceMs" to
                    stats.number("spectraVisibleChromaVulkanOpponentCurrentCpuReferenceMs"),
                "currentCpuVectorizedPixelCount" to
                    stats.number("spectraVisibleChromaVulkanOpponentCurrentCpuVectorizedPixelCount"),
                "currentCpuScalarPixelCount" to
                    stats.number("spectraVisibleChromaVulkanOpponentCurrentCpuScalarPixelCount"),
                "cpuReferenceBackend" to
                    stats["spectraVisibleChromaVulkanOpponentCpuReferenceBackend"],
                "cpuReferenceStatus" to
                    stats["spectraVisibleChromaVulkanOpponentCpuReferenceStatus"],
                "currentGpuKernelMs" to
                    stats.number("spectraVisibleChromaVulkanOpponentCurrentGpuKernelMs"),
                "currentGpuTotalMs" to
                    stats.number("spectraVisibleChromaVulkanOpponentCurrentGpuTotalMs"),
                "currentTransferAndSyncMs" to
                    stats.number("spectraVisibleChromaVulkanOpponentCurrentTransferAndSyncMs"),
                "cpuMedianMs" to
                    stats.number("spectraVisibleChromaVulkanOpponentCpuMedianMs"),
                "gpuKernelMedianMs" to
                    stats.number("spectraVisibleChromaVulkanOpponentGpuKernelMedianMs"),
                "gpuTotalMedianMs" to
                    stats.number("spectraVisibleChromaVulkanOpponentGpuTotalMedianMs"),
                "transferAndSyncMedianMs" to
                    stats.number("spectraVisibleChromaVulkanOpponentTransferAndSyncMedianMs"),
                "measuredSpeedup" to
                    stats.number("spectraVisibleChromaVulkanOpponentMeasuredSpeedup"),
                "transferFraction" to
                    stats.number("spectraVisibleChromaVulkanOpponentTransferFraction"),
                "qualificationOverheadMs" to
                    stats.number("spectraVisibleChromaVulkanOpponentQualificationOverheadMs"),
                "inputBytes" to stats.number("spectraVisibleChromaVulkanOpponentInputBytes"),
                "outputBytes" to stats.number("spectraVisibleChromaVulkanOpponentOutputBytes"),
                "allocatedBytes" to
                    stats.number("spectraVisibleChromaVulkanOpponentAllocatedBytes"),
                "estimatedTransientBytes" to
                    stats.number("spectraVisibleChromaVulkanOpponentEstimatedTransientBytes"),
                "maximumTransientBytes" to
                    stats.number("spectraVisibleChromaVulkanOpponentMaximumTransientBytes"),
                "stripedExecution" to linkedMapOf(
                    "enabled" to
                        stats.boolean("spectraVisibleChromaVulkanOpponentStripedMode"),
                    "planValid" to
                        stats.boolean("spectraVisibleChromaVulkanOpponentStripPlanValid"),
                    "planStatus" to
                        stats["spectraVisibleChromaVulkanOpponentStripPlanStatus"],
                    "stripCount" to
                        stats.number("spectraVisibleChromaVulkanOpponentStripCount"),
                    "successfulStripCount" to
                        stats.number("spectraVisibleChromaVulkanOpponentSuccessfulStripCount"),
                    "targetOutputRows" to
                        stats.number("spectraVisibleChromaVulkanOpponentTargetOutputRows"),
                    "haloRows" to
                        stats.number("spectraVisibleChromaVulkanOpponentHaloRows"),
                    "maximumInputRows" to
                        stats.number("spectraVisibleChromaVulkanOpponentMaximumInputRows"),
                    "assemblyMs" to
                        stats.number("spectraVisibleChromaVulkanOpponentAssemblyMs"),
                    "fullFrameOutputBytes" to
                        stats.number("spectraVisibleChromaVulkanOpponentFullFrameOutputBytes"),
                    "maximumStripInputBytes" to
                        stats.number("spectraVisibleChromaVulkanOpponentMaximumStripInputBytes"),
                    "maximumStripOutputBytes" to
                        stats.number("spectraVisibleChromaVulkanOpponentMaximumStripOutputBytes")
                ),
                "persistentBuffers" to linkedMapOf(
                    "reuseObserved" to stats.boolean(
                        "spectraVisibleChromaVulkanOpponentPersistentBufferReuseObserved"
                    ),
                    "reallocated" to stats.boolean(
                        "spectraVisibleChromaVulkanOpponentPersistentBufferReallocated"
                    ),
                    "reuseHitCount" to stats.number(
                        "spectraVisibleChromaVulkanOpponentPersistentBufferReuseHitCount"
                    ),
                    "reallocationCount" to stats.number(
                        "spectraVisibleChromaVulkanOpponentPersistentBufferReallocationCount"
                    ),
                    "capacityBytes" to stats.number(
                        "spectraVisibleChromaVulkanOpponentPersistentBufferCapacityBytes"
                    ),
                    "residentBytes" to stats.number(
                        "spectraVisibleChromaVulkanOpponentPersistentResidentBytes"
                    ),
                    "allocationGeneration" to stats.number(
                        "spectraVisibleChromaVulkanOpponentPersistentAllocationGeneration"
                    )
                ),
                "executionStatus" to
                    stats["spectraVisibleChromaVulkanOpponentExecutionStatus"],
                "qualificationStatus" to
                    stats["spectraVisibleChromaVulkanOpponentQualificationStatus"],
                "failureReason" to
                    stats["spectraVisibleChromaVulkanOpponentFailureReason"]
            )
        ),
        "deviceQualificationMs" to
            stats.number("spectraPerformanceDeviceQualificationMs"),
        "deviceQualificationTimingAccounting" to
            stats["spectraPerformanceDeviceQualificationTimingAccounting"],
        "mixedPrecisionStatus" to stats["spectraPerformanceMixedPrecisionStatus"],
        "correctnessContract" to stats["spectraPerformanceCorrectnessContract"],
        "fusedStatisticsDispatchCount" to
            stats.number("spectraPerformanceFusedStatisticsDispatchCount"),
        "statisticsTotalMs" to stats.number("spectraPerformanceStatisticsTotalMs"),
        "stages" to linkedMapOf(
            "initial" to linkedMapOf(
                "elapsedMs" to stats.number("spectraPerformanceInitialStatisticsMs"),
                "method" to stats["spectraPerformanceInitialStatisticsMethod"],
                "simdKernelUsed" to stats.boolean("spectraPerformanceInitialSimdKernelUsed")
            ),
            "postPass1" to linkedMapOf(
                "elapsedMs" to stats.number("spectraPerformancePostPass1StatisticsMs"),
                "method" to stats["spectraPerformancePostPass1StatisticsMethod"],
                "simdKernelUsed" to stats.boolean("spectraPerformancePostPass1SimdKernelUsed")
            ),
            "postPass2" to linkedMapOf(
                "elapsedMs" to stats.number("spectraPerformancePostPass2StatisticsMs"),
                "method" to stats["spectraPerformancePostPass2StatisticsMethod"],
                "simdKernelUsed" to stats.boolean("spectraPerformancePostPass2SimdKernelUsed")
            )
        ),
        "tileCount" to stats.number("spectraPerformanceInitialTileCount"),
        "workerCount" to stats.number("spectraPerformanceInitialWorkerCount"),
        "estimatedBytesRead" to stats.number("spectraPerformanceStatisticsBytesRead"),
        "estimatedBytesWritten" to stats.number("spectraPerformanceStatisticsBytesWritten"),
        "scratchBytes" to stats.number("spectraPerformanceStatisticsScratchBytes"),
        "frameBytes" to stats.number("spectraPerformanceFrameBytes"),
        "estimatedReadPasses" to stats.number("spectraPerformanceEstimatedReadPasses"),
        "knownFullFrameCloneCount" to
            stats.number("spectraPerformanceKnownFullFrameCloneCount"),
        "pixelKernels" to linkedMapOf(
            "visibleChromaBackend" to stats["spectraVisibleChromaPixelBackend"],
            "visibleChromaSelectionReason" to
                stats["spectraVisibleChromaPixelBackendSelectionReason"],
            "visibleChromaFallbackReason" to
                stats["spectraVisibleChromaPixelBackendFallbackReason"],
            "visibleChromaNeonCompiled" to
                stats.boolean("spectraVisibleChromaPixelNeonCompiled"),
            "visibleChromaSelfTestPassed" to
                stats.boolean("spectraVisibleChromaPixelSimdSelfTestPassed"),
            "visibleChromaLatencyBenchmarkPerformed" to
                stats.boolean("spectraVisibleChromaPixelLatencyBenchmarkPerformed"),
            "visibleChromaLatencyBenchmarkPassed" to
                stats.boolean("spectraVisibleChromaPixelLatencyBenchmarkPassed"),
            "visibleChromaScalarBenchmarkMs" to
                stats.number("spectraVisibleChromaPixelScalarBenchmarkMs"),
            "visibleChromaNeonBenchmarkMs" to
                stats.number("spectraVisibleChromaPixelNeonBenchmarkMs"),
            "visibleChromaBenchmarkSpeedup" to
                stats.number("spectraVisibleChromaPixelBenchmarkSpeedup"),
            "visibleChromaVectorizedPixels" to
                stats.number("spectraVisibleChromaOpponentVectorizedPixelCount"),
            "visibleChromaScalarPixels" to
                stats.number("spectraVisibleChromaOpponentScalarPixelCount"),
            "visibleChromaTileCount" to
                stats.number("spectraVisibleChromaOpponentTileCount"),
            "visibleChromaTileBuildMsAggregate" to
                stats.number("spectraVisibleChromaOpponentTileBuildMs"),
            "visibleChromaPeakScratchBytes" to
                stats.number("spectraVisibleChromaOpponentPeakScratchBytes"),
            "mutexFreeDeterministicReduction" to
                stats.boolean("spectraVisibleChromaMutexFreeTileReduction"),
            "pass2WorkingCloneCount" to
                stats.number("spectraPass2PixelWorkingCloneCount"),
            "pass2AvoidedFullFrameCloneCount" to
                stats.number("spectraPass2AvoidedFullFrameCloneCount"),
            "pass2BufferStrategy" to stats["spectraPass2PixelBufferStrategy"]
        )
    )

    private fun timingMap(stats: Map<String, String>): Map<String, Any?> = linkedMapOf(
        "mergeAnchorUnpackMs" to stats.number("anchorUnpackMs"),
        "mergeSupportUnpackMs" to stats.number("supportUnpackMs"),
        "mergeAlignmentMs" to stats.number("alignmentMs"),
        "mergeAccumulatorMs" to stats.number("mergeAccumulatorMs"),
        "mergeNormalizeMs" to stats.number("mergeNormalizeMs"),
        "spectraForwardBackwardAlignmentMs" to stats.number("spectraForwardBackwardAlignmentMs"),
        "spectraTemporalObserverMs" to stats.number("spectraTemporalObserverMs"),
        "spectraTemporalVulkanTotalMs" to stats.number("spectraTemporalVulkanTotalMs"),
        "spectraTemporalVulkanObserverKernelMs" to stats.number("spectraTemporalVulkanObserverKernelMs"),
        "spectraTemporalVulkanStaticFieldKernelMs" to stats.number("spectraTemporalVulkanStaticFieldKernelMs"),
        "spectraTemporalVulkanTimingAccounting" to "NESTED_IN_SPECTRA_TEMPORAL_OBSERVER_MS",
        "spectraStaticConsensusMs" to stats.number("spectraStaticConsensusMs"),
        "spectraFitConsensusMs" to stats.number("spectraFitConsensusMs"),
        "spectraCorrelationAnalysisMs" to stats.number("spectraCorrelationAnalysisMs"),
        "spectraTemporalFusionTimingAccounting" to "NESTED_IN_TOTAL_NATIVE_DNG_MERGE_MS",
        "mergeOutputArrayMs" to stats.number("outputArrayMs"),
        "totalNativeMergeMs" to stats.number("totalNativeDngMergeMs"),
        "raw10UnpackMs" to stats.number("raw10UnpackMs"),
        "raw10ToMasterRaw16Ms" to stats.number("raw10ToMasterRaw16Ms"),
        "rawSensorReadMs" to stats.number("rawSensorReadMs"),
        "rawSensorToMasterRaw16Ms" to stats.number("rawSensorToMasterRaw16Ms"),
        "spectraCaptureProvenanceMs" to stats.number("spectraCaptureProvenanceMs"),
        "pass0Ms" to stats.number("spectraPass0ProcessingTimeMs"),
        "pass1Ms" to stats.number("spectraPass1ProcessingTimeMs"),
        "anisotropicTensorFieldBuildMs" to
            stats.number("spectraAnisotropicDetailTensorFieldBuildMs"),
        "anisotropicDirectionalFilterMs" to
            stats.number("spectraAnisotropicDetailDirectionalFilterMs"),
        "anisotropicDetailTimingAccounting" to
            "NESTED_IN_SPECTRA_PASS1_PROCESSING_MS",
        "pass2Ms" to stats.number("spectraPass2ProcessingTimeMs"),
        "pass3Ms" to stats.number("spectraPass3ProcessingTimeMs"),
        "chromaFineProcessingMs" to stats.number("spectraChromaFineProcessingTimeMs"),
        "chromaFineMeasurementMs" to stats.number("spectraChromaFineMeasurementTimeMs"),
        "chromaMidProcessingMs" to stats.number("spectraChromaMidProcessingTimeMs"),
        "chromaMidMeasurementMs" to stats.number("spectraChromaMidMeasurementTimeMs"),
        "chromaLowProcessingMs" to stats.number("spectraChromaLowProcessingTimeMs"),
        "chromaLowMeasurementMs" to stats.number("spectraChromaLowMeasurementTimeMs"),
        "chromaBandTimingAccounting" to "NESTED_IN_PASS2_PASS3_TOTALS",
        "spectraFinalProvenanceMs" to stats.number("spectraFinalProvenanceMs"),
        "spectraTotalProcessingMs" to stats.number("spectraTotalProcessingMs"),
        "spectraAccountedProcessingMs" to stats.number("spectraAccountedProcessingMs"),
        "spectraUnattributedProcessingMs" to stats.number("spectraUnattributedProcessingMs"),
        "spectraPerformanceStatisticsTotalMs" to
            stats.number("spectraPerformanceStatisticsTotalMs"),
        "spectraPerformanceInitialStatisticsMs" to
            stats.number("spectraPerformanceInitialStatisticsMs"),
        "spectraPerformancePostPass1StatisticsMs" to
            stats.number("spectraPerformancePostPass1StatisticsMs"),
        "spectraPerformancePostPass2StatisticsMs" to
            stats.number("spectraPerformancePostPass2StatisticsMs"),
        "spectraPerformanceTimingAccounting" to
            "NESTED_IN_SPECTRA_TOTAL_PROCESSING_MS",
        "defectCorrectionMs" to stats.number("defectCorrectionMs"),
        "greenSplitMs" to stats.number("greenSplitMs"),
        "lensShadingMs" to stats.number("lensShadingMs"),
        "rawIspAccountedMs" to stats.number("rawIspAccountedMs"),
        "rawIspUnattributedMs" to stats.number("rawIspUnattributedMs"),
        "demosaicMs" to stats.number("demosaicMs"),
        "spectraDemosaicPropagationMs" to stats.number("spectraDemosaicPropagationMs"),
        "spectraAwbPropagationMs" to stats.number("spectraAwbPropagationMs"),
        "spectraColourTransformPropagationMs" to stats.number("spectraColourTransformPropagationMs"),
        "spectraTonePropagationMs" to stats.number("spectraTonePropagationMs"),
        "spectraTonePropagationTimingMode" to stats["spectraTonePropagationTimingMode"],
        "spectraMeasuredPostDemosaicResidualMs" to
            stats.number("spectraMeasuredPostDemosaicResidualMs"),
        "spectraMeasuredPostColourTransformResidualMs" to
            stats.number("spectraMeasuredPostColourTransformResidualMs"),
        "spectraMeasuredVisibleResidualMs" to stats.number("spectraMeasuredVisibleResidualMs"),
        "spectraDownstreamInputMeasurementMs" to
            stats.number("spectraDownstreamInputMeasurementMs"),
        "spectraDownstreamProcessingMs" to
            stats.number("spectraDownstreamProcessingTimeMs"),
        "spectraDownstreamOutputMeasurementMs" to
            stats.number("spectraDownstreamOutputMeasurementMs"),
        "spectraDownstreamPropagationMs" to
            stats.number("spectraDownstreamPropagationMs"),
        "spectraDownstreamTimingAccounting" to
            "SEQUENTIAL_AROUND_SHARPENING_FINAL_OUTPUT_MEASUREMENT_SEPARATE",
        "spectraPropagationMathMs" to stats.number("spectraPropagationMathMs"),
        "spectraPropagationSequentialMs" to stats.number("spectraPropagationSequentialMs"),
        "awbColourTransformFusedMs" to stats.number("awbColourTransformMs"),
        "awbTimingMode" to stats["awbTimingMode"],
        "colourTransformTimingMode" to stats["colourTransformTimingMode"],
        "highlightRecoveryMs" to stats.number("highlightRecoveryMs"),
        "toneAndVibranceMs" to stats.number("toneAndVibranceMs"),
        "finalOutputPassMs" to stats.number("finalOutputPassMs"),
        "finalOutSpatialNrMs" to stats.number("finalOutSpatialNrMs"),
        "spectraVisibleChromaProcessingMs" to
            stats.number("spectraVisibleChromaProcessingMs"),
        "spectraVisibleChromaInputMeasurementMs" to
            stats.number("spectraVisibleChromaInputMeasurementMs"),
        "spectraVisibleChromaOutputMeasurementMs" to
            stats.number("spectraVisibleChromaOutputMeasurementMs"),
        "spectraVisibleChromaTimingMode" to stats["spectraVisibleChromaTimingMode"],
        "finalOutClampQuantMs" to stats.number("finalOutClampQuantMs"),
        "sharpeningMs" to stats.number("sharpenMs"),
        "outputRotateMs" to stats.number("outputRotateMs"),
        "jpegEncodeMs" to stats.number("jpegEncodeMs"),
        "totalRawIspCoreMs" to stats.number("totalRawIspCoreMs")
    )


    internal fun parseNativeStats(raw: String): Map<String, String> = raw
        .split(';')
        .mapNotNull { field ->
            val separator = field.indexOf('=')
            if (separator <= 0 || separator == field.lastIndex) null
            else field.substring(0, separator).trim() to field.substring(separator + 1).trim()
        }
        .toMap()

    private fun Map<String, String>.number(key: String): Number? {
        val raw = this[key]?.trim() ?: return null
        raw.toLongOrNull()?.let { return it }
        return raw.toDoubleOrNull()?.takeIf(Double::isFinite)
    }

    private fun Map<String, String>.boolean(key: String): Boolean =
        this[key].equals("true", ignoreCase = true) ||
            this[key].equals("yes", ignoreCase = true)

    private fun Map<String, String>.floatOrNull(key: String): Float? =
        this[key]?.toFloatOrNull()?.takeIf(Float::isFinite)
}
