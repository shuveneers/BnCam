package com.bncam.core.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResidualNoiseStateTest {
    @Test
    fun nativeStatsProduceMilestoneTwoPropagationContract() {
        val state = ResidualNoiseState.fromNativeStats(
            mapOf(
                "spectraResidualDomain" to "POST_TONE_RGB",
                "spectraResidualVarianceY" to "0.002",
                "spectraResidualVarianceRG" to "0.003",
                "spectraResidualVarianceBG" to "0.004",
                "spectraResidualCovarianceRgb" to "[1,2,3,4,5,6,7,8,9]",
                "spectraPostDemosaicStage" to "POST_DEMOSAIC_RGB",
                "spectraPostDemosaicMethod" to "MALVAR_2004_PHASE_AVERAGED_KERNEL_ENERGY",
                "spectraPostDemosaicStatus" to "PROPAGATED",
                "spectraPostDemosaicConfidence" to "0.81",
                "spectraPostDemosaicVarianceY" to "0.005",
                "spectraPostDemosaicVarianceRG" to "0.006",
                "spectraPostDemosaicVarianceBG" to "0.007",
                "spectraPostDemosaicCovarianceRgb" to "[1,0,0,0,2,0,0,0,3]",
                "spectraTotalToneDerivativeSampleCount" to "100",
                "spectraTotalToneDerivativeRms" to "1.8",
                "spectraMeasuredPostDemosaicStage" to "POST_DEMOSAIC_RGB",
                "spectraMeasuredPostDemosaicStatus" to
                    "OBSERVATION_READY_NO_AUTO_CALIBRATION",
                "spectraMeasuredPostDemosaicConfidence" to "0.7",
                "spectraMeasuredPostDemosaicRobustVarianceRG" to "0.006",
                "spectraMeasuredPostDemosaicAcceptedSampleCount" to "12000",
                "spectraMeasuredPostDemosaicValidTileCount" to "48",
                "spectraPostDemosaicCalibrationReady" to "true",
                "spectraPostDemosaicCalibrationStatus" to
                    "READY_OBSERVATION_ONLY_NO_COEFFICIENT_UPDATE",
                "spectraPostDemosaicCalibrationMeasuredToPredictedChroma" to "1.4",
                "spectraCalibrationStatus" to
                    "MILESTONE_2B_STAGE_OBSERVATIONS_READY_NO_AUTO_CALIBRATION",
                "spectraPropagationAwbGainsRgb" to "[2.0,1.0,1.8]",
                "spectraPropagationColourMatrix" to "[1,0,0,0,1,0,0,0,1]",
                "spectraPredictedVisibleChromaAmplification" to "2.1",
                "spectraMeasuredPostIspMethod" to "CROSS_5_HIGH_PASS_ENERGY_DIVIDED_BY_1_25",
                "spectraMeasuredPostIspStatus" to
                    "CONTROLLED_SCENE_PROXY_SPATIAL_CORRELATION_AND_TEXTURE_NOT_DECONVOLVED",
                "spectraMeasuredPostIspFilterEnergyGain" to "1.25",
                "spectraMeasuredPostIspVarianceRG" to "0.008",
                "spectraMeasuredPostIspVarianceBG" to "0.009",
                "spectraMeasuredPostIspSampleCount" to "900",
                "spectraPass2VisibleTargetReady" to "true",
                "spectraPass2VisibleTargetStatus" to
                    "OBSERVATION_ONLY_NO_SKIP_AUTHORITY_MILESTONE_2",
                "spectraPass2VisibleTargetPreventedSkip" to "true",
                "spectraResidualTemporalCorrelation" to "1.5",
                "spectraResidualIndependentNoiseFraction" to "-0.5",
                "spectraResidualEffectiveFrameCount" to "0"
            )
        )

        assertTrue(state.available)
        assertEquals("POST_TONE_RGB", state.domain)
        assertEquals("POST_TONE_PRE_FINAL_NR_SHARPEN", state.valueStage)
        assertEquals("MILESTONE_2_PROPAGATED_THROUGH_TONE", state.propagationStatus)
        assertEquals(0.002, state.varianceY, 0.0)
        assertEquals(9, state.covarianceRgb.size)
        assertEquals("DERIVED_FROM_RGB_COVARIANCE", state.opponentVarianceStatus)
        assertEquals("SYMMETRIC_PSD_BOUNDED", state.covarianceStatus)
        assertEquals("POST_DEMOSAIC_RGB", state.postDemosaic.stage)
        assertEquals(0.81, state.postDemosaic.confidence, 0.0)
        assertEquals(1.8, state.totalToneDerivative.rms, 0.0)
        assertEquals("POST_DEMOSAIC_RGB", state.measuredPostDemosaic.stage)
        assertEquals(0.006, state.measuredPostDemosaic.robustVarianceRG, 0.0)
        assertEquals(12000L, state.measuredPostDemosaic.acceptedSampleCount)
        assertTrue(state.postDemosaicCalibration.ready)
        assertEquals(1.4, state.postDemosaicCalibration.measuredToPredictedChroma, 0.0)
        assertEquals(3, state.propagationAwbGainsRgb.size)
        assertEquals(9, state.propagationColourMatrix.size)
        assertEquals(2.1, state.predictedVisibleChromaAmplification, 0.0)
        assertEquals("CROSS_5_HIGH_PASS_ENERGY_DIVIDED_BY_1_25", state.measuredPostIspMethod)
        assertEquals(1.25, state.measuredPostIspFilterEnergyGain, 0.0)
        assertEquals(900L, state.measuredPostIspSampleCount)
        assertTrue(state.pass2VisibleTargetReady)
        assertEquals(
            "OBSERVATION_ONLY_NO_SKIP_AUTHORITY_MILESTONE_2",
            state.pass2VisibleTargetStatus
        )
        assertTrue(state.pass2VisibleTargetPreventedSkip)
        assertEquals(1.0, state.temporalCorrelation, 0.0)
        assertEquals(0.0, state.independentNoiseFraction, 0.0)
        assertEquals(1.0, state.effectiveFrameCount, 0.0)
        assertTrue(state.toTraceMap().containsKey("stages"))
        assertTrue(state.toTraceMap().containsKey("transitions"))
        assertTrue(state.toTraceMap().containsKey("visibleMeasurement"))
        assertTrue(state.toTraceMap().containsKey("calibration"))
    }

    @Test
    fun nonFiniteAndNegativeValuesCannotEscapeContract() {
        val state = ResidualNoiseState(
            varianceY = Double.NaN,
            varianceRG = -1.0,
            covarianceRgBg = Double.POSITIVE_INFINITY,
            predictedVisibleChromaAmplification = -4.0,
            measuredPostIspVarianceRG = Double.NaN,
            postTone = NoisePropagationStage(
                confidence = 4.0,
                varianceY = -1.0,
                covarianceRgb = listOf(Double.NaN)
            )
        ).sanitized()

        assertEquals(0.0, state.varianceY, 0.0)
        assertEquals(0.0, state.varianceRG, 0.0)
        assertEquals(0.0, state.covarianceRgBg, 0.0)
        assertEquals(0.0, state.predictedVisibleChromaAmplification, 0.0)
        assertEquals(0.0, state.measuredPostIspVarianceRG, 0.0)
        assertEquals(1.0, state.postTone.confidence, 0.0)
        assertEquals(0.0, state.postTone.varianceY, 0.0)
        assertEquals(9, state.postTone.covarianceRgb.size)
    }
}
