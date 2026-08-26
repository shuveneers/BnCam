package com.bncam.core.quality

import kotlin.math.max

/** Milestone-7 noise-aware sharpening and optional local-contrast observability. */
data class SpectraDownstreamIspTrace(
    val architecture: String = "UNAVAILABLE",
    val inputStage: String = "UNAVAILABLE",
    val outputStage: String = "UNAVAILABLE",
    val resultStatus: String = "NOT_RUN",
    val spectraAware: Boolean = false,
    val enabled: Boolean = false,
    val applied: Boolean = false,
    val method: String = "NONE",
    val planStatus: String = "UNAVAILABLE",
    val residualSource: String = "UNAVAILABLE",
    val modelConfidence: Double = 0.0,
    val predictedSigmaY: Double = 0.0,
    val measuredSigmaY: Double = 0.0,
    val sigmaY: Double = 0.0,
    val lumaAuthority: Double = 0.0,
    val detailProtection: Double = 0.0,
    val globalAuthority: Double = 0.0,
    val baseAmount: Double = 0.0,
    val maximumAmount: Double = 0.0,
    val minimumEdgeSnr: Double = 0.0,
    val fullEdgeSnr: Double = 0.0,
    val haloProtection: Double = 0.0,
    val maximumPredictedVarianceGain: Double = 1.0,
    val localContrastEnabled: Boolean = false,
    val localContrastAuthority: Double = 0.0,
    val localContrastApplied: Boolean = false,
    val processedPixelCount: Long = 0,
    val candidatePixelCount: Long = 0,
    val changedPixelCount: Long = 0,
    val changedPixelFraction: Double = 0.0,
    val noiseRejectedPixelCount: Long = 0,
    val haloProtectedPixelCount: Long = 0,
    val localContrastPixelCount: Long = 0,
    val meanLocalAuthority: Double = 0.0,
    val authorityP10: Double = 0.0,
    val authorityP50: Double = 0.0,
    val authorityP90: Double = 0.0,
    val meanEdgeSnr: Double = 0.0,
    val maximumAppliedAmount: Double = 0.0,
    val maximumLocalContrastAmount: Double = 0.0,
    val inputVarianceY: Double = 0.0,
    val outputVarianceY: Double = 0.0,
    val measuredVarianceGainY: Double = 1.0,
    val predictedVarianceGainY: Double = 1.0,
    val inputResidualSampleCount: Long = 0,
    val outputResidualSampleCount: Long = 0,
    val processingTimeMs: Double = 0.0,
    val inputMeasurementMs: Double = 0.0,
    val outputMeasurementMs: Double = 0.0,
    val propagationTimeMs: Double = 0.0
) {
    fun sanitized(): SpectraDownstreamIspTrace = copy(
        modelConfidence = unitM7(modelConfidence),
        predictedSigmaY = positiveM7(predictedSigmaY),
        measuredSigmaY = positiveM7(measuredSigmaY),
        sigmaY = positiveM7(sigmaY),
        lumaAuthority = unitM7(lumaAuthority),
        detailProtection = finiteM7(detailProtection).coerceIn(-1.0, 1.0),
        globalAuthority = unitM7(globalAuthority),
        baseAmount = positiveM7(baseAmount),
        maximumAmount = positiveM7(maximumAmount),
        minimumEdgeSnr = positiveM7(minimumEdgeSnr),
        fullEdgeSnr = positiveM7(fullEdgeSnr),
        haloProtection = unitM7(haloProtection),
        maximumPredictedVarianceGain = finiteM7(maximumPredictedVarianceGain).coerceIn(0.0, 2.0),
        localContrastAuthority = positiveM7(localContrastAuthority),
        processedPixelCount = processedPixelCount.coerceAtLeast(0),
        candidatePixelCount = candidatePixelCount.coerceAtLeast(0),
        changedPixelCount = changedPixelCount.coerceAtLeast(0),
        changedPixelFraction = unitM7(changedPixelFraction),
        noiseRejectedPixelCount = noiseRejectedPixelCount.coerceAtLeast(0),
        haloProtectedPixelCount = haloProtectedPixelCount.coerceAtLeast(0),
        localContrastPixelCount = localContrastPixelCount.coerceAtLeast(0),
        meanLocalAuthority = positiveM7(meanLocalAuthority),
        authorityP10 = positiveM7(authorityP10),
        authorityP50 = positiveM7(authorityP50),
        authorityP90 = positiveM7(authorityP90),
        meanEdgeSnr = positiveM7(meanEdgeSnr),
        maximumAppliedAmount = positiveM7(maximumAppliedAmount),
        maximumLocalContrastAmount = positiveM7(maximumLocalContrastAmount),
        inputVarianceY = positiveM7(inputVarianceY),
        outputVarianceY = positiveM7(outputVarianceY),
        measuredVarianceGainY = finiteM7(measuredVarianceGainY).coerceIn(0.0, 2.0),
        predictedVarianceGainY = finiteM7(predictedVarianceGainY).coerceIn(0.0, 2.0),
        inputResidualSampleCount = inputResidualSampleCount.coerceAtLeast(0),
        outputResidualSampleCount = outputResidualSampleCount.coerceAtLeast(0),
        processingTimeMs = positiveM7(processingTimeMs),
        inputMeasurementMs = positiveM7(inputMeasurementMs),
        outputMeasurementMs = positiveM7(outputMeasurementMs),
        propagationTimeMs = positiveM7(propagationTimeMs)
    )

    fun toTraceMap(): Map<String, Any> = sanitized().let { trace ->
        linkedMapOf(
            "architecture" to trace.architecture,
            "inputStage" to trace.inputStage,
            "outputStage" to trace.outputStage,
            "resultStatus" to trace.resultStatus,
            "spectraAware" to trace.spectraAware,
            "enabled" to trace.enabled,
            "applied" to trace.applied,
            "method" to trace.method,
            "planStatus" to trace.planStatus,
            "residualSource" to trace.residualSource,
            "model" to linkedMapOf(
                "confidence" to trace.modelConfidence,
                "predictedSigmaY" to trace.predictedSigmaY,
                "measuredSigmaY" to trace.measuredSigmaY,
                "resolvedSigmaY" to trace.sigmaY,
                "inputVarianceY" to trace.inputVarianceY,
                "outputVarianceY" to trace.outputVarianceY,
                "measuredVarianceGainY" to trace.measuredVarianceGainY,
                "predictedVarianceGainY" to trace.predictedVarianceGainY,
                "inputResidualSampleCount" to trace.inputResidualSampleCount,
                "outputResidualSampleCount" to trace.outputResidualSampleCount
            ),
            "authority" to linkedMapOf(
                "lumaAuthority" to trace.lumaAuthority,
                "detailProtection" to trace.detailProtection,
                "globalAuthority" to trace.globalAuthority,
                "baseAmount" to trace.baseAmount,
                "maximumAmount" to trace.maximumAmount,
                "meanLocalAuthority" to trace.meanLocalAuthority,
                "p10" to trace.authorityP10,
                "p50" to trace.authorityP50,
                "p90" to trace.authorityP90,
                "maximumAppliedAmount" to trace.maximumAppliedAmount
            ),
            "protection" to linkedMapOf(
                "minimumEdgeSnr" to trace.minimumEdgeSnr,
                "fullEdgeSnr" to trace.fullEdgeSnr,
                "meanEdgeSnr" to trace.meanEdgeSnr,
                "haloProtection" to trace.haloProtection,
                "maximumPredictedVarianceGain" to trace.maximumPredictedVarianceGain,
                "noiseRejectedPixels" to trace.noiseRejectedPixelCount,
                "haloProtectedPixels" to trace.haloProtectedPixelCount
            ),
            "localContrast" to linkedMapOf(
                "enabled" to trace.localContrastEnabled,
                "applied" to trace.localContrastApplied,
                "authority" to trace.localContrastAuthority,
                "maximumAppliedAmount" to trace.maximumLocalContrastAmount,
                "pixelCount" to trace.localContrastPixelCount
            ),
            "pixels" to linkedMapOf(
                "processed" to trace.processedPixelCount,
                "candidates" to trace.candidatePixelCount,
                "changed" to trace.changedPixelCount,
                "changedFraction" to trace.changedPixelFraction
            ),
            "timing" to linkedMapOf(
                "processingMs" to trace.processingTimeMs,
                "inputMeasurementMs" to trace.inputMeasurementMs,
                "outputMeasurementMs" to trace.outputMeasurementMs,
                "propagationMs" to trace.propagationTimeMs,
                "accounting" to "SEQUENTIAL_AROUND_SHARPENING_FINAL_OUTPUT_MEASUREMENT_SEPARATE"
            )
        )
    }

    companion object {
        fun fromNativeStats(stats: Map<String, String>): SpectraDownstreamIspTrace =
            SpectraDownstreamIspTrace(
                architecture = stats["spectraDownstreamArchitecture"] ?: "UNAVAILABLE",
                inputStage = stats["spectraDownstreamInputStage"] ?: "UNAVAILABLE",
                outputStage = stats["spectraDownstreamOutputStage"] ?: "UNAVAILABLE",
                resultStatus = stats["spectraDownstreamResultStatus"] ?: "NOT_RUN",
                spectraAware = stats.booleanM7("spectraDownstreamSpectraAware"),
                enabled = stats.booleanM7("spectraDownstreamEnabled"),
                applied = stats.booleanM7("spectraDownstreamApplied"),
                method = stats["spectraDownstreamMethod"] ?: "NONE",
                planStatus = stats["spectraDownstreamPlanStatus"] ?: "UNAVAILABLE",
                residualSource = stats["spectraDownstreamResidualSource"] ?: "UNAVAILABLE",
                modelConfidence = stats.numberM7("spectraDownstreamModelConfidence"),
                predictedSigmaY = stats.numberM7("spectraDownstreamPredictedSigmaY"),
                measuredSigmaY = stats.numberM7("spectraDownstreamMeasuredSigmaY"),
                sigmaY = stats.numberM7("spectraDownstreamSigmaY"),
                lumaAuthority = stats.numberM7("spectraDownstreamLumaAuthority"),
                detailProtection = stats.numberM7("spectraDownstreamDetailProtection"),
                globalAuthority = stats.numberM7("spectraDownstreamGlobalAuthority"),
                baseAmount = stats.numberM7("spectraDownstreamBaseAmount"),
                maximumAmount = stats.numberM7("spectraDownstreamMaximumAmount"),
                minimumEdgeSnr = stats.numberM7("spectraDownstreamMinimumEdgeSnr"),
                fullEdgeSnr = stats.numberM7("spectraDownstreamFullEdgeSnr"),
                haloProtection = stats.numberM7("spectraDownstreamHaloProtection"),
                maximumPredictedVarianceGain = stats.numberM7(
                    "spectraDownstreamMaximumPredictedVarianceGain",
                    1.0
                ),
                localContrastEnabled = stats.booleanM7("spectraDownstreamLocalContrastEnabled"),
                localContrastAuthority = stats.numberM7("spectraDownstreamLocalContrastAuthority"),
                localContrastApplied = stats.booleanM7("spectraDownstreamLocalContrastApplied"),
                processedPixelCount = stats.longM7("spectraDownstreamProcessedPixelCount"),
                candidatePixelCount = stats.longM7("spectraDownstreamCandidatePixelCount"),
                changedPixelCount = stats.longM7("spectraDownstreamChangedPixelCount"),
                changedPixelFraction = stats.numberM7("spectraDownstreamChangedPixelFraction"),
                noiseRejectedPixelCount = stats.longM7("spectraDownstreamNoiseRejectedPixelCount"),
                haloProtectedPixelCount = stats.longM7("spectraDownstreamHaloProtectedPixelCount"),
                localContrastPixelCount = stats.longM7("spectraDownstreamLocalContrastPixelCount"),
                meanLocalAuthority = stats.numberM7("spectraDownstreamMeanLocalAuthority"),
                authorityP10 = stats.numberM7("spectraDownstreamAuthorityP10"),
                authorityP50 = stats.numberM7("spectraDownstreamAuthorityP50"),
                authorityP90 = stats.numberM7("spectraDownstreamAuthorityP90"),
                meanEdgeSnr = stats.numberM7("spectraDownstreamMeanEdgeSnr"),
                maximumAppliedAmount = stats.numberM7("spectraDownstreamMaximumAppliedAmount"),
                maximumLocalContrastAmount = stats.numberM7(
                    "spectraDownstreamMaximumLocalContrastAmount"
                ),
                inputVarianceY = stats.numberM7("spectraDownstreamInputVarianceY"),
                outputVarianceY = stats.numberM7("spectraDownstreamOutputVarianceY"),
                measuredVarianceGainY = stats.numberM7("spectraDownstreamMeasuredVarianceGainY", 1.0),
                predictedVarianceGainY = stats.numberM7("spectraDownstreamPredictedVarianceGainY", 1.0),
                inputResidualSampleCount = stats.longM7("spectraDownstreamInputResidualSampleCount"),
                outputResidualSampleCount = stats.longM7("spectraDownstreamOutputResidualSampleCount"),
                processingTimeMs = stats.numberM7("spectraDownstreamProcessingTimeMs"),
                inputMeasurementMs = stats.numberM7("spectraDownstreamInputMeasurementMs"),
                outputMeasurementMs = stats.numberM7("spectraDownstreamOutputMeasurementMs"),
                propagationTimeMs = stats.numberM7("spectraDownstreamPropagationMs")
            ).sanitized()
    }
}

private fun Map<String, String>.numberM7(key: String, default: Double = 0.0): Double =
    this[key]?.toDoubleOrNull()?.takeIf(Double::isFinite) ?: default

private fun Map<String, String>.longM7(key: String): Long = this[key]?.toLongOrNull() ?: 0L

private fun Map<String, String>.booleanM7(key: String): Boolean =
    this[key].equals("true", ignoreCase = true) || this[key].equals("yes", ignoreCase = true)

private fun finiteM7(value: Double): Double = if (value.isFinite()) value else 0.0
private fun positiveM7(value: Double): Double = max(0.0, finiteM7(value))
private fun unitM7(value: Double): Double = finiteM7(value).coerceIn(0.0, 1.0)
