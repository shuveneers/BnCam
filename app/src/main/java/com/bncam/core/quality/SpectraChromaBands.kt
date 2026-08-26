package com.bncam.core.quality

import kotlin.math.max

/** One active but conservatively bounded Milestone-3B pre-demosaic chroma band. */
data class SpectraChromaBandTrace(
    val name: String = "UNKNOWN",
    val enabled: Boolean = false,
    val applied: Boolean = false,
    val planStatus: String = "UNAVAILABLE",
    val resultStatus: String = "NOT_RUN",
    val kernel: String = "NONE",
    val targetStatus: String = "UNAVAILABLE",
    val noRegretScope: String = "COMBINED_PASS_GATE",
    val inputEnergy: Double = 0.0,
    val targetFloor: Double = 0.0,
    val excessEnergy: Double = 0.0,
    val requiredReductionFraction: Double = 0.0,
    val authorityScale: Double = 0.0,
    val maximumCorrectionScale: Double = 0.0,
    val modelConfidence: Double = 0.0,
    val visibleChromaAmplification: Double = 1.0,
    val evidence: Double = 0.0,
    val outputEnergy: Double = 0.0,
    val outputStage: String = "UNAVAILABLE",
    val runtimeMethod: String = "NOT_RUN",
    val reductionPercentage: Double = 0.0,
    val coefficientEnergyBefore: Double = 0.0,
    val coefficientEnergyAfter: Double = 0.0,
    val maximumCorrection: Double = 0.0,
    val changedPixelFraction: Double = 0.0,
    val candidatePixelCount: Long = 0,
    val changedPixelCount: Long = 0,
    val structureRejectedPixelCount: Long = 0,
    val meanNoiseSigma: Double = 0.0,
    val meanStructureWeight: Double = 0.0,
    val meanShrinkage: Double = 0.0,
    val shrinkageP90: Double = 0.0,
    val maximumShrinkage: Double = 0.0,
    val processingTimeMs: Double = 0.0,
    val measurementTimeMs: Double = 0.0,
    val noRegretMeanAcceptance: Double = 0.0,
    val noRegretAttenuatedPixelFraction: Double = 0.0
) {
    fun sanitized(): SpectraChromaBandTrace = copy(
        inputEnergy = nonNegative(inputEnergy),
        targetFloor = nonNegative(targetFloor),
        excessEnergy = nonNegative(excessEnergy),
        requiredReductionFraction = bounded01(requiredReductionFraction),
        authorityScale = bounded01(authorityScale),
        maximumCorrectionScale = bounded01(maximumCorrectionScale),
        modelConfidence = bounded01(modelConfidence),
        visibleChromaAmplification = nonNegative(visibleChromaAmplification),
        evidence = bounded01(evidence),
        outputEnergy = nonNegative(outputEnergy),
        reductionPercentage = finite(reductionPercentage).coerceIn(-100.0, 100.0),
        coefficientEnergyBefore = nonNegative(coefficientEnergyBefore),
        coefficientEnergyAfter = nonNegative(coefficientEnergyAfter),
        maximumCorrection = nonNegative(maximumCorrection),
        changedPixelFraction = bounded01(changedPixelFraction),
        candidatePixelCount = candidatePixelCount.coerceAtLeast(0),
        changedPixelCount = changedPixelCount.coerceAtLeast(0),
        structureRejectedPixelCount = structureRejectedPixelCount.coerceAtLeast(0),
        meanNoiseSigma = nonNegative(meanNoiseSigma),
        meanStructureWeight = bounded01(meanStructureWeight),
        meanShrinkage = bounded01(meanShrinkage),
        shrinkageP90 = bounded01(shrinkageP90),
        maximumShrinkage = bounded01(maximumShrinkage),
        processingTimeMs = nonNegative(processingTimeMs),
        measurementTimeMs = nonNegative(measurementTimeMs),
        noRegretMeanAcceptance = bounded01(noRegretMeanAcceptance),
        noRegretAttenuatedPixelFraction = bounded01(noRegretAttenuatedPixelFraction)
    )

    fun toTraceMap(): Map<String, Any> = sanitized().let { band ->
        linkedMapOf(
            "name" to band.name,
            "enabled" to band.enabled,
            "applied" to band.applied,
            "planStatus" to band.planStatus,
            "resultStatus" to band.resultStatus,
            "kernel" to band.kernel,
            "targetStatus" to band.targetStatus,
            "noRegretScope" to band.noRegretScope,
            "inputEnergy" to band.inputEnergy,
            "targetFloor" to band.targetFloor,
            "excessEnergy" to band.excessEnergy,
            "requiredReductionFraction" to band.requiredReductionFraction,
            "authorityScale" to band.authorityScale,
            "maximumCorrectionScale" to band.maximumCorrectionScale,
            "modelConfidence" to band.modelConfidence,
            "visibleChromaAmplification" to band.visibleChromaAmplification,
            "evidence" to band.evidence,
            "outputEnergy" to band.outputEnergy,
            "outputStage" to band.outputStage,
            "runtimeMethod" to band.runtimeMethod,
            "reductionPercentage" to band.reductionPercentage,
            "coefficientEnergyBefore" to band.coefficientEnergyBefore,
            "coefficientEnergyAfter" to band.coefficientEnergyAfter,
            "maximumCorrection" to band.maximumCorrection,
            "changedPixelFraction" to band.changedPixelFraction,
            "candidatePixelCount" to band.candidatePixelCount,
            "changedPixelCount" to band.changedPixelCount,
            "structureRejectedPixelCount" to band.structureRejectedPixelCount,
            "meanNoiseSigma" to band.meanNoiseSigma,
            "meanStructureWeight" to band.meanStructureWeight,
            "meanShrinkage" to band.meanShrinkage,
            "shrinkageP90" to band.shrinkageP90,
            "maximumShrinkage" to band.maximumShrinkage,
            "processingTimeMs" to band.processingTimeMs,
            "measurementTimeMs" to band.measurementTimeMs,
            "noRegretMeanAcceptance" to band.noRegretMeanAcceptance,
            "noRegretAttenuatedPixelFraction" to band.noRegretAttenuatedPixelFraction
        )
    }

    companion object {
        fun fromNativeStats(prefix: String, stats: Map<String, String>): SpectraChromaBandTrace =
            SpectraChromaBandTrace(
                name = stats["${prefix}Name"] ?: "UNKNOWN",
                enabled = stats.bool("${prefix}Enabled"),
                applied = stats.bool("${prefix}Applied"),
                planStatus = stats["${prefix}PlanStatus"] ?: "UNAVAILABLE",
                resultStatus = stats["${prefix}ResultStatus"] ?: "NOT_RUN",
                kernel = stats["${prefix}Kernel"] ?: "NONE",
                targetStatus = stats["${prefix}TargetStatus"] ?: "UNAVAILABLE",
                noRegretScope = stats["${prefix}NoRegretScope"] ?: "COMBINED_PASS_GATE",
                inputEnergy = stats.num("${prefix}InputEnergy"),
                targetFloor = stats.num("${prefix}TargetFloor"),
                excessEnergy = stats.num("${prefix}ExcessEnergy"),
                requiredReductionFraction = stats.num("${prefix}RequiredReductionFraction"),
                authorityScale = stats.num("${prefix}AuthorityScale"),
                maximumCorrectionScale = stats.num("${prefix}MaximumCorrectionScale"),
                modelConfidence = stats.num("${prefix}ModelConfidence"),
                visibleChromaAmplification = stats.num(
                    "${prefix}VisibleChromaAmplification",
                    1.0
                ),
                evidence = stats.num("${prefix}Evidence"),
                outputEnergy = stats.num("${prefix}OutputEnergy"),
                outputStage = stats["${prefix}OutputStage"] ?: "UNAVAILABLE",
                runtimeMethod = stats["${prefix}RuntimeMethod"] ?: "NOT_RUN",
                reductionPercentage = stats.num("${prefix}ReductionPercentage"),
                coefficientEnergyBefore = stats.num("${prefix}CoefficientEnergyBefore"),
                coefficientEnergyAfter = stats.num("${prefix}CoefficientEnergyAfter"),
                maximumCorrection = stats.num("${prefix}MaximumCorrection"),
                changedPixelFraction = stats.num("${prefix}ChangedPixelFraction"),
                candidatePixelCount = stats.longValue("${prefix}CandidatePixelCount"),
                changedPixelCount = stats.longValue("${prefix}ChangedPixelCount"),
                structureRejectedPixelCount = stats.longValue(
                    "${prefix}StructureRejectedPixelCount"
                ),
                meanNoiseSigma = stats.num("${prefix}MeanNoiseSigma"),
                meanStructureWeight = stats.num("${prefix}MeanStructureWeight"),
                meanShrinkage = stats.num("${prefix}MeanShrinkage"),
                shrinkageP90 = stats.num("${prefix}ShrinkageP90"),
                maximumShrinkage = stats.num("${prefix}MaximumShrinkage"),
                processingTimeMs = stats.num("${prefix}ProcessingTimeMs"),
                measurementTimeMs = stats.num("${prefix}MeasurementTimeMs"),
                noRegretMeanAcceptance = stats.num("${prefix}NoRegretMeanAcceptance"),
                noRegretAttenuatedPixelFraction = stats.num(
                    "${prefix}NoRegretAttenuatedPixelFraction"
                )
            ).sanitized()
    }
}

/** Complete M3B architecture trace. Band timings are nested inside Pass 2/3 timing. */
data class SpectraChromaBandsTrace(
    val architecture: String = "UNAVAILABLE",
    val energyMethod: String = "UNINITIALIZED",
    val inputStatus: String = "UNAVAILABLE",
    val inputSampleCount: Long = 0,
    val inputRedSampleCount: Long = 0,
    val inputBlueSampleCount: Long = 0,
    val inputRedBlueSampleBalance: Double = 0.0,
    val inputConfidence: Double = 0.0,
    val inputMeasurementMs: Double = 0.0,
    val postPass2Status: String = "UNAVAILABLE",
    val postPass2SampleCount: Long = 0,
    val postPass2RedSampleCount: Long = 0,
    val postPass2BlueSampleCount: Long = 0,
    val postPass2RedBlueSampleBalance: Double = 0.0,
    val postPass2Confidence: Double = 0.0,
    val postPass2MeasurementMs: Double = 0.0,
    val fine: SpectraChromaBandTrace = SpectraChromaBandTrace(name = "FINE"),
    val mid: SpectraChromaBandTrace = SpectraChromaBandTrace(name = "MID"),
    val low: SpectraChromaBandTrace = SpectraChromaBandTrace(name = "LOW")
) {
    fun toTraceMap(): Map<String, Any> = linkedMapOf(
        "architecture" to architecture,
        "status" to "SPECTRA_CONTEXT_FUSION_ACTIVE_MULTISCALE_CHROMA",
        "calibrationStatus" to "BAND_SPLIT_AND_DEFAULT_AUTHORITY_PENDING_DEVICE_VALIDATION",
        "energyMeasurement" to linkedMapOf(
            "method" to energyMethod,
            "inputStatus" to inputStatus,
            "inputSampleCount" to inputSampleCount.coerceAtLeast(0),
            "inputRedSampleCount" to inputRedSampleCount.coerceAtLeast(0),
            "inputBlueSampleCount" to inputBlueSampleCount.coerceAtLeast(0),
            "inputRedBlueSampleBalance" to bounded01(inputRedBlueSampleBalance),
            "inputConfidence" to bounded01(inputConfidence),
            "inputMeasurementMs" to nonNegative(inputMeasurementMs),
            "postPass2Status" to postPass2Status,
            "postPass2SampleCount" to postPass2SampleCount.coerceAtLeast(0),
            "postPass2RedSampleCount" to postPass2RedSampleCount.coerceAtLeast(0),
            "postPass2BlueSampleCount" to postPass2BlueSampleCount.coerceAtLeast(0),
            "postPass2RedBlueSampleBalance" to bounded01(postPass2RedBlueSampleBalance),
            "postPass2Confidence" to bounded01(postPass2Confidence),
            "postPass2MeasurementMs" to nonNegative(postPass2MeasurementMs),
            "timingAccounting" to "NESTED_IN_PASS2_PASS3_TOTALS"
        ),
        "fine" to fine.toTraceMap(),
        "mid" to mid.toTraceMap(),
        "low" to low.toTraceMap()
    )

    companion object {
        fun fromNativeStats(stats: Map<String, String>): SpectraChromaBandsTrace =
            SpectraChromaBandsTrace(
                architecture = stats["spectraChromaBandsArchitecture"] ?: "UNAVAILABLE",
                energyMethod = stats["spectraChromaBandEnergyMethod"] ?: "UNINITIALIZED",
                inputStatus = stats["spectraChromaBandEnergyInputStatus"] ?: "UNAVAILABLE",
                inputSampleCount = stats.longValue("spectraChromaBandEnergyInputSampleCount"),
                inputRedSampleCount = stats.longValue(
                    "spectraChromaBandEnergyInputRedSampleCount"
                ),
                inputBlueSampleCount = stats.longValue(
                    "spectraChromaBandEnergyInputBlueSampleCount"
                ),
                inputRedBlueSampleBalance = stats.num(
                    "spectraChromaBandEnergyInputRedBlueSampleBalance"
                ),
                inputConfidence = stats.num("spectraChromaBandEnergyInputConfidence"),
                inputMeasurementMs = stats.num("spectraChromaBandEnergyInputMeasurementMs"),
                postPass2Status = stats["spectraChromaBandEnergyPostPass2Status"] ?: "UNAVAILABLE",
                postPass2SampleCount = stats.longValue(
                    "spectraChromaBandEnergyPostPass2SampleCount"
                ),
                postPass2RedSampleCount = stats.longValue(
                    "spectraChromaBandEnergyPostPass2RedSampleCount"
                ),
                postPass2BlueSampleCount = stats.longValue(
                    "spectraChromaBandEnergyPostPass2BlueSampleCount"
                ),
                postPass2RedBlueSampleBalance = stats.num(
                    "spectraChromaBandEnergyPostPass2RedBlueSampleBalance"
                ),
                postPass2Confidence = stats.num(
                    "spectraChromaBandEnergyPostPass2Confidence"
                ),
                postPass2MeasurementMs = stats.num(
                    "spectraChromaBandEnergyPostPass2MeasurementMs"
                ),
                fine = SpectraChromaBandTrace.fromNativeStats("spectraChromaFine", stats),
                mid = SpectraChromaBandTrace.fromNativeStats("spectraChromaMid", stats),
                low = SpectraChromaBandTrace.fromNativeStats("spectraChromaLow", stats)
            )
    }
}

private fun Map<String, String>.num(key: String, default: Double = 0.0): Double =
    this[key]?.toDoubleOrNull()?.takeIf(Double::isFinite) ?: default

private fun Map<String, String>.longValue(key: String): Long = this[key]?.toLongOrNull() ?: 0L

private fun Map<String, String>.bool(key: String): Boolean =
    this[key].equals("true", ignoreCase = true) || this[key].equals("yes", ignoreCase = true)

private fun finite(value: Double): Double = if (value.isFinite()) value else 0.0
private fun nonNegative(value: Double): Double = max(0.0, finite(value))
private fun bounded01(value: Double): Double = finite(value).coerceIn(0.0, 1.0)
