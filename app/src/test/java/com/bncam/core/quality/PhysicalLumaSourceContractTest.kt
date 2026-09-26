package com.bncam.core.quality

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class PhysicalLumaSourceContractTest {
    private fun source(path: String): String = sequenceOf(File("."), File("app"))
        .map { File(it, "src/$path") }.first { it.isFile }.readText().replace("\r\n", "\n")

    @Test fun commonOwnerFollowsChromaAndPrecedesAwb() {
        val shader = source("main/cpp/vulkan/shaders/spectra_demosaic_resident.comp")
        val chroma = shader.indexOf("vec3 rawForWb = physicalChromaDenoise")
        val luma = shader.indexOf("rawForWb=physicalLumaDenoise")
        assertTrue(chroma >= 0 && luma > chroma)
        assertTrue(luma < shader.indexOf("vec3 legacyWb = rawForWb"))
        assertEquals(1, "rawForWb=physicalLumaDenoise".toRegex().findAll(shader).count())
    }
    @Test fun authorityHasNoProfileIsoOrDemosaicSwitch() {
        val model = source("main/cpp/PhysicalLumaDenoise.h")
        for (forbidden in listOf("spectraProcessingMode", "sensitivityIso", "DemosaicAlgorithm", "bilateralFilter", "GaussianBlur"))
            assertFalse(forbidden, model.contains(forbidden))
        val isp = source("main/cpp/IspCore.cpp")
        assertTrue(isp.contains("residualNoiseState.postDemosaic, physicalNoiseStatisticsActive"))
        assertTrue(isp.contains("PHYSICAL_LUMA_BYPASS_NO_VALID_NOISE_MODEL"))
        assertTrue(isp.contains("if (!neuralPosteriorSeedApplied)"))
    }
    @Test fun fusionScaleCrossesJniSeparatelyFromFrozenSo() {
        val kt = source("main/java/com/bncam/core/engine/ImageUtils.kt")
        assertTrue(kt.contains("physicalFusionVarianceScale = finalCal?.physicalFusionVarianceScale"))
        val native = source("main/cpp/native-lib.cpp")
        assertTrue(native.contains("jfloat physicalFusionVarianceScale"))
        val gpu = source("main/cpp/vulkan/VulkanRawMultiFrameBackend.cpp")
        assertTrue(gpu.contains("if(out.supportAccepted>0){float statsMs"))
    }
    @Test fun bypassIsDebugOnlyAndNoAdditionalImageReadbackExists() {
        val debug = source("debug/java/com/bncam/BenchmarkDebugReceiverController.kt")
        assertTrue(debug.contains("setPhysicalLumaDebugEnabled"))
        val native = source("main/cpp/native-lib.cpp")
        assertTrue(native.contains("#ifndef NDEBUG\n    bncam::luma::debugEnabled.store"))
        val shader = source("main/cpp/vulkan/shaders/physical_luma_denoise.glsl")
        assertTrue(shader.contains("postChroma-vec3(correction)"))
        assertFalse(shader.contains("clamp(result"))
    }
}
