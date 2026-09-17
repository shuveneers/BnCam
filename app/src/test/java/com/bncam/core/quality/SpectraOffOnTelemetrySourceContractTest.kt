package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectraOffOnTelemetrySourceContractTest {
    @Test
    fun `physical model and neural mutation are logged as independent truths`() {
        val isp = File("src/main/cpp/IspCore.cpp").readText()

        assertTrue(isp.contains("; physicalNoiseModelAvailable="))
        assertTrue(isp.contains("; physicalNoiseJniPayloadReceived="))
        assertTrue(isp.contains("; physicalNoiseUsedForNeuralConditioning="))
        assertTrue(isp.contains("; spectraNeuralConditioningValid="))
        assertTrue(isp.contains("; phase8PhysicalNoiseStatisticsActive="))
        assertTrue(isp.contains("; phase8NoisePropagationActive="))
        assertTrue(isp.contains("; phase8NeuralDenoiseEnabled="))
        assertTrue(isp.contains("; phase8NeuralPixelMutation="))
        assertTrue(isp.contains("; phase8NeuralBypassReason="))
        assertTrue(isp.contains("NeuralBypassReason::UserDisabled"))
        assertTrue(isp.contains("EXACT_USER_DISABLED_IDENTITY"))
        assertTrue(!isp.contains("; physicalNoiseModelPixelAuthority=false"))
    }
}
