package com.bncam.core.quality

import kotlin.math.max

/** Milestone-4 post-tone visible-chroma correction trace. */
data class SpectraVisibleChromaTrace(
    val activationSource: String = "DISABLED",
    val architecture: String = "UNAVAILABLE",
    val inputStage: String = "UNAVAILABLE",
    val outputStage: String = "UNAVAILABLE",
    val covarianceSource: String = "UNAVAILABLE",
    val timingAccounting: String = "UNAVAILABLE",
    val enabled: Boolean = false,
    val applied: Boolean = false,
    val planStatus: String = "UNAVAILABLE",
    val resultStatus: String = "NOT_RUN",
    val method: String = "NONE",
    val noRegretMethod: String = "NONE",
    val modelConfidence: Double = 0.0,
    val authority: Double = 0.0,
    val maximumCorrection: Double = 0.0,
    val sigmaRG: Double = 0.0,
    val sigmaBG: Double = 0.0,
    val covarianceCorrelation: Double = 0.0,
    val predictedVarianceRG: Double = 0.0,
    val predictedVarianceBG: Double = 0.0,
    val predictedCovarianceRgBg: Double = 0.0,
    val processedPixelCount: Long = 0,
    val candidatePixelCount: Long = 0,
    val changedPixelCount: Long = 0,
    val changedPixelFraction: Double = 0.0,
    val lumaEdgeProtectedPixelCount: Long = 0,
    val colourEdgeProtectedPixelCount: Long = 0,
    val saturationProtectedPixelCount: Long = 0,
    val fullyAcceptedPixelCount: Long = 0,
    val partiallyAcceptedPixelCount: Long = 0,
    val rejectedPixelCount: Long = 0,
    val fullyAcceptedTileCount: Long = 0,
    val partiallyAcceptedTileCount: Long = 0,
    val rejectedTileCount: Long = 0,
    val evaluatedTileCount: Long = 0,
    val meanAcceptance: Double = 0.0,
    val acceptanceP10: Double = 0.0,
    val acceptanceP50: Double = 0.0,
    val acceptanceP90: Double = 0.0,
    val meanColourShift: Double = 0.0,
    val maximumColourShift: Double = 0.0,
    val meanNoiseImprovement: Double = 0.0,
    val edgePreservationScore: Double = 1.0,
    val oversmoothingScore: Double = 0.0,
    val inputVarianceRG: Double = 0.0,
    val inputVarianceBG: Double = 0.0,
    val inputCovarianceRgBg: Double = 0.0,
    val outputVarianceRG: Double = 0.0,
    val outputVarianceBG: Double = 0.0,
    val outputCovarianceRgBg: Double = 0.0,
    val inputResidualSampleCount: Long = 0,
    val outputResidualSampleCount: Long = 0,
    val processingTimeMs: Double = 0.0,
    val inputMeasurementMs: Double = 0.0,
    val outputMeasurementMs: Double = 0.0,
    val pixelBackend: String = "NOT_SELECTED",
    val pixelBackendSelectionReason: String = "UNAVAILABLE",
    val pixelBackendFallbackReason: String = "none",
    val pixelNeonCompiled: Boolean = false,
    val pixelSimdSelfTestPerformed: Boolean = false,
    val pixelSimdSelfTestPassed: Boolean = false,
    val pixelSimdSelfTestMaximumAbsoluteDelta: Double = 0.0,
    val pixelSimdSelfTestElapsedMs: Double = 0.0,
    val pixelLatencyBenchmarkPerformed: Boolean = false,
    val pixelLatencyBenchmarkPassed: Boolean = false,
    val pixelScalarBenchmarkMs: Double = 0.0,
    val pixelNeonBenchmarkMs: Double = 0.0,
    val pixelBenchmarkSpeedup: Double = 0.0,
    val opponentVectorizedPixelCount: Long = 0,
    val opponentScalarPixelCount: Long = 0,
    val opponentRejectedNonFinitePixelCount: Long = 0,
    val opponentEstimatedBytesRead: Long = 0,
    val opponentEstimatedBytesWritten: Long = 0,
    val opponentPeakScratchBytes: Long = 0,
    val opponentTileCount: Int = 0,
    val opponentTileSize: Int = 0,
    val opponentTileBuildMs: Double = 0.0,
    val mutexFreeTileReduction: Boolean = false,
    val cpuWeightKernel: String = "UNAVAILABLE",
    val cpuNeighbourSampleCount: Long = 0,
    val cpuGaussianWeightLookupCount: Long = 0,
    val cpuGaussianWeightLutIntervalCount: Int = 0,
    val cpuGaussianWeightMaximumSquaredDistance: Double = 0.0,
    val cpuGaussianWeightLutMaximumAbsoluteError: Double = 0.0,
    val cpuPerPixelScaleHoisted: Boolean = false,
    val cpuCenterSampleHoisted: Boolean = false
) {
    fun sanitized(): SpectraVisibleChromaTrace = copy(
        modelConfidence = unit(modelConfidence),
        authority = unit(authority),
        maximumCorrection = positive(maximumCorrection),
        sigmaRG = positive(sigmaRG),
        sigmaBG = positive(sigmaBG),
        covarianceCorrelation = finite(covarianceCorrelation).coerceIn(-1.0, 1.0),
        predictedVarianceRG = positive(predictedVarianceRG),
        predictedVarianceBG = positive(predictedVarianceBG),
        predictedCovarianceRgBg = finite(predictedCovarianceRgBg),
        processedPixelCount = processedPixelCount.coerceAtLeast(0),
        candidatePixelCount = candidatePixelCount.coerceAtLeast(0),
        changedPixelCount = changedPixelCount.coerceAtLeast(0),
        changedPixelFraction = unit(changedPixelFraction),
        lumaEdgeProtectedPixelCount = lumaEdgeProtectedPixelCount.coerceAtLeast(0),
        colourEdgeProtectedPixelCount = colourEdgeProtectedPixelCount.coerceAtLeast(0),
        saturationProtectedPixelCount = saturationProtectedPixelCount.coerceAtLeast(0),
        fullyAcceptedPixelCount = fullyAcceptedPixelCount.coerceAtLeast(0),
        partiallyAcceptedPixelCount = partiallyAcceptedPixelCount.coerceAtLeast(0),
        rejectedPixelCount = rejectedPixelCount.coerceAtLeast(0),
        fullyAcceptedTileCount = fullyAcceptedTileCount.coerceAtLeast(0),
        partiallyAcceptedTileCount = partiallyAcceptedTileCount.coerceAtLeast(0),
        rejectedTileCount = rejectedTileCount.coerceAtLeast(0),
        evaluatedTileCount = evaluatedTileCount.coerceAtLeast(0),
        meanAcceptance = unit(meanAcceptance),
        acceptanceP10 = unit(acceptanceP10),
        acceptanceP50 = unit(acceptanceP50),
        acceptanceP90 = unit(acceptanceP90),
        meanColourShift = positive(meanColourShift),
        maximumColourShift = positive(maximumColourShift),
        meanNoiseImprovement = finite(meanNoiseImprovement).coerceIn(-1.0, 1.0),
        edgePreservationScore = unit(edgePreservationScore),
        oversmoothingScore = unit(oversmoothingScore),
        inputVarianceRG = positive(inputVarianceRG),
        inputVarianceBG = positive(inputVarianceBG),
        inputCovarianceRgBg = finite(inputCovarianceRgBg),
        outputVarianceRG = positive(outputVarianceRG),
        outputVarianceBG = positive(outputVarianceBG),
        outputCovarianceRgBg = finite(outputCovarianceRgBg),
        inputResidualSampleCount = inputResidualSampleCount.coerceAtLeast(0),
        outputResidualSampleCount = outputResidualSampleCount.coerceAtLeast(0),
        processingTimeMs = positive(processingTimeMs),
        inputMeasurementMs = positive(inputMeasurementMs),
        outputMeasurementMs = positive(outputMeasurementMs),
        pixelSimdSelfTestMaximumAbsoluteDelta = positive(pixelSimdSelfTestMaximumAbsoluteDelta),
        pixelSimdSelfTestElapsedMs = positive(pixelSimdSelfTestElapsedMs),
        pixelScalarBenchmarkMs = positive(pixelScalarBenchmarkMs),
        pixelNeonBenchmarkMs = positive(pixelNeonBenchmarkMs),
        pixelBenchmarkSpeedup = positive(pixelBenchmarkSpeedup),
        opponentVectorizedPixelCount = opponentVectorizedPixelCount.coerceAtLeast(0),
        opponentScalarPixelCount = opponentScalarPixelCount.coerceAtLeast(0),
        opponentRejectedNonFinitePixelCount = opponentRejectedNonFinitePixelCount.coerceAtLeast(0),
        opponentEstimatedBytesRead = opponentEstimatedBytesRead.coerceAtLeast(0),
        opponentEstimatedBytesWritten = opponentEstimatedBytesWritten.coerceAtLeast(0),
        opponentPeakScratchBytes = opponentPeakScratchBytes.coerceAtLeast(0),
        opponentTileCount = opponentTileCount.coerceAtLeast(0),
        opponentTileSize = opponentTileSize.coerceAtLeast(0),
        opponentTileBuildMs = positive(opponentTileBuildMs),
        cpuNeighbourSampleCount = cpuNeighbourSampleCount.coerceAtLeast(0),
        cpuGaussianWeightLookupCount = cpuGaussianWeightLookupCount.coerceAtLeast(0),
        cpuGaussianWeightLutIntervalCount = cpuGaussianWeightLutIntervalCount.coerceAtLeast(0),
        cpuGaussianWeightMaximumSquaredDistance = positive(cpuGaussianWeightMaximumSquaredDistance),
        cpuGaussianWeightLutMaximumAbsoluteError = positive(cpuGaussianWeightLutMaximumAbsoluteError)
    )

    fun toTraceMap(): Map<String, Any> = sanitized().let { trace ->
        linkedMapOf(
            "activationSource" to trace.activationSource,
            "architecture" to trace.architecture,
            "inputStage" to trace.inputStage,
            "outputStage" to trace.outputStage,
            "covarianceSource" to trace.covarianceSource,
            "timingAccounting" to trace.timingAccounting,
            "enabled" to trace.enabled,
            "applied" to trace.applied,
            "planStatus" to trace.planStatus,
            "resultStatus" to trace.resultStatus,
            "method" to trace.method,
            "noRegretMethod" to trace.noRegretMethod,
            "modelConfidence" to trace.modelConfidence,
            "authority" to trace.authority,
            "maximumCorrection" to trace.maximumCorrection,
            "sigmaRG" to trace.sigmaRG,
            "sigmaBG" to trace.sigmaBG,
            "covarianceCorrelation" to trace.covarianceCorrelation,
            "prediction" to linkedMapOf(
                "varianceRG" to trace.predictedVarianceRG,
                "varianceBG" to trace.predictedVarianceBG,
                "covarianceRG_BG" to trace.predictedCovarianceRgBg
            ),
            "pixels" to linkedMapOf(
                "processed" to trace.processedPixelCount,
                "candidates" to trace.candidatePixelCount,
                "changed" to trace.changedPixelCount,
                "changedFraction" to trace.changedPixelFraction,
                "lumaEdgeProtected" to trace.lumaEdgeProtectedPixelCount,
                "colourEdgeProtected" to trace.colourEdgeProtectedPixelCount,
                "saturationProtected" to trace.saturationProtectedPixelCount,
                "fullyAccepted" to trace.fullyAcceptedPixelCount,
                "partiallyAccepted" to trace.partiallyAcceptedPixelCount,
                "rejected" to trace.rejectedPixelCount
            ),
            "noRegret" to linkedMapOf(
                "fullyAcceptedTiles" to trace.fullyAcceptedTileCount,
                "partiallyAcceptedTiles" to trace.partiallyAcceptedTileCount,
                "rejectedTiles" to trace.rejectedTileCount,
                "evaluatedTiles" to trace.evaluatedTileCount,
                "meanAcceptance" to trace.meanAcceptance,
                "acceptanceP10" to trace.acceptanceP10,
                "acceptanceP50" to trace.acceptanceP50,
                "acceptanceP90" to trace.acceptanceP90,
                "meanColourShift" to trace.meanColourShift,
                "maximumColourShift" to trace.maximumColourShift,
                "meanNoiseImprovement" to trace.meanNoiseImprovement,
                "edgePreservationScore" to trace.edgePreservationScore,
                "oversmoothingScore" to trace.oversmoothingScore
            ),
            "residualMeasurement" to linkedMapOf(
                "inputVarianceRG" to trace.inputVarianceRG,
                "inputVarianceBG" to trace.inputVarianceBG,
                "inputCovarianceRG_BG" to trace.inputCovarianceRgBg,
                "outputVarianceRG" to trace.outputVarianceRG,
                "outputVarianceBG" to trace.outputVarianceBG,
                "outputCovarianceRG_BG" to trace.outputCovarianceRgBg,
                "inputSampleCount" to trace.inputResidualSampleCount,
                "outputSampleCount" to trace.outputResidualSampleCount
            ),
            "timing" to linkedMapOf(
                "processingMs" to trace.processingTimeMs,
                "inputMeasurementMs" to trace.inputMeasurementMs,
                "outputMeasurementMs" to trace.outputMeasurementMs,
                "accounting" to "NESTED_IN_FINAL_OUTPUT_PASS_MS"
            ),
            "pixelBackend" to linkedMapOf(
                "selected" to trace.pixelBackend,
                "selectionReason" to trace.pixelBackendSelectionReason,
                "fallbackReason" to trace.pixelBackendFallbackReason,
                "neonCompiled" to trace.pixelNeonCompiled,
                "selfTest" to linkedMapOf(
                    "performed" to trace.pixelSimdSelfTestPerformed,
                    "passed" to trace.pixelSimdSelfTestPassed,
                    "maximumAbsoluteDelta" to trace.pixelSimdSelfTestMaximumAbsoluteDelta,
                    "elapsedMs" to trace.pixelSimdSelfTestElapsedMs
                ),
                "latencyQualification" to linkedMapOf(
                    "performed" to trace.pixelLatencyBenchmarkPerformed,
                    "passed" to trace.pixelLatencyBenchmarkPassed,
                    "scalarMs" to trace.pixelScalarBenchmarkMs,
                    "neonMs" to trace.pixelNeonBenchmarkMs,
                    "speedup" to trace.pixelBenchmarkSpeedup,
                    "minimumRequiredSpeedup" to 1.03
                ),
                "opponentPreprocessing" to linkedMapOf(
                    "vectorizedPixels" to trace.opponentVectorizedPixelCount,
                    "scalarPixels" to trace.opponentScalarPixelCount,
                    "rejectedNonFinitePixels" to trace.opponentRejectedNonFinitePixelCount,
                    "estimatedBytesRead" to trace.opponentEstimatedBytesRead,
                    "estimatedBytesWritten" to trace.opponentEstimatedBytesWritten,
                    "peakScratchBytes" to trace.opponentPeakScratchBytes,
                    "tileCount" to trace.opponentTileCount,
                    "tileSize" to trace.opponentTileSize,
                    "aggregateTileBuildMs" to trace.opponentTileBuildMs,
                    "mutexFreeDeterministicReduction" to trace.mutexFreeTileReduction,
                    "cpuWeightKernel" to trace.cpuWeightKernel,
                    "cpuNeighbourSamples" to trace.cpuNeighbourSampleCount,
                    "cpuGaussianWeightLookups" to trace.cpuGaussianWeightLookupCount,
                    "cpuGaussianWeightLutIntervals" to trace.cpuGaussianWeightLutIntervalCount,
                    "cpuGaussianWeightMaximumSquaredDistance" to
                        trace.cpuGaussianWeightMaximumSquaredDistance,
                    "cpuGaussianWeightLutMaximumAbsoluteError" to
                        trace.cpuGaussianWeightLutMaximumAbsoluteError,
                    "cpuPerPixelScaleHoisted" to trace.cpuPerPixelScaleHoisted,
                    "cpuCenterSampleHoisted" to trace.cpuCenterSampleHoisted
                )
            )
        )
    }

    companion object {
        fun fromNativeStats(stats: Map<String, String>): SpectraVisibleChromaTrace =
            SpectraVisibleChromaTrace(
                activationSource = stats["spectraVisibleChromaActivationSource"] ?: "DISABLED",
                architecture = stats["spectraVisibleChromaArchitecture"] ?: "UNAVAILABLE",
                inputStage = stats["spectraVisibleChromaInputStage"] ?: "UNAVAILABLE",
                outputStage = stats["spectraVisibleChromaOutputStage"] ?: "UNAVAILABLE",
                covarianceSource = stats["spectraVisibleChromaCovarianceSource"] ?: "UNAVAILABLE",
                timingAccounting = stats["spectraVisibleChromaTimingAccounting"] ?: "UNAVAILABLE",
                enabled = stats.booleanValue("spectraVisibleChromaEnabled"),
                applied = stats.booleanValue("spectraVisibleChromaApplied"),
                planStatus = stats["spectraVisibleChromaPlanStatus"] ?: "UNAVAILABLE",
                resultStatus = stats["spectraVisibleChromaResultStatus"] ?: "NOT_RUN",
                method = stats["spectraVisibleChromaMethod"] ?: "NONE",
                noRegretMethod = stats["spectraVisibleChromaNoRegretMethod"] ?: "NONE",
                modelConfidence = stats.numberValue("spectraVisibleChromaModelConfidence"),
                authority = stats.numberValue("spectraVisibleChromaAuthority"),
                maximumCorrection = stats.numberValue("spectraVisibleChromaMaximumCorrection"),
                sigmaRG = stats.numberValue("spectraVisibleChromaSigmaRG"),
                sigmaBG = stats.numberValue("spectraVisibleChromaSigmaBG"),
                covarianceCorrelation = stats.numberValue("spectraVisibleChromaCovarianceCorrelation"),
                predictedVarianceRG = stats.numberValue("spectraVisibleChromaPredictedVarianceRG"),
                predictedVarianceBG = stats.numberValue("spectraVisibleChromaPredictedVarianceBG"),
                predictedCovarianceRgBg = stats.numberValue("spectraVisibleChromaPredictedCovarianceRgBg"),
                processedPixelCount = stats.longValueM4("spectraVisibleChromaProcessedPixelCount"),
                candidatePixelCount = stats.longValueM4("spectraVisibleChromaCandidatePixelCount"),
                changedPixelCount = stats.longValueM4("spectraVisibleChromaChangedPixelCount"),
                changedPixelFraction = stats.numberValue("spectraVisibleChromaChangedPixelFraction"),
                lumaEdgeProtectedPixelCount = stats.longValueM4("spectraVisibleChromaLumaEdgeProtectedPixelCount"),
                colourEdgeProtectedPixelCount = stats.longValueM4("spectraVisibleChromaColourEdgeProtectedPixelCount"),
                saturationProtectedPixelCount = stats.longValueM4("spectraVisibleChromaSaturationProtectedPixelCount"),
                fullyAcceptedPixelCount = stats.longValueM4("spectraVisibleChromaFullyAcceptedPixelCount"),
                partiallyAcceptedPixelCount = stats.longValueM4("spectraVisibleChromaPartiallyAcceptedPixelCount"),
                rejectedPixelCount = stats.longValueM4("spectraVisibleChromaRejectedPixelCount"),
                fullyAcceptedTileCount = stats.longValueM4("spectraVisibleChromaFullyAcceptedTileCount"),
                partiallyAcceptedTileCount = stats.longValueM4("spectraVisibleChromaPartiallyAcceptedTileCount"),
                rejectedTileCount = stats.longValueM4("spectraVisibleChromaRejectedTileCount"),
                evaluatedTileCount = stats.longValueM4("spectraVisibleChromaEvaluatedTileCount"),
                meanAcceptance = stats.numberValue("spectraVisibleChromaMeanAcceptance"),
                acceptanceP10 = stats.numberValue("spectraVisibleChromaAcceptanceP10"),
                acceptanceP50 = stats.numberValue("spectraVisibleChromaAcceptanceP50"),
                acceptanceP90 = stats.numberValue("spectraVisibleChromaAcceptanceP90"),
                meanColourShift = stats.numberValue("spectraVisibleChromaMeanColourShift"),
                maximumColourShift = stats.numberValue("spectraVisibleChromaMaximumColourShift"),
                meanNoiseImprovement = stats.numberValue("spectraVisibleChromaMeanNoiseImprovement"),
                edgePreservationScore = stats.numberValue("spectraVisibleChromaEdgePreservationScore", 1.0),
                oversmoothingScore = stats.numberValue("spectraVisibleChromaOversmoothingScore"),
                inputVarianceRG = stats.numberValue("spectraVisibleChromaInputVarianceRG"),
                inputVarianceBG = stats.numberValue("spectraVisibleChromaInputVarianceBG"),
                inputCovarianceRgBg = stats.numberValue("spectraVisibleChromaInputCovarianceRgBg"),
                outputVarianceRG = stats.numberValue("spectraVisibleChromaOutputVarianceRG"),
                outputVarianceBG = stats.numberValue("spectraVisibleChromaOutputVarianceBG"),
                outputCovarianceRgBg = stats.numberValue("spectraVisibleChromaOutputCovarianceRgBg"),
                inputResidualSampleCount = stats.longValueM4("spectraVisibleChromaInputResidualSampleCount"),
                outputResidualSampleCount = stats.longValueM4("spectraVisibleChromaOutputResidualSampleCount"),
                processingTimeMs = stats.numberValue("spectraVisibleChromaProcessingTimeMs"),
                inputMeasurementMs = stats.numberValue("spectraVisibleChromaInputMeasurementMs"),
                outputMeasurementMs = stats.numberValue("spectraVisibleChromaOutputMeasurementMs"),
                pixelBackend = stats["spectraVisibleChromaPixelBackend"] ?: "NOT_SELECTED",
                pixelBackendSelectionReason =
                    stats["spectraVisibleChromaPixelBackendSelectionReason"] ?: "UNAVAILABLE",
                pixelBackendFallbackReason =
                    stats["spectraVisibleChromaPixelBackendFallbackReason"] ?: "none",
                pixelNeonCompiled = stats.booleanValue("spectraVisibleChromaPixelNeonCompiled"),
                pixelSimdSelfTestPerformed =
                    stats.booleanValue("spectraVisibleChromaPixelSimdSelfTestPerformed"),
                pixelSimdSelfTestPassed =
                    stats.booleanValue("spectraVisibleChromaPixelSimdSelfTestPassed"),
                pixelSimdSelfTestMaximumAbsoluteDelta = stats.numberValue(
                    "spectraVisibleChromaPixelSimdSelfTestMaximumAbsoluteDelta"
                ),
                pixelSimdSelfTestElapsedMs =
                    stats.numberValue("spectraVisibleChromaPixelSimdSelfTestElapsedMs"),
                pixelLatencyBenchmarkPerformed = stats.booleanValue(
                    "spectraVisibleChromaPixelLatencyBenchmarkPerformed"
                ),
                pixelLatencyBenchmarkPassed = stats.booleanValue(
                    "spectraVisibleChromaPixelLatencyBenchmarkPassed"
                ),
                pixelScalarBenchmarkMs = stats.numberValue(
                    "spectraVisibleChromaPixelScalarBenchmarkMs"
                ),
                pixelNeonBenchmarkMs = stats.numberValue(
                    "spectraVisibleChromaPixelNeonBenchmarkMs"
                ),
                pixelBenchmarkSpeedup = stats.numberValue(
                    "spectraVisibleChromaPixelBenchmarkSpeedup"
                ),
                opponentVectorizedPixelCount =
                    stats.longValueM4("spectraVisibleChromaOpponentVectorizedPixelCount"),
                opponentScalarPixelCount =
                    stats.longValueM4("spectraVisibleChromaOpponentScalarPixelCount"),
                opponentRejectedNonFinitePixelCount = stats.longValueM4(
                    "spectraVisibleChromaOpponentRejectedNonFinitePixelCount"
                ),
                opponentEstimatedBytesRead =
                    stats.longValueM4("spectraVisibleChromaOpponentEstimatedBytesRead"),
                opponentEstimatedBytesWritten =
                    stats.longValueM4("spectraVisibleChromaOpponentEstimatedBytesWritten"),
                opponentPeakScratchBytes =
                    stats.longValueM4("spectraVisibleChromaOpponentPeakScratchBytes"),
                opponentTileCount =
                    stats.longValueM4("spectraVisibleChromaOpponentTileCount").toInt(),
                opponentTileSize =
                    stats.longValueM4("spectraVisibleChromaOpponentTileSize").toInt(),
                opponentTileBuildMs =
                    stats.numberValue("spectraVisibleChromaOpponentTileBuildMs"),
                mutexFreeTileReduction =
                    stats.booleanValue("spectraVisibleChromaMutexFreeTileReduction"),
                cpuWeightKernel =
                    stats["spectraVisibleChromaCpuWeightKernel"] ?: "UNAVAILABLE",
                cpuNeighbourSampleCount = stats.longValueM4(
                    "spectraVisibleChromaCpuNeighbourSampleCount"
                ),
                cpuGaussianWeightLookupCount = stats.longValueM4(
                    "spectraVisibleChromaCpuGaussianWeightLookupCount"
                ),
                cpuGaussianWeightLutIntervalCount = stats.longValueM4(
                    "spectraVisibleChromaCpuGaussianWeightLutIntervalCount"
                ).toInt(),
                cpuGaussianWeightMaximumSquaredDistance = stats.numberValue(
                    "spectraVisibleChromaCpuGaussianWeightMaximumSquaredDistance"
                ),
                cpuGaussianWeightLutMaximumAbsoluteError = stats.numberValue(
                    "spectraVisibleChromaCpuGaussianWeightLutMaximumAbsoluteError"
                ),
                cpuPerPixelScaleHoisted = stats.booleanValue(
                    "spectraVisibleChromaCpuPerPixelScaleHoisted"
                ),
                cpuCenterSampleHoisted = stats.booleanValue(
                    "spectraVisibleChromaCpuCenterSampleHoisted"
                )
            ).sanitized()
    }
}

private fun Map<String, String>.numberValue(key: String, default: Double = 0.0): Double =
    this[key]?.toDoubleOrNull()?.takeIf(Double::isFinite) ?: default

private fun Map<String, String>.longValueM4(key: String): Long = this[key]?.toLongOrNull() ?: 0L

private fun Map<String, String>.booleanValue(key: String): Boolean =
    this[key].equals("true", ignoreCase = true) || this[key].equals("yes", ignoreCase = true)

private fun finite(value: Double): Double = if (value.isFinite()) value else 0.0
private fun positive(value: Double): Double = max(0.0, finite(value))
private fun unit(value: Double): Double = finite(value).coerceIn(0.0, 1.0)
