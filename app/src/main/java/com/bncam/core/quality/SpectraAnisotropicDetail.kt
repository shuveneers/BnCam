package com.bncam.core.quality

import kotlin.math.max

/** Milestone-5 green/luma structure-tensor and directional Pass-1 trace. */
data class SpectraAnisotropicDetailTrace(
    val architecture: String = "UNAVAILABLE",
    val tensorMethod: String = "UNAVAILABLE",
    val filterMethod: String = "UNAVAILABLE",
    val fallbackMethod: String = "UNAVAILABLE",
    val timingAccounting: String = "NESTED_IN_SPECTRA_PASS1_PROCESSING_MS",
    val enabled: Boolean = false,
    val applied: Boolean = false,
    val status: String = "NOT_RUN",
    val evaluatedPixelCount: Long = 0,
    val validTensorPixelCount: Long = 0,
    val confidentTensorPixelCount: Long = 0,
    val fallbackPixelCount: Long = 0,
    val directionalChangedPixelCount: Long = 0,
    val crossEdgeProtectedSampleCount: Long = 0,
    val alongStructureSupportedSampleCount: Long = 0,
    val orientation0Count: Long = 0,
    val orientation45Count: Long = 0,
    val orientation90Count: Long = 0,
    val orientation135Count: Long = 0,
    val validTensorFraction: Double = 0.0,
    val confidentTensorFraction: Double = 0.0,
    val fallbackFraction: Double = 1.0,
    val directionalChangedFraction: Double = 0.0,
    val meanConfidence: Double = 0.0,
    val confidenceP10: Double = 0.0,
    val confidenceP50: Double = 0.0,
    val confidenceP90: Double = 0.0,
    val meanCoherence: Double = 0.0,
    val coherenceP10: Double = 0.0,
    val coherenceP50: Double = 0.0,
    val coherenceP90: Double = 0.0,
    val meanDirectionalWeight: Double = 1.0,
    val meanIsotropicAuthorityScale: Double = 1.0,
    val meanDirectionalAuthorityScale: Double = 1.0,
    val maximumLinearCorrection: Double = 0.0,
    val tensorFieldBuildMs: Double = 0.0,
    val directionalFilterMs: Double = 0.0
) {
    fun sanitized(): SpectraAnisotropicDetailTrace = copy(
        evaluatedPixelCount = evaluatedPixelCount.coerceAtLeast(0),
        validTensorPixelCount = validTensorPixelCount.coerceAtLeast(0),
        confidentTensorPixelCount = confidentTensorPixelCount.coerceAtLeast(0),
        fallbackPixelCount = fallbackPixelCount.coerceAtLeast(0),
        directionalChangedPixelCount = directionalChangedPixelCount.coerceAtLeast(0),
        crossEdgeProtectedSampleCount = crossEdgeProtectedSampleCount.coerceAtLeast(0),
        alongStructureSupportedSampleCount = alongStructureSupportedSampleCount.coerceAtLeast(0),
        orientation0Count = orientation0Count.coerceAtLeast(0),
        orientation45Count = orientation45Count.coerceAtLeast(0),
        orientation90Count = orientation90Count.coerceAtLeast(0),
        orientation135Count = orientation135Count.coerceAtLeast(0),
        validTensorFraction = unitM5(validTensorFraction),
        confidentTensorFraction = unitM5(confidentTensorFraction),
        fallbackFraction = unitM5(fallbackFraction),
        directionalChangedFraction = unitM5(directionalChangedFraction),
        meanConfidence = unitM5(meanConfidence),
        confidenceP10 = unitM5(confidenceP10),
        confidenceP50 = unitM5(confidenceP50),
        confidenceP90 = unitM5(confidenceP90),
        meanCoherence = unitM5(meanCoherence),
        coherenceP10 = unitM5(coherenceP10),
        coherenceP50 = unitM5(coherenceP50),
        coherenceP90 = unitM5(coherenceP90),
        meanDirectionalWeight = positiveM5(meanDirectionalWeight),
        meanIsotropicAuthorityScale = unitM5(meanIsotropicAuthorityScale),
        meanDirectionalAuthorityScale = unitM5(meanDirectionalAuthorityScale),
        maximumLinearCorrection = positiveM5(maximumLinearCorrection),
        tensorFieldBuildMs = positiveM5(tensorFieldBuildMs),
        directionalFilterMs = positiveM5(directionalFilterMs)
    )

    fun toTraceMap(): Map<String, Any> = sanitized().let { trace ->
        linkedMapOf(
            "architecture" to trace.architecture,
            "tensorMethod" to trace.tensorMethod,
            "filterMethod" to trace.filterMethod,
            "fallbackMethod" to trace.fallbackMethod,
            "enabled" to trace.enabled,
            "applied" to trace.applied,
            "status" to trace.status,
            "pixels" to linkedMapOf(
                "evaluated" to trace.evaluatedPixelCount,
                "validTensor" to trace.validTensorPixelCount,
                "confidentTensor" to trace.confidentTensorPixelCount,
                "fallback" to trace.fallbackPixelCount,
                "directionalChanged" to trace.directionalChangedPixelCount,
                "validTensorFraction" to trace.validTensorFraction,
                "confidentTensorFraction" to trace.confidentTensorFraction,
                "fallbackFraction" to trace.fallbackFraction,
                "directionalChangedFraction" to trace.directionalChangedFraction
            ),
            "directionalSupport" to linkedMapOf(
                "crossEdgeProtectedSamples" to trace.crossEdgeProtectedSampleCount,
                "alongStructureSupportedSamples" to trace.alongStructureSupportedSampleCount,
                "meanDirectionalWeight" to trace.meanDirectionalWeight,
                "meanIsotropicAuthorityScale" to trace.meanIsotropicAuthorityScale,
                "meanDirectionalAuthorityScale" to trace.meanDirectionalAuthorityScale,
                "maximumLinearCorrection" to trace.maximumLinearCorrection
            ),
            "tensor" to linkedMapOf(
                "meanConfidence" to trace.meanConfidence,
                "confidenceP10" to trace.confidenceP10,
                "confidenceP50" to trace.confidenceP50,
                "confidenceP90" to trace.confidenceP90,
                "meanCoherence" to trace.meanCoherence,
                "coherenceP10" to trace.coherenceP10,
                "coherenceP50" to trace.coherenceP50,
                "coherenceP90" to trace.coherenceP90,
                "orientationHistogram" to linkedMapOf(
                    "0deg" to trace.orientation0Count,
                    "45deg" to trace.orientation45Count,
                    "90deg" to trace.orientation90Count,
                    "135deg" to trace.orientation135Count
                )
            ),
            "timing" to linkedMapOf(
                "tensorFieldBuildMs" to trace.tensorFieldBuildMs,
                "directionalFilterMs" to trace.directionalFilterMs,
                "accounting" to trace.timingAccounting
            )
        )
    }

    companion object {
        fun fromNativeStats(stats: Map<String, String>): SpectraAnisotropicDetailTrace =
            SpectraAnisotropicDetailTrace(
                architecture = stats["spectraAnisotropicDetailArchitecture"] ?: "UNAVAILABLE",
                tensorMethod = stats["spectraAnisotropicDetailTensorMethod"] ?: "UNAVAILABLE",
                filterMethod = stats["spectraAnisotropicDetailFilterMethod"] ?: "UNAVAILABLE",
                fallbackMethod = stats["spectraAnisotropicDetailFallbackMethod"] ?: "UNAVAILABLE",
                timingAccounting = stats["spectraAnisotropicDetailTimingAccounting"] ?: "UNAVAILABLE",
                enabled = stats.booleanM5("spectraAnisotropicDetailEnabled"),
                applied = stats.booleanM5("spectraAnisotropicDetailApplied"),
                status = stats["spectraAnisotropicDetailStatus"] ?: "NOT_RUN",
                evaluatedPixelCount = stats.longM5("spectraAnisotropicDetailEvaluatedPixelCount"),
                validTensorPixelCount = stats.longM5("spectraAnisotropicDetailValidTensorPixelCount"),
                confidentTensorPixelCount = stats.longM5("spectraAnisotropicDetailConfidentTensorPixelCount"),
                fallbackPixelCount = stats.longM5("spectraAnisotropicDetailFallbackPixelCount"),
                directionalChangedPixelCount = stats.longM5("spectraAnisotropicDetailDirectionalChangedPixelCount"),
                crossEdgeProtectedSampleCount = stats.longM5("spectraAnisotropicDetailCrossEdgeProtectedSampleCount"),
                alongStructureSupportedSampleCount = stats.longM5("spectraAnisotropicDetailAlongStructureSupportedSampleCount"),
                orientation0Count = stats.longM5("spectraAnisotropicDetailOrientation0Count"),
                orientation45Count = stats.longM5("spectraAnisotropicDetailOrientation45Count"),
                orientation90Count = stats.longM5("spectraAnisotropicDetailOrientation90Count"),
                orientation135Count = stats.longM5("spectraAnisotropicDetailOrientation135Count"),
                validTensorFraction = stats.numberM5("spectraAnisotropicDetailValidTensorFraction"),
                confidentTensorFraction = stats.numberM5("spectraAnisotropicDetailConfidentTensorFraction"),
                fallbackFraction = stats.numberM5("spectraAnisotropicDetailFallbackFraction", 1.0),
                directionalChangedFraction = stats.numberM5("spectraAnisotropicDetailDirectionalChangedFraction"),
                meanConfidence = stats.numberM5("spectraAnisotropicDetailMeanConfidence"),
                confidenceP10 = stats.numberM5("spectraAnisotropicDetailConfidenceP10"),
                confidenceP50 = stats.numberM5("spectraAnisotropicDetailConfidenceP50"),
                confidenceP90 = stats.numberM5("spectraAnisotropicDetailConfidenceP90"),
                meanCoherence = stats.numberM5("spectraAnisotropicDetailMeanCoherence"),
                coherenceP10 = stats.numberM5("spectraAnisotropicDetailCoherenceP10"),
                coherenceP50 = stats.numberM5("spectraAnisotropicDetailCoherenceP50"),
                coherenceP90 = stats.numberM5("spectraAnisotropicDetailCoherenceP90"),
                meanDirectionalWeight = stats.numberM5("spectraAnisotropicDetailMeanDirectionalWeight", 1.0),
                meanIsotropicAuthorityScale = stats.numberM5("spectraAnisotropicDetailMeanIsotropicAuthorityScale", 1.0),
                meanDirectionalAuthorityScale = stats.numberM5("spectraAnisotropicDetailMeanDirectionalAuthorityScale", 1.0),
                maximumLinearCorrection = stats.numberM5("spectraAnisotropicDetailMaximumLinearCorrection"),
                tensorFieldBuildMs = stats.numberM5("spectraAnisotropicDetailTensorFieldBuildMs"),
                directionalFilterMs = stats.numberM5("spectraAnisotropicDetailDirectionalFilterMs")
            ).sanitized()
    }
}

private fun Map<String, String>.numberM5(key: String, default: Double = 0.0): Double =
    this[key]?.toDoubleOrNull()?.takeIf(Double::isFinite) ?: default

private fun Map<String, String>.longM5(key: String): Long = this[key]?.toLongOrNull() ?: 0L

private fun Map<String, String>.booleanM5(key: String): Boolean =
    this[key].equals("true", ignoreCase = true) || this[key].equals("yes", ignoreCase = true)

private fun finiteM5(value: Double): Double = if (value.isFinite()) value else 0.0
private fun positiveM5(value: Double): Double = max(0.0, finiteM5(value))
private fun unitM5(value: Double): Double = finiteM5(value).coerceIn(0.0, 1.0)
