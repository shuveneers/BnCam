package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectraOffOnTruthSourceContractTest {
    @Test
    fun `neural off is identity while physical noise truth remains active`() {
        val root = File(System.getProperty("user.dir"))
        val policy = File(root, "src/main/cpp/SpectraNeuralProductionPolicy.h").readText()
        val invocation = File(root, "src/main/cpp/NeuralRawDenoisePolicy.h").readText()
        val isp = File(root, "src/main/cpp/IspCore.cpp").readText()

        // Off is a binary mutation bypass, not a weaker denoise setting.
        assertTrue(policy.contains("mode != NeuralProductionMutationMode::Off"))
        assertTrue(policy.contains("return out;"))
        assertTrue(invocation.contains("NeuralBypassReason::UserDisabled"))
        assertTrue(invocation.contains("return \"user_disabled\""))

        // The resident Off path never enters Neural preparation/backend execution.
        assertTrue(isp.contains("meta.calibration.spectraProcessingMode == 0"))
        assertTrue(isp.contains("EXACT_USER_DISABLED_IDENTITY"))
        assertTrue(isp.contains("executeSpectraRawFinalizeFromRawNormalize"))

        // Physical statistics/propagation use physical availability, not Neural enable state.
        assertTrue(isp.contains("const bool physicalNoiseStatisticsActive = frozenPhysicalNoise.available"))
        assertTrue(isp.contains("physicalNoiseStatisticsActive || profilePerceptualDetailRequested"))
        assertFalse(isp.contains("const bool spectraNoiseActive = meta.calibration.spectraProcessingMode != 0"))
        assertFalse(isp.contains("SKIPPED_SPECTRA_OFF"))
    }
}
