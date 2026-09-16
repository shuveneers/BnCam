package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase3PhysicalNoiseNeuralConditioningSourceContractTest {
    @Test
    fun `neural conditioning consumes only frozen physical so`() {
        val nativeConfig = File("src/main/cpp/NativeRenderQualityConfig.h").readText()
        val isp = File("src/main/cpp/IspCore.cpp").readText()
        val policy = File("src/main/cpp/SpectraNeuralProductionPolicy.h").readText()
        val backend = File("src/main/cpp/vulkan/VulkanNeuralRawDenoiseBackend.cpp").readText()

        assertTrue(nativeConfig.contains("FrozenPhysicalNoiseModelNative frozenPhysicalNoiseModel() const noexcept"))
        assertTrue(nativeConfig.contains("if (!physicalNoiseModelAvailable()) return out"))
        assertTrue(nativeConfig.contains("out.shotS[ch] = static_cast<float>(effectiveS[ch])"))
        assertTrue(nativeConfig.contains("out.readO[ch] = static_cast<float>(effectiveO[ch])"))

        val finalizeStart = isp.indexOf("bncam::vulkan::SpectraRawFinalizeRequest request{}")
        val prepareCall = isp.indexOf("prepareNeuralProductionContext(neuralEvidence)", finalizeStart)
        assertTrue("RAW finalize request block must exist", finalizeStart >= 0)
        assertTrue("Neural preparation call must exist", prepareCall > finalizeStart)
        val conditioningBlock = isp.substring(finalizeStart, prepareCall)

        assertTrue(conditioningBlock.contains("const auto frozenPhysicalNoise = meta.calibration.frozenPhysicalNoiseModel()"))
        assertTrue(conditioningBlock.contains("request.effectiveS = frozenPhysicalNoise.shotS"))
        assertTrue(conditioningBlock.contains("request.effectiveO = frozenPhysicalNoise.readO"))
        assertTrue(conditioningBlock.contains("neuralEvidence.effectiveS = frozenPhysicalNoise.shotS"))
        assertTrue(conditioningBlock.contains("neuralEvidence.effectiveO = frozenPhysicalNoise.readO"))

        assertFalse("Neural path must not re-read native effectiveS directly", conditioningBlock.contains("meta.calibration.effectiveS["))
        assertFalse("Neural path must not re-read native effectiveO directly", conditioningBlock.contains("meta.calibration.effectiveO["))
        assertFalse("Neural path must not use OEM Camera2 S as fallback", conditioningBlock.contains("meta.calibration.cameraS"))
        assertFalse("Neural path must not use OEM Camera2 O as fallback", conditioningBlock.contains("meta.calibration.cameraO"))

        assertTrue(policy.contains("out.core.noise.shotS = input.effectiveS"))
        assertTrue(policy.contains("out.core.noise.readO = input.effectiveO"))
        assertTrue(backend.contains("metadata[i] = request.core.noise.shotS[i]"))
        assertTrue(backend.contains("metadata[4u + i] = request.core.noise.readO[i]"))
    }
}
