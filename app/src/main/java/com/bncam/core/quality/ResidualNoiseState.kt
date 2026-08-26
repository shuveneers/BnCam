package com.bncam.core.quality

import kotlin.math.max

/** Immutable RGB/covariance state exported at one active ISP stage. */
data class NoisePropagationStage(
    val stage: String = "UNKNOWN",
    val method: String = "UNINITIALIZED",
    val status: String = "UNAVAILABLE",
    val confidence: Double = 0.0,
    val varianceY: Double = 0.0,
    val varianceRG: Double = 0.0,
    val varianceBG: Double = 0.0,
    val covarianceRgBg: Double = 0.0,
    val covarianceRgb: List<Double> = List(9) { 0.0 }
) {
    fun sanitized(): NoisePropagationStage = copy(
        confidence = finiteOrZero(confidence).coerceIn(0.0, 1.0),
        varianceY = finiteNonNegative(varianceY),
        varianceRG = finiteNonNegative(varianceRG),
        varianceBG = finiteNonNegative(varianceBG),
        covarianceRgBg = finiteOrZero(covarianceRgBg),
        covarianceRgb = covarianceRgb.fixedFiniteArray(9)
    )

    fun toTraceMap(): Map<String, Any> = sanitized().let { state ->
        linkedMapOf(
            "stage" to state.stage,
            "method" to state.method,
            "status" to state.status,
            "confidence" to state.confidence,
            "varianceY" to state.varianceY,
            "varianceRG" to state.varianceRG,
            "varianceBG" to state.varianceBG,
            "covarianceRG_BG" to state.covarianceRgBg,
            "covarianceRGB" to state.covarianceRgb
        )
    }

    companion object {
        fun fromNativeStats(prefix: String, stats: Map<String, String>): NoisePropagationStage =
            NoisePropagationStage(
                stage = stats["${prefix}Stage"] ?: "UNKNOWN",
                method = stats["${prefix}Method"] ?: "UNINITIALIZED",
                status = stats["${prefix}Status"] ?: "UNAVAILABLE",
                confidence = stats.double("${prefix}Confidence"),
                varianceY = stats.double("${prefix}VarianceY"),
                varianceRG = stats.double("${prefix}VarianceRG"),
                varianceBG = stats.double("${prefix}VarianceBG"),
                covarianceRgBg = stats.double("${prefix}CovarianceRgBg"),
                covarianceRgb = parseArray(stats["${prefix}CovarianceRgb"], expected = 9)
            ).sanitized()
    }
}

/** Distribution of a local transform derivative sampled from the active capture. */
data class NoiseDerivativeStatistics(
    val sampleCount: Long = 0,
    val mean: Double = 1.0,
    val rms: Double = 1.0,
    val p10: Double = 1.0,
    val p50: Double = 1.0,
    val p90: Double = 1.0,
    val minimum: Double = 1.0,
    val maximum: Double = 1.0
) {
    fun sanitized(): NoiseDerivativeStatistics = copy(
        sampleCount = sampleCount.coerceAtLeast(0),
        mean = finiteNonNegative(mean),
        rms = finiteNonNegative(rms),
        p10 = finiteNonNegative(p10),
        p50 = finiteNonNegative(p50),
        p90 = finiteNonNegative(p90),
        minimum = finiteNonNegative(minimum),
        maximum = finiteNonNegative(maximum)
    )

    fun toTraceMap(): Map<String, Any> = sanitized().let { stats ->
        linkedMapOf(
            "sampleCount" to stats.sampleCount,
            "mean" to stats.mean,
            "rms" to stats.rms,
            "p10" to stats.p10,
            "p50" to stats.p50,
            "p90" to stats.p90,
            "min" to stats.minimum,
            "max" to stats.maximum
        )
    }

    companion object {
        fun fromNativeStats(prefix: String, stats: Map<String, String>): NoiseDerivativeStatistics =
            NoiseDerivativeStatistics(
                sampleCount = stats.long("${prefix}SampleCount"),
                mean = stats.double("${prefix}Mean", default = 1.0),
                rms = stats.double("${prefix}Rms", default = 1.0),
                p10 = stats.double("${prefix}P10", default = 1.0),
                p50 = stats.double("${prefix}P50", default = 1.0),
                p90 = stats.double("${prefix}P90", default = 1.0),
                minimum = stats.double("${prefix}Min", default = 1.0),
                maximum = stats.double("${prefix}Max", default = 1.0)
            ).sanitized()
    }
}


/** Structure-rejected residual observation measured in the same linear RGB stage as a prediction. */
data class NoiseResidualObservation(
    val stage: String = "UNKNOWN",
    val method: String = "UNINITIALIZED",
    val status: String = "UNAVAILABLE",
    val confidence: Double = 0.0,
    val filterEnergyGain: Double = 1.25,
    val varianceY: Double = 0.0,
    val varianceRG: Double = 0.0,
    val varianceBG: Double = 0.0,
    val covarianceRgBg: Double = 0.0,
    val robustVarianceY: Double = 0.0,
    val robustVarianceRG: Double = 0.0,
    val robustVarianceBG: Double = 0.0,
    val robustCovarianceRgBg: Double = 0.0,
    val varianceYP10: Double = 0.0,
    val varianceYP50: Double = 0.0,
    val varianceYP90: Double = 0.0,
    val varianceRGP10: Double = 0.0,
    val varianceRGP50: Double = 0.0,
    val varianceRGP90: Double = 0.0,
    val varianceBGP10: Double = 0.0,
    val varianceBGP50: Double = 0.0,
    val varianceBGP90: Double = 0.0,
    val structureThreshold: Double = 0.0,
    val flatSampleFraction: Double = 0.0,
    val textureContamination: Double = 0.0,
    val candidateSampleCount: Long = 0,
    val acceptedSampleCount: Long = 0,
    val validTileCount: Long = 0,
    val totalTileCount: Long = 0
) {
    fun sanitized(): NoiseResidualObservation = copy(
        confidence = finiteOrZero(confidence).coerceIn(0.0, 1.0),
        filterEnergyGain = max(1.0, finiteNonNegative(filterEnergyGain)),
        varianceY = finiteNonNegative(varianceY),
        varianceRG = finiteNonNegative(varianceRG),
        varianceBG = finiteNonNegative(varianceBG),
        covarianceRgBg = finiteOrZero(covarianceRgBg),
        robustVarianceY = finiteNonNegative(robustVarianceY),
        robustVarianceRG = finiteNonNegative(robustVarianceRG),
        robustVarianceBG = finiteNonNegative(robustVarianceBG),
        robustCovarianceRgBg = finiteOrZero(robustCovarianceRgBg),
        varianceYP10 = finiteNonNegative(varianceYP10),
        varianceYP50 = finiteNonNegative(varianceYP50),
        varianceYP90 = finiteNonNegative(varianceYP90),
        varianceRGP10 = finiteNonNegative(varianceRGP10),
        varianceRGP50 = finiteNonNegative(varianceRGP50),
        varianceRGP90 = finiteNonNegative(varianceRGP90),
        varianceBGP10 = finiteNonNegative(varianceBGP10),
        varianceBGP50 = finiteNonNegative(varianceBGP50),
        varianceBGP90 = finiteNonNegative(varianceBGP90),
        structureThreshold = finiteNonNegative(structureThreshold),
        flatSampleFraction = finiteOrZero(flatSampleFraction).coerceIn(0.0, 1.0),
        textureContamination = finiteNonNegative(textureContamination),
        candidateSampleCount = candidateSampleCount.coerceAtLeast(0),
        acceptedSampleCount = acceptedSampleCount.coerceAtLeast(0),
        validTileCount = validTileCount.coerceAtLeast(0),
        totalTileCount = totalTileCount.coerceAtLeast(0)
    )

    fun toTraceMap(): Map<String, Any> = sanitized().let { observation ->
        linkedMapOf(
            "stage" to observation.stage,
            "method" to observation.method,
            "status" to observation.status,
            "confidence" to observation.confidence,
            "filterEnergyGain" to observation.filterEnergyGain,
            "varianceY" to observation.varianceY,
            "varianceRG" to observation.varianceRG,
            "varianceBG" to observation.varianceBG,
            "covarianceRG_BG" to observation.covarianceRgBg,
            "robustVarianceY" to observation.robustVarianceY,
            "robustVarianceRG" to observation.robustVarianceRG,
            "robustVarianceBG" to observation.robustVarianceBG,
            "robustCovarianceRG_BG" to observation.robustCovarianceRgBg,
            "varianceYP10" to observation.varianceYP10,
            "varianceYP50" to observation.varianceYP50,
            "varianceYP90" to observation.varianceYP90,
            "varianceRGP10" to observation.varianceRGP10,
            "varianceRGP50" to observation.varianceRGP50,
            "varianceRGP90" to observation.varianceRGP90,
            "varianceBGP10" to observation.varianceBGP10,
            "varianceBGP50" to observation.varianceBGP50,
            "varianceBGP90" to observation.varianceBGP90,
            "structureThreshold" to observation.structureThreshold,
            "flatSampleFraction" to observation.flatSampleFraction,
            "textureContamination" to observation.textureContamination,
            "candidateSampleCount" to observation.candidateSampleCount,
            "acceptedSampleCount" to observation.acceptedSampleCount,
            "validTileCount" to observation.validTileCount,
            "totalTileCount" to observation.totalTileCount
        )
    }

    companion object {
        fun fromNativeStats(prefix: String, stats: Map<String, String>): NoiseResidualObservation =
            NoiseResidualObservation(
                stage = stats["${prefix}Stage"] ?: "UNKNOWN",
                method = stats["${prefix}Method"] ?: "UNINITIALIZED",
                status = stats["${prefix}Status"] ?: "UNAVAILABLE",
                confidence = stats.double("${prefix}Confidence"),
                filterEnergyGain = stats.double("${prefix}FilterEnergyGain", 1.25),
                varianceY = stats.double("${prefix}VarianceY"),
                varianceRG = stats.double("${prefix}VarianceRG"),
                varianceBG = stats.double("${prefix}VarianceBG"),
                covarianceRgBg = stats.double("${prefix}CovarianceRgBg"),
                robustVarianceY = stats.double("${prefix}RobustVarianceY"),
                robustVarianceRG = stats.double("${prefix}RobustVarianceRG"),
                robustVarianceBG = stats.double("${prefix}RobustVarianceBG"),
                robustCovarianceRgBg = stats.double("${prefix}RobustCovarianceRgBg"),
                varianceYP10 = stats.double("${prefix}VarianceYP10"),
                varianceYP50 = stats.double("${prefix}VarianceYP50"),
                varianceYP90 = stats.double("${prefix}VarianceYP90"),
                varianceRGP10 = stats.double("${prefix}VarianceRGP10"),
                varianceRGP50 = stats.double("${prefix}VarianceRGP50"),
                varianceRGP90 = stats.double("${prefix}VarianceRGP90"),
                varianceBGP10 = stats.double("${prefix}VarianceBGP10"),
                varianceBGP50 = stats.double("${prefix}VarianceBGP50"),
                varianceBGP90 = stats.double("${prefix}VarianceBGP90"),
                structureThreshold = stats.double("${prefix}StructureThreshold"),
                flatSampleFraction = stats.double("${prefix}FlatSampleFraction"),
                textureContamination = stats.double("${prefix}TextureContamination"),
                candidateSampleCount = stats.long("${prefix}CandidateSampleCount"),
                acceptedSampleCount = stats.long("${prefix}AcceptedSampleCount"),
                validTileCount = stats.long("${prefix}ValidTileCount"),
                totalTileCount = stats.long("${prefix}TotalTileCount")
            ).sanitized()
    }
}

/** Prediction-versus-observation result. Ratios are exported but never applied automatically. */
data class NoiseCalibrationComparison(
    val stage: String = "UNKNOWN",
    val status: String = "UNAVAILABLE",
    val ready: Boolean = false,
    val confidence: Double = 0.0,
    val measuredToPredictedY: Double = 0.0,
    val measuredToPredictedRG: Double = 0.0,
    val measuredToPredictedBG: Double = 0.0,
    val measuredToPredictedChroma: Double = 0.0,
    val absoluteLog2ErrorY: Double = 0.0,
    val absoluteLog2ErrorRG: Double = 0.0,
    val absoluteLog2ErrorBG: Double = 0.0
) {
    fun sanitized(): NoiseCalibrationComparison = copy(
        confidence = finiteOrZero(confidence).coerceIn(0.0, 1.0),
        measuredToPredictedY = finiteNonNegative(measuredToPredictedY),
        measuredToPredictedRG = finiteNonNegative(measuredToPredictedRG),
        measuredToPredictedBG = finiteNonNegative(measuredToPredictedBG),
        measuredToPredictedChroma = finiteNonNegative(measuredToPredictedChroma),
        absoluteLog2ErrorY = finiteNonNegative(absoluteLog2ErrorY),
        absoluteLog2ErrorRG = finiteNonNegative(absoluteLog2ErrorRG),
        absoluteLog2ErrorBG = finiteNonNegative(absoluteLog2ErrorBG)
    )

    fun toTraceMap(): Map<String, Any> = sanitized().let { comparison ->
        linkedMapOf(
            "stage" to comparison.stage,
            "status" to comparison.status,
            "ready" to comparison.ready,
            "confidence" to comparison.confidence,
            "measuredToPredictedY" to comparison.measuredToPredictedY,
            "measuredToPredictedRG" to comparison.measuredToPredictedRG,
            "measuredToPredictedBG" to comparison.measuredToPredictedBG,
            "measuredToPredictedChroma" to comparison.measuredToPredictedChroma,
            "absoluteLog2ErrorY" to comparison.absoluteLog2ErrorY,
            "absoluteLog2ErrorRG" to comparison.absoluteLog2ErrorRG,
            "absoluteLog2ErrorBG" to comparison.absoluteLog2ErrorBG
        )
    }

    companion object {
        fun fromNativeStats(prefix: String, stats: Map<String, String>): NoiseCalibrationComparison =
            NoiseCalibrationComparison(
                stage = stats["${prefix}Stage"] ?: "UNKNOWN",
                status = stats["${prefix}Status"] ?: "UNAVAILABLE",
                ready = stats.boolean("${prefix}Ready"),
                confidence = stats.double("${prefix}Confidence"),
                measuredToPredictedY = stats.double("${prefix}MeasuredToPredictedY"),
                measuredToPredictedRG = stats.double("${prefix}MeasuredToPredictedRG"),
                measuredToPredictedBG = stats.double("${prefix}MeasuredToPredictedBG"),
                measuredToPredictedChroma = stats.double("${prefix}MeasuredToPredictedChroma"),
                absoluteLog2ErrorY = stats.double("${prefix}AbsoluteLog2ErrorY"),
                absoluteLog2ErrorRG = stats.double("${prefix}AbsoluteLog2ErrorRG"),
                absoluteLog2ErrorBG = stats.double("${prefix}AbsoluteLog2ErrorBG")
            ).sanitized()
    }
}

/**
 * SPECTRA 2 residual-noise contract through the complete CPU JPEG ISP. Milestone 7 extends the
 * physical state through 8-bit quantisation and the noise-aware sharpening/local-contrast stage.
 * JPEG compression remains outside the analytical model and is observed only after encoding.
 */
data class ResidualNoiseState(
    val available: Boolean = false,
    val domain: String = "FINAL_JPEG_RGB",
    val valueStage: String = "FINAL_JPEG_PRE_ENCODE",
    val lastObservedStage: String = "JPEG_ENCODED",
    val propagationStatus: String = "MILESTONE_7_FINAL_JPEG_RESIDUAL_STATE",
    val opponentVarianceStatus: String = "DERIVED_FROM_RGB_COVARIANCE",
    val covarianceStatus: String = "SYMMETRIC_PSD_BOUNDED",
    val comparabilityStatus: String =
        "FINAL_JPEG_RESIDUAL_STATE_MEASURED_PRE_ENCODE_JPEG_COMPRESSION_NOT_PROPAGATED",
    val varianceY: Double = 0.0,
    val varianceRG: Double = 0.0,
    val varianceBG: Double = 0.0,
    val covarianceRgBg: Double = 0.0,
    val covarianceRgb: List<Double> = List(9) { 0.0 },
    val highFrequencyBudget: Double = 0.0,
    val midFrequencyBudget: Double = 0.0,
    val lowFrequencyBudget: Double = 0.0,
    val correlationLength: Double = 0.0,
    val directionalPatternEnergy: Double = 0.0,
    val rowPatternEnergy: Double = 0.0,
    val columnPatternEnergy: Double = 0.0,
    val temporalCorrelation: Double = 0.0,
    val independentNoiseFraction: Double = 1.0,
    val effectiveFrameCount: Double = 1.0,
    val modelConfidence: Double = 0.0,
    val motionConfidence: Double = 0.0,
    val alignmentConfidence: Double = 0.0,
    val preDemosaic: NoisePropagationStage = NoisePropagationStage(),
    val postDemosaic: NoisePropagationStage = NoisePropagationStage(),
    val postAwb: NoisePropagationStage = NoisePropagationStage(),
    val postColourTransform: NoisePropagationStage = NoisePropagationStage(),
    val postTone: NoisePropagationStage = NoisePropagationStage(),
    val postVisibleChroma: NoisePropagationStage = NoisePropagationStage(),
    val postQuantization: NoisePropagationStage = NoisePropagationStage(),
    val finalJpeg: NoisePropagationStage = NoisePropagationStage(),
    val measuredPostDemosaic: NoiseResidualObservation = NoiseResidualObservation(),
    val measuredPostColourTransform: NoiseResidualObservation = NoiseResidualObservation(),
    val postDemosaicCalibration: NoiseCalibrationComparison = NoiseCalibrationComparison(),
    val postColourTransformCalibration: NoiseCalibrationComparison = NoiseCalibrationComparison(),
    val calibrationStatus: String = "MILESTONE_2B_NOT_EVALUATED",
    val propagationAwbGainsRgb: List<Double> = listOf(1.0, 1.0, 1.0),
    val propagationColourMatrix: List<Double> = List(9) { 0.0 },
    val toneCurveDerivative: NoiseDerivativeStatistics = NoiseDerivativeStatistics(),
    val sectionCurveDerivative: NoiseDerivativeStatistics = NoiseDerivativeStatistics(),
    val gammaCurveDerivative: NoiseDerivativeStatistics = NoiseDerivativeStatistics(),
    val totalToneDerivative: NoiseDerivativeStatistics = NoiseDerivativeStatistics(),
    val toneChromaScale: NoiseDerivativeStatistics = NoiseDerivativeStatistics(),
    val predictedVisibleChromaAmplification: Double = 1.0,
    val predictedVisibleVarianceY: Double = 0.0,
    val predictedVisibleVarianceRG: Double = 0.0,
    val predictedVisibleVarianceBG: Double = 0.0,
    val predictedVisibleCovarianceRgBg: Double = 0.0,
    val measuredPreSharpenStage: String = "POST_QUANTIZATION_8BIT_PRE_SHARPEN",
    val measuredPreSharpenVarianceY: Double = 0.0,
    val measuredPreSharpenVarianceRG: Double = 0.0,
    val measuredPreSharpenVarianceBG: Double = 0.0,
    val measuredPreSharpenCovarianceRgBg: Double = 0.0,
    val measuredPreSharpenSampleCount: Long = 0,
    val measuredPostIspStage: String = "FINAL_JPEG_PRE_ENCODE_AFTER_SHARPEN",
    val measuredPostIspMethod: String = "CROSS_5_HIGH_PASS_ENERGY_DIVIDED_BY_1_25",
    val measuredPostIspStatus: String =
        "CONTROLLED_SCENE_PROXY_SPATIAL_CORRELATION_AND_TEXTURE_NOT_DECONVOLVED",
    val measuredPostIspFilterEnergyGain: Double = 1.25,
    val measuredPostIspVarianceY: Double = 0.0,
    val measuredPostIspVarianceRG: Double = 0.0,
    val measuredPostIspVarianceBG: Double = 0.0,
    val measuredPostIspCovarianceRgBg: Double = 0.0,
    val measuredPostIspSampleCount: Long = 0,
    val pass2VisibleTargetReady: Boolean = false,
    val pass2VisibleTargetStatus: String = "UNAVAILABLE",
    val pass2VisibleTargetPreventedSkip: Boolean = false,
    val pass2VisibleTargetConfidence: Double = 0.0,
    val pass2VisibleChromaAmplification: Double = 1.0
) {
    fun sanitized(): ResidualNoiseState = copy(
        varianceY = finiteNonNegative(varianceY),
        varianceRG = finiteNonNegative(varianceRG),
        varianceBG = finiteNonNegative(varianceBG),
        covarianceRgBg = finiteOrZero(covarianceRgBg),
        covarianceRgb = covarianceRgb.fixedFiniteArray(9),
        highFrequencyBudget = finiteNonNegative(highFrequencyBudget),
        midFrequencyBudget = finiteNonNegative(midFrequencyBudget),
        lowFrequencyBudget = finiteNonNegative(lowFrequencyBudget),
        correlationLength = finiteNonNegative(correlationLength),
        directionalPatternEnergy = finiteNonNegative(directionalPatternEnergy),
        rowPatternEnergy = finiteNonNegative(rowPatternEnergy),
        columnPatternEnergy = finiteNonNegative(columnPatternEnergy),
        temporalCorrelation = finiteOrZero(temporalCorrelation).coerceIn(0.0, 1.0),
        independentNoiseFraction = finiteOrZero(independentNoiseFraction).coerceIn(0.0, 1.0),
        effectiveFrameCount = max(1.0, finiteOrZero(effectiveFrameCount)),
        modelConfidence = finiteOrZero(modelConfidence).coerceIn(0.0, 1.0),
        motionConfidence = finiteOrZero(motionConfidence).coerceIn(0.0, 1.0),
        alignmentConfidence = finiteOrZero(alignmentConfidence).coerceIn(0.0, 1.0),
        preDemosaic = preDemosaic.sanitized(),
        postDemosaic = postDemosaic.sanitized(),
        postAwb = postAwb.sanitized(),
        postColourTransform = postColourTransform.sanitized(),
        postTone = postTone.sanitized(),
        postVisibleChroma = postVisibleChroma.sanitized(),
        postQuantization = postQuantization.sanitized(),
        finalJpeg = finalJpeg.sanitized(),
        measuredPostDemosaic = measuredPostDemosaic.sanitized(),
        measuredPostColourTransform = measuredPostColourTransform.sanitized(),
        postDemosaicCalibration = postDemosaicCalibration.sanitized(),
        postColourTransformCalibration = postColourTransformCalibration.sanitized(),
        propagationAwbGainsRgb = propagationAwbGainsRgb.fixedFiniteArray(3),
        propagationColourMatrix = propagationColourMatrix.fixedFiniteArray(9),
        toneCurveDerivative = toneCurveDerivative.sanitized(),
        sectionCurveDerivative = sectionCurveDerivative.sanitized(),
        gammaCurveDerivative = gammaCurveDerivative.sanitized(),
        totalToneDerivative = totalToneDerivative.sanitized(),
        toneChromaScale = toneChromaScale.sanitized(),
        predictedVisibleChromaAmplification = finiteNonNegative(predictedVisibleChromaAmplification),
        predictedVisibleVarianceY = finiteNonNegative(predictedVisibleVarianceY),
        predictedVisibleVarianceRG = finiteNonNegative(predictedVisibleVarianceRG),
        predictedVisibleVarianceBG = finiteNonNegative(predictedVisibleVarianceBG),
        predictedVisibleCovarianceRgBg = finiteOrZero(predictedVisibleCovarianceRgBg),
        measuredPreSharpenVarianceY = finiteNonNegative(measuredPreSharpenVarianceY),
        measuredPreSharpenVarianceRG = finiteNonNegative(measuredPreSharpenVarianceRG),
        measuredPreSharpenVarianceBG = finiteNonNegative(measuredPreSharpenVarianceBG),
        measuredPreSharpenCovarianceRgBg = finiteOrZero(measuredPreSharpenCovarianceRgBg),
        measuredPreSharpenSampleCount = measuredPreSharpenSampleCount.coerceAtLeast(0),
        measuredPostIspFilterEnergyGain = max(1.0, finiteNonNegative(measuredPostIspFilterEnergyGain)),
        measuredPostIspVarianceY = finiteNonNegative(measuredPostIspVarianceY),
        measuredPostIspVarianceRG = finiteNonNegative(measuredPostIspVarianceRG),
        measuredPostIspVarianceBG = finiteNonNegative(measuredPostIspVarianceBG),
        measuredPostIspCovarianceRgBg = finiteOrZero(measuredPostIspCovarianceRgBg),
        measuredPostIspSampleCount = measuredPostIspSampleCount.coerceAtLeast(0),
        pass2VisibleTargetConfidence = finiteOrZero(pass2VisibleTargetConfidence).coerceIn(0.0, 1.0),
        pass2VisibleChromaAmplification = finiteNonNegative(pass2VisibleChromaAmplification)
    )

    fun toTraceMap(): Map<String, Any> = sanitized().let { state ->
        linkedMapOf(
            "available" to state.available,
            "domain" to state.domain,
            "valueStage" to state.valueStage,
            "lastObservedStage" to state.lastObservedStage,
            "propagationStatus" to state.propagationStatus,
            "opponentVarianceStatus" to state.opponentVarianceStatus,
            "covarianceStatus" to state.covarianceStatus,
            "comparabilityStatus" to state.comparabilityStatus,
            "varianceY" to state.varianceY,
            "varianceRG" to state.varianceRG,
            "varianceBG" to state.varianceBG,
            "covarianceRG_BG" to state.covarianceRgBg,
            "covarianceRGB" to state.covarianceRgb,
            "highFrequencyBudget" to state.highFrequencyBudget,
            "midFrequencyBudget" to state.midFrequencyBudget,
            "lowFrequencyBudget" to state.lowFrequencyBudget,
            "correlationLength" to state.correlationLength,
            "directionalPatternEnergy" to state.directionalPatternEnergy,
            "rowPatternEnergy" to state.rowPatternEnergy,
            "columnPatternEnergy" to state.columnPatternEnergy,
            "temporalCorrelation" to state.temporalCorrelation,
            "independentNoiseFraction" to state.independentNoiseFraction,
            "effectiveFrameCount" to state.effectiveFrameCount,
            "modelConfidence" to state.modelConfidence,
            "motionConfidence" to state.motionConfidence,
            "alignmentConfidence" to state.alignmentConfidence,
            "stages" to linkedMapOf(
                "preDemosaic" to state.preDemosaic.toTraceMap(),
                "postDemosaic" to state.postDemosaic.toTraceMap(),
                "postAwb" to state.postAwb.toTraceMap(),
                "postColourTransform" to state.postColourTransform.toTraceMap(),
                "postTone" to state.postTone.toTraceMap(),
                "postVisibleChroma" to state.postVisibleChroma.toTraceMap(),
                "postQuantization" to state.postQuantization.toTraceMap(),
                "finalJpeg" to state.finalJpeg.toTraceMap()
            ),
            "transitions" to linkedMapOf(
                "demosaic" to transitionTraceMap(state.preDemosaic, state.postDemosaic),
                "awb" to transitionTraceMap(state.postDemosaic, state.postAwb),
                "colourTransform" to transitionTraceMap(
                    state.postAwb,
                    state.postColourTransform
                ),
                "toneAndCurves" to transitionTraceMap(
                    state.postColourTransform,
                    state.postTone
                ),
                "visibleChroma" to transitionTraceMap(
                    state.postTone,
                    state.postVisibleChroma
                ),
                "quantization" to transitionTraceMap(
                    state.postVisibleChroma,
                    state.postQuantization
                ),
                "noiseAwareSharpen" to transitionTraceMap(
                    state.postQuantization,
                    state.finalJpeg
                )
            ),
            "calibration" to linkedMapOf(
                "status" to state.calibrationStatus,
                "identity" to linkedMapOf(
                    "awbGainsRgb" to state.propagationAwbGainsRgb,
                    "colourMatrix" to state.propagationColourMatrix
                ),
                "observations" to linkedMapOf(
                    "postDemosaic" to state.measuredPostDemosaic.toTraceMap(),
                    "postColourTransform" to state.measuredPostColourTransform.toTraceMap()
                ),
                "comparisons" to linkedMapOf(
                    "postDemosaic" to state.postDemosaicCalibration.toTraceMap(),
                    "postColourTransform" to state.postColourTransformCalibration.toTraceMap()
                ),
                "coefficientUpdate" to "DISABLED_OBSERVATION_ONLY"
            ),
            "curveDerivatives" to linkedMapOf(
                "tone" to state.toneCurveDerivative.toTraceMap(),
                "section" to state.sectionCurveDerivative.toTraceMap(),
                "gamma" to state.gammaCurveDerivative.toTraceMap(),
                "totalTone" to state.totalToneDerivative.toTraceMap(),
                "toneChromaScale" to state.toneChromaScale.toTraceMap()
            ),
            "visiblePrediction" to linkedMapOf(
                "chromaAmplification" to state.predictedVisibleChromaAmplification,
                "varianceY" to state.predictedVisibleVarianceY,
                "varianceRG" to state.predictedVisibleVarianceRG,
                "varianceBG" to state.predictedVisibleVarianceBG,
                "covarianceRG_BG" to state.predictedVisibleCovarianceRgBg,
                "stage" to state.postTone.stage
            ),
            "preSharpenMeasurement" to linkedMapOf(
                "stage" to state.measuredPreSharpenStage,
                "method" to state.measuredPostIspMethod,
                "status" to state.measuredPostIspStatus,
                "varianceY" to state.measuredPreSharpenVarianceY,
                "varianceRG" to state.measuredPreSharpenVarianceRG,
                "varianceBG" to state.measuredPreSharpenVarianceBG,
                "covarianceRG_BG" to state.measuredPreSharpenCovarianceRgBg,
                "sampleCount" to state.measuredPreSharpenSampleCount
            ),
            "visibleMeasurement" to linkedMapOf(
                "stage" to state.measuredPostIspStage,
                "method" to state.measuredPostIspMethod,
                "status" to state.measuredPostIspStatus,
                "filterEnergyGain" to state.measuredPostIspFilterEnergyGain,
                "varianceY" to state.measuredPostIspVarianceY,
                "varianceRG" to state.measuredPostIspVarianceRG,
                "varianceBG" to state.measuredPostIspVarianceBG,
                "covarianceRG_BG" to state.measuredPostIspCovarianceRgBg,
                "sampleCount" to state.measuredPostIspSampleCount
            ),
            "pass2VisibleTarget" to linkedMapOf(
                "ready" to state.pass2VisibleTargetReady,
                "status" to state.pass2VisibleTargetStatus,
                "preventedPreDemosaicOnlySkip" to state.pass2VisibleTargetPreventedSkip,
                "confidence" to state.pass2VisibleTargetConfidence,
                "chromaAmplification" to state.pass2VisibleChromaAmplification
            )
        )
    }

    companion object {
        fun fromNativeStats(stats: Map<String, String>): ResidualNoiseState = ResidualNoiseState(
            available = stats.keys.any { it.startsWith("spectraResidual") || it.startsWith("spectraPostTone") },
            domain = stats["spectraResidualDomain"] ?: "POST_TONE_RGB",
            valueStage = stats["spectraResidualValueStage"] ?: "POST_TONE_PRE_FINAL_NR_SHARPEN",
            lastObservedStage = stats["spectraResidualLastObservedStage"] ?: "PRE_JPEG_ENCODE_8BIT",
            propagationStatus = stats["spectraResidualPropagationStatus"]
                ?: "MILESTONE_2_PROPAGATED_THROUGH_TONE",
            opponentVarianceStatus = stats["spectraResidualOpponentVarianceStatus"]
                ?: "DERIVED_FROM_RGB_COVARIANCE",
            covarianceStatus = stats["spectraResidualCovarianceStatus"]
                ?: "SYMMETRIC_PSD_BOUNDED",
            comparabilityStatus = stats["spectraResidualComparabilityStatus"]
                ?: "HIGHLIGHT_RECOVERY_VIBRANCE_COLOR_MANAGEMENT_NR_SHARPEN_QUANTIZATION_NOT_PROPAGATED",
            varianceY = stats.double("spectraResidualVarianceY"),
            varianceRG = stats.double("spectraResidualVarianceRG"),
            varianceBG = stats.double("spectraResidualVarianceBG"),
            covarianceRgBg = stats.double("spectraResidualCovarianceRgBg"),
            covarianceRgb = parseArray(stats["spectraResidualCovarianceRgb"], expected = 9),
            highFrequencyBudget = stats.double("spectraResidualHighFrequencyBudget"),
            midFrequencyBudget = stats.double("spectraResidualMidFrequencyBudget"),
            lowFrequencyBudget = stats.double("spectraResidualLowFrequencyBudget"),
            correlationLength = stats.double("spectraResidualCorrelationLength"),
            directionalPatternEnergy = stats.double("spectraResidualDirectionalPatternEnergy"),
            rowPatternEnergy = stats.double("spectraResidualRowPatternEnergy"),
            columnPatternEnergy = stats.double("spectraResidualColumnPatternEnergy"),
            temporalCorrelation = stats.preferredDouble(
                primary = "spectraTemporalCorrelation",
                fallback = "spectraResidualTemporalCorrelation"
            ),
            independentNoiseFraction = stats.preferredDouble(
                primary = "spectraIndependentNoiseFraction",
                fallback = "spectraResidualIndependentNoiseFraction",
                default = 1.0
            ),
            effectiveFrameCount = stats.preferredDouble(
                primary = "spectraEffectiveFrameCount",
                fallback = "spectraResidualEffectiveFrameCount",
                default = 1.0
            ),
            modelConfidence = stats.double("spectraResidualModelConfidence"),
            motionConfidence = stats.preferredDouble(
                primary = "spectraMotionConfidence",
                fallback = "spectraResidualMotionConfidence"
            ),
            alignmentConfidence = stats.preferredDouble(
                primary = "spectraAlignmentConfidence",
                fallback = "spectraResidualAlignmentConfidence"
            ),
            preDemosaic = NoisePropagationStage.fromNativeStats("spectraPreDemosaic", stats),
            postDemosaic = NoisePropagationStage.fromNativeStats("spectraPostDemosaic", stats),
            postAwb = NoisePropagationStage.fromNativeStats("spectraPostAwb", stats),
            postColourTransform = NoisePropagationStage.fromNativeStats(
                "spectraPostColourTransform",
                stats
            ),
            postTone = NoisePropagationStage.fromNativeStats("spectraPostTone", stats),
            postVisibleChroma = NoisePropagationStage.fromNativeStats(
                "spectraPostVisibleChroma",
                stats
            ),
            postQuantization = NoisePropagationStage.fromNativeStats(
                "spectraPostQuantization",
                stats
            ),
            finalJpeg = NoisePropagationStage.fromNativeStats(
                "spectraFinalJpeg",
                stats
            ),
            measuredPostDemosaic = NoiseResidualObservation.fromNativeStats(
                "spectraMeasuredPostDemosaic",
                stats
            ),
            measuredPostColourTransform = NoiseResidualObservation.fromNativeStats(
                "spectraMeasuredPostColourTransform",
                stats
            ),
            postDemosaicCalibration = NoiseCalibrationComparison.fromNativeStats(
                "spectraPostDemosaicCalibration",
                stats
            ),
            postColourTransformCalibration = NoiseCalibrationComparison.fromNativeStats(
                "spectraPostColourTransformCalibration",
                stats
            ),
            calibrationStatus = stats["spectraCalibrationStatus"]
                ?: "MILESTONE_2B_NOT_EVALUATED",
            propagationAwbGainsRgb = parseArray(
                stats["spectraPropagationAwbGainsRgb"],
                expected = 3
            ),
            propagationColourMatrix = parseArray(
                stats["spectraPropagationColourMatrix"],
                expected = 9
            ),
            toneCurveDerivative = NoiseDerivativeStatistics.fromNativeStats(
                "spectraToneCurveDerivative",
                stats
            ),
            sectionCurveDerivative = NoiseDerivativeStatistics.fromNativeStats(
                "spectraSectionCurveDerivative",
                stats
            ),
            gammaCurveDerivative = NoiseDerivativeStatistics.fromNativeStats(
                "spectraGammaCurveDerivative",
                stats
            ),
            totalToneDerivative = NoiseDerivativeStatistics.fromNativeStats(
                "spectraTotalToneDerivative",
                stats
            ),
            toneChromaScale = NoiseDerivativeStatistics.fromNativeStats(
                "spectraToneChromaScale",
                stats
            ),
            predictedVisibleChromaAmplification = stats.double(
                "spectraPredictedVisibleChromaAmplification",
                default = 1.0
            ),
            predictedVisibleVarianceY = stats.double("spectraPredictedVisibleVarianceY"),
            predictedVisibleVarianceRG = stats.double("spectraPredictedVisibleVarianceRG"),
            predictedVisibleVarianceBG = stats.double("spectraPredictedVisibleVarianceBG"),
            predictedVisibleCovarianceRgBg = stats.double(
                "spectraPredictedVisibleCovarianceRgBg"
            ),
            measuredPreSharpenStage = stats["spectraMeasuredPreSharpenStage"]
                ?: "POST_QUANTIZATION_8BIT_PRE_SHARPEN",
            measuredPreSharpenVarianceY = stats.double("spectraMeasuredPreSharpenVarianceY"),
            measuredPreSharpenVarianceRG = stats.double("spectraMeasuredPreSharpenVarianceRG"),
            measuredPreSharpenVarianceBG = stats.double("spectraMeasuredPreSharpenVarianceBG"),
            measuredPreSharpenCovarianceRgBg = stats.double(
                "spectraMeasuredPreSharpenCovarianceRgBg"
            ),
            measuredPreSharpenSampleCount = stats.long("spectraMeasuredPreSharpenSampleCount"),
            measuredPostIspStage = stats["spectraMeasuredPostIspStage"]
                ?: "FINAL_JPEG_PRE_ENCODE_AFTER_SHARPEN",
            measuredPostIspMethod = stats["spectraMeasuredPostIspMethod"]
                ?: "CROSS_5_HIGH_PASS_ENERGY_DIVIDED_BY_1_25",
            measuredPostIspStatus = stats["spectraMeasuredPostIspStatus"]
                ?: "CONTROLLED_SCENE_PROXY_SPATIAL_CORRELATION_AND_TEXTURE_NOT_DECONVOLVED",
            measuredPostIspFilterEnergyGain = stats.double(
                "spectraMeasuredPostIspFilterEnergyGain",
                default = 1.25
            ),
            measuredPostIspVarianceY = stats.double("spectraMeasuredPostIspVarianceY"),
            measuredPostIspVarianceRG = stats.double("spectraMeasuredPostIspVarianceRG"),
            measuredPostIspVarianceBG = stats.double("spectraMeasuredPostIspVarianceBG"),
            measuredPostIspCovarianceRgBg = stats.double(
                "spectraMeasuredPostIspCovarianceRgBg"
            ),
            measuredPostIspSampleCount = stats.long("spectraMeasuredPostIspSampleCount"),
            pass2VisibleTargetReady = stats.boolean("spectraPass2VisibleTargetReady"),
            pass2VisibleTargetStatus = stats["spectraPass2VisibleTargetStatus"] ?: "UNAVAILABLE",
            pass2VisibleTargetPreventedSkip = stats.boolean(
                "spectraPass2VisibleTargetPreventedSkip"
            ),
            pass2VisibleTargetConfidence = stats.double("spectraPass2VisibleTargetConfidence"),
            pass2VisibleChromaAmplification = stats.double(
                "spectraPass2VisibleChromaAmplification",
                default = 1.0
            )
        ).sanitized()
    }
}

private fun transitionTraceMap(
    input: NoisePropagationStage,
    output: NoisePropagationStage
): Map<String, Any> {
    fun metric(inputValue: Double, outputValue: Double): Map<String, Any> {
        val safeInput = finiteNonNegative(inputValue)
        val safeOutput = finiteNonNegative(outputValue)
        val inputAvailable = safeInput > 1.0e-18
        val amplification = if (inputAvailable) safeOutput / safeInput else 0.0
        val percentageChange = if (inputAvailable) (amplification - 1.0) * 100.0 else 0.0
        return linkedMapOf(
            "input" to safeInput,
            "output" to safeOutput,
            "amplification" to finiteNonNegative(amplification),
            "percentageChange" to finiteOrZero(percentageChange),
            "status" to if (inputAvailable) "AVAILABLE" else "INPUT_VARIANCE_ZERO"
        )
    }

    return linkedMapOf(
        "inputStage" to input.stage,
        "outputStage" to output.stage,
        "inputConfidence" to input.confidence,
        "outputConfidence" to output.confidence,
        "varianceY" to metric(input.varianceY, output.varianceY),
        "varianceRG" to metric(input.varianceRG, output.varianceRG),
        "varianceBG" to metric(input.varianceBG, output.varianceBG),
        "covarianceRG_BG" to linkedMapOf(
            "input" to finiteOrZero(input.covarianceRgBg),
            "output" to finiteOrZero(output.covarianceRgBg),
            "delta" to finiteOrZero(output.covarianceRgBg - input.covarianceRgBg)
        )
    )
}

private fun parseArray(value: String?, expected: Int): List<Double> {
    val parsed = value
        ?.removePrefix("[")
        ?.removeSuffix("]")
        ?.split(',')
        ?.mapNotNull { it.trim().toDoubleOrNull()?.takeIf(Double::isFinite) }
        .orEmpty()
    return parsed.take(expected) + List((expected - parsed.size).coerceAtLeast(0)) { 0.0 }
}

private fun List<Double>.fixedFiniteArray(expected: Int): List<Double> =
    take(expected).map(::finiteOrZero).let { values ->
        if (values.size == expected) values else values + List(expected - values.size) { 0.0 }
    }

private fun Map<String, String>.double(key: String, default: Double = 0.0): Double =
    this[key]?.toDoubleOrNull()?.takeIf(Double::isFinite) ?: default

private fun Map<String, String>.long(key: String, default: Long = 0L): Long =
    this[key]?.toLongOrNull() ?: default

private fun Map<String, String>.boolean(key: String): Boolean =
    this[key].equals("true", ignoreCase = true) ||
        this[key].equals("yes", ignoreCase = true)

private fun finiteOrZero(value: Double): Double = if (value.isFinite()) value else 0.0
private fun finiteNonNegative(value: Double): Double = max(0.0, finiteOrZero(value))

private fun Map<String, String>.preferredDouble(
    primary: String,
    fallback: String,
    default: Double = 0.0
): Double = this[primary]?.toDoubleOrNull()?.takeIf(Double::isFinite)
    ?: this[fallback]?.toDoubleOrNull()?.takeIf(Double::isFinite)
    ?: default
