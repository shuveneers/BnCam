package com.bncam.core.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectraChromaBandsTest {
    @Test
    fun nativeBandStatsProduceStableTraceContract() {
        val trace = SpectraChromaBandsTrace.fromNativeStats(
            mapOf(
                "spectraChromaBandsArchitecture" to
                    "SPECTRA_CONTEXT_FUSION_MULTISCALE_CFA_CHROMA",
                "spectraChromaBandEnergyMethod" to
                    "R_MINUS_GREF_B_MINUS_GREF_SAME_COLOUR_RADII_2_4_8_STRIDE16_SAMPLED",
                "spectraChromaBandEnergyInputStatus" to
                    "AVAILABLE_SAMPLED_PRE_DEMOSAIC_PROXY",
                "spectraChromaBandEnergyInputSampleCount" to "2048",
                "spectraChromaBandEnergyInputRedSampleCount" to "1024",
                "spectraChromaBandEnergyInputBlueSampleCount" to "1024",
                "spectraChromaBandEnergyInputRedBlueSampleBalance" to "1.0",
                "spectraChromaBandEnergyInputConfidence" to "0.9",
                "spectraChromaFineName" to "FINE",
                "spectraChromaFineEnabled" to "true",
                "spectraChromaFineApplied" to "true",
                "spectraChromaFineAuthorityScale" to "0.91",
                "spectraChromaFineMaximumCorrectionScale" to "0.95",
                "spectraChromaFineInputEnergy" to "0.004",
                "spectraChromaFineOutputEnergy" to "0.003",
                "spectraChromaFineRuntimeMethod" to
                    "GREEN_GUIDED_FINE_WIENER_SHRINKAGE_RADIUS2",
                "spectraChromaFineReductionPercentage" to "25",
                "spectraChromaFineCoefficientEnergyBefore" to "0.0004",
                "spectraChromaFineCoefficientEnergyAfter" to "0.0003",
                "spectraChromaFineCandidatePixelCount" to "1200",
                "spectraChromaFineChangedPixelCount" to "600",
                "spectraChromaFineStructureRejectedPixelCount" to "100",
                "spectraChromaFineMeanNoiseSigma" to "0.002",
                "spectraChromaFineMeanStructureWeight" to "0.8",
                "spectraChromaFineMeanShrinkage" to "0.3",
                "spectraChromaFineShrinkageP90" to "0.6",
                "spectraChromaFineMaximumShrinkage" to "0.9",
                "spectraChromaMidName" to "MID",
                "spectraChromaMidEnabled" to "false",
                "spectraChromaLowName" to "LOW",
                "spectraChromaLowEvidence" to "0.4"
            )
        )

        assertEquals("SPECTRA_CONTEXT_FUSION_MULTISCALE_CFA_CHROMA", trace.architecture)
        assertEquals(2048L, trace.inputSampleCount)
        assertEquals(1024L, trace.inputRedSampleCount)
        assertEquals(1024L, trace.inputBlueSampleCount)
        assertEquals(1.0, trace.inputRedBlueSampleBalance, 0.0)
        assertTrue(trace.fine.enabled)
        assertTrue(trace.fine.applied)
        assertEquals(0.91, trace.fine.authorityScale, 0.0)
        assertEquals(25.0, trace.fine.reductionPercentage, 0.0)
        assertEquals("GREEN_GUIDED_FINE_WIENER_SHRINKAGE_RADIUS2", trace.fine.runtimeMethod)
        assertEquals(1200L, trace.fine.candidatePixelCount)
        assertEquals(600L, trace.fine.changedPixelCount)
        assertEquals(0.3, trace.fine.meanShrinkage, 0.0)
        assertEquals(0.6, trace.fine.shrinkageP90, 0.0)
        assertFalse(trace.mid.enabled)
        assertEquals(0.4, trace.low.evidence, 0.0)
        assertTrue(trace.toTraceMap().containsKey("fine"))
        assertTrue(trace.toTraceMap().containsKey("mid"))
        assertTrue(trace.toTraceMap().containsKey("low"))
    }

    @Test
    fun unsafeNativeValuesAreBounded() {
        val band = SpectraChromaBandTrace(
            authorityScale = 4.0,
            maximumCorrectionScale = Double.NaN,
            inputEnergy = -1.0,
            changedPixelFraction = -0.5,
            candidatePixelCount = -10,
            meanShrinkage = 5.0,
            shrinkageP90 = Double.NaN,
            noRegretMeanAcceptance = 2.0
        ).sanitized()

        assertEquals(1.0, band.authorityScale, 0.0)
        assertEquals(0.0, band.maximumCorrectionScale, 0.0)
        assertEquals(0.0, band.inputEnergy, 0.0)
        assertEquals(0.0, band.changedPixelFraction, 0.0)
        assertEquals(0L, band.candidatePixelCount)
        assertEquals(1.0, band.meanShrinkage, 0.0)
        assertEquals(0.0, band.shrinkageP90, 0.0)
        assertEquals(1.0, band.noRegretMeanAcceptance, 0.0)
    }
}
