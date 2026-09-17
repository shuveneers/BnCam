package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase6PhysicalNoiseReadOnlyFusionSourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/DngMerger.cpp").isFile }
        ?: error("Cannot locate app module")

    @Test
    fun `temporal raw fusion consumes frozen physical so without autonomous adaptation`() {
        val app = appDir()
        val merger = File(app, "src/main/cpp/DngMerger.cpp").readText()
        val helper = File(app, "src/main/cpp/SpectraTemporalFusion.h").readText()
        val observer = File(app, "src/main/cpp/vulkan/VulkanSpectraTemporalObserverBackend.cpp").readText()
        val observerHeader = File(app, "src/main/cpp/vulkan/VulkanSpectraTemporalObserverBackend.h").readText()
        val fusion = File(app, "src/main/cpp/vulkan/VulkanRawMultiFrameBackend.cpp").readText()
        val fusionHeader = File(app, "src/main/cpp/vulkan/VulkanRawMultiFrameBackend.h").readText()
        val shader = File(app, "src/main/cpp/vulkan/shaders/raw_multiframe_fusion.comp").readText()
        val nativeBridge = File(app, "src/main/cpp/native-lib.cpp").readText()

        assertTrue(merger.contains("FROZEN_PHYSICAL_SO_READ_ONLY"))
        assertTrue(merger.contains("const double physicalS = model.effectiveS[ch]"))
        assertTrue(merger.contains("const double physicalO = model.effectiveO[ch]"))

        val forbidden = listOf(
            "fitTemporalNoiseModel(",
            "fitStability(",
            "SpectraFitConsensus",
            "SPECTRA_ADAPTIVE_SO",
            "adaptationLowerBound",
            "adaptationUpperBound",
            "adaptedS",
            "adaptedO"
        )
        val productionTexts = listOf(merger, helper, observer, observerHeader, fusion, fusionHeader, shader)
        forbidden.forEach { token ->
            productionTexts.forEach { text -> assertFalse("forbidden phase-6 token: $token", text.contains(token)) }
        }

        assertTrue(shader.contains("float physicalS = pF(P_S0 + uint(ch));"))
        assertTrue(shader.contains("float physicalO = pF(P_O0 + uint(ch));"))
        assertFalse(shader.contains("P_ADAPT_"))

        // JNI keeps the historical arguments only for ABI stability. They are explicitly ignored.
        assertTrue(nativeBridge.contains("(void)spectraAdaptiveCalibrationEnabled;"))
        assertTrue(nativeBridge.contains("(void)spectraMode;"))
    }
}
