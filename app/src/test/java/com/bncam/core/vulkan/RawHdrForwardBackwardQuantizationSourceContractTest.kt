package com.bncam.core.vulkan

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class RawHdrForwardBackwardQuantizationSourceContractTest {
    private val backend = File("src/main/cpp/vulkan/VulkanRawMultiFrameBackend.cpp").readText()
    private val fusion = File("src/main/cpp/SpectraTemporalFusion.h").readText()
    private val merger = File("src/main/cpp/DngMerger.cpp").readText()

    @Test
    fun rawHdrUsesQuantizationAwareForwardBackwardConsistencyWithoutLoweringGate() {
        assertTrue(backend.contains("fwd.response,rev.response,1.5"))
        assertTrue(backend.contains("request.computationalHdr ? 0.12f : 0.08f"))
        assertTrue(fusion.contains("closureSigmaPixels = 0.85"))
        assertTrue(fusion.contains("safeSigma"))
    }

    @Test
    fun rejectionTelemetryExposesClosureEvidence() {
        assertTrue(merger.contains("closureErrorPx="))
        assertTrue(merger.contains("reverseResponse="))
        assertTrue(backend.contains("forwardBackwardClosureErrorPixels"))
    }
}
