package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase3PhysicalNoiseNeuralConditioningSourceContractTest {
    @Test
    fun `physical baseline dynamic iso and neural have one noise authority chain`() {
        val resolver = File("src/main/java/com/bncam/core/quality/PhysicalNoiseModel.kt").readText()
        val physicalState = File("src/main/java/com/bncam/core/quality/PhysicalNoiseState.kt").readText()
        val nativeConfig = File("src/main/cpp/NativeRenderQualityConfig.h").readText()
        val isp = File("src/main/cpp/IspCore.cpp").readText()
        val policy = File("src/main/cpp/SpectraNeuralProductionPolicy.h").readText()
        val backend = File("src/main/cpp/vulkan/VulkanNeuralRawDenoiseBackend.cpp").readText()
        val physicalFinalize = File("src/main/cpp/vulkan/shaders/spectra_raw_finalize.comp").readText()
        val neuralCondition = File("src/main/cpp/vulkan/shaders/neural_condition.comp").readText()
        val neuralBridge = File("src/main/cpp/vulkan/shaders/neural_mosaic_bridge.comp").readText()

        // Dynamic ISO has exactly one owner: the parametric resolver selects ISO_NM first and
        // evaluates A/B/C/D once. OEM remains direct Camera2 S/O and bypasses Dynamic ISO.
        assertTrue(resolver.contains("dynamicIsoCoefficient * (captureIso.toDouble() - BASE_NOISE_MODEL_ISO)"))
        assertTrue(resolver.contains("return transformedIso.toInt().toDouble()"))
        assertTrue(resolver.contains("val profile = request.model.resolveAt(effectiveIso)"))
        assertTrue(resolver.contains("require(!dynamicIsoEnabled) { \"OEM cannot enable Dynamic ISO\" }"))

        // Every downstream RAW consumer receives only the frozen effective S/O. The Dynamic ISO
        // coefficient remains trace metadata and is never another pixel-domain strength control.
        assertTrue(physicalState.contains("consumers must use effectiveS/effectiveO"))
        assertTrue(physicalState.contains("return PhysicalNoiseSoContract.pack(_effectiveS, _effectiveO)"))
        assertTrue(physicalState.contains("return (_effectiveS[channel] * x + _effectiveO[channel]).coerceAtLeast(0.0)"))
        assertFalse(physicalFinalize.contains("dynamicIsoCoefficient"))
        assertFalse(neuralCondition.contains("dynamicIsoCoefficient"))
        assertFalse(neuralBridge.contains("dynamicIsoCoefficient"))

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

        // The physical model owns first-pass Bayer denoise even with Neural OFF.
        assertTrue(physicalFinalize.contains("value = baselineNoiseReducedAt(x, y, value)"))
        assertTrue(physicalFinalize.contains("float noiseVariance = predictedNoiseVariance(localMean, channel)"))

        // Neural is only a post-physical residual refinement. Effective S/O is an upper bound;
        // no/invalid residual evidence fails closed to zero instead of restoring pre-baseline noise.
        assertTrue(neuralCondition.contains("vec4 remainingVariance=postPhysicalResidualVariance"))
        assertTrue(neuralCondition.contains("return min(modelVariance, observed)"))
        assertTrue(neuralCondition.contains("return vec4(0.0)"))
        assertTrue(neuralBridge.contains("return min(modelVariance, max(min(horizontal, vertical), vec4(0.0)))"))
        assertTrue(neuralBridge.contains("return vec4(0.0)"))

        assertTrue(policy.contains("out.core.noise.shotS = input.effectiveS"))
        assertTrue(policy.contains("out.core.noise.readO = input.effectiveO"))
        assertTrue(backend.contains("metadata[i] = request.core.noise.shotS[i]"))
        assertTrue(backend.contains("metadata[4u + i] = request.core.noise.readO[i]"))
    }
}
