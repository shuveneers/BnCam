package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveRawToneSourceContractTest {
    @Test
    fun `raw tone path uses spatial bidirectional normalization and adaptive display placement`() {
        val isp = File("src/main/cpp/IspCore.cpp").readText()
        val policy = File("src/main/cpp/DynamicRangeTonePolicy.h").readText()
        val fllf = File("src/main/cpp/FastLocalLaplacianPolicy.h").readText()
        val shader = File("src/main/cpp/vulkan/shaders/spectra_tone_resident.comp").readText()

        assertTrue(policy.contains("sceneRangeStops"))
        assertTrue(policy.contains("automaticGlobalGainCap"))
        assertTrue(isp.contains("maxGain = std::min(maxGain, dynamicRangeTonePlan.automaticGlobalGainCap)"))
        assertTrue(isp.contains("dynamicRangeTonePlan.displayWhiteExpansionStart"))
        assertTrue(isp.contains("dynamicRangeTonePlan.displayWhiteExpansionGamma"))

        assertTrue(fllf.contains("negative highlight compression is safe"))
        assertTrue(shader.contains("float rawRequestedEv = log2(sceneKey / localLuma);"))
        assertTrue(shader.contains("float correctionEv = rawRequestedEv * strength * need;"))
        assertTrue(shader.contains("return sceneRgb * exp2(correctionEv);"))
        assertTrue(shader.contains("float knee = clamp(pc.shoulderStart"))
        assertTrue(shader.contains("float exponent = clamp(pc.shoulderStrength"))

        val runTone = shader.substringAfter("void runTone(uvec2 gid)").substringBefore("void captureUltraHdrAuthority")
        val exposureIndex = runTone.indexOf("rgb *= pc.exposureGain;")
        val fllfIndex = runTone.indexOf("applyFllfLocalExposure")
        val agxIndex = runTone.indexOf("applyAgXTonemap")
        assertTrue(exposureIndex >= 0 && fllfIndex > exposureIndex && agxIndex > fllfIndex)

        // These temporary global presentation hacks must not re-enter the RAW path.
        assertFalse(shader.contains("effectiveRawBaseVibrance = max"))
        assertFalse(shader.contains("kRawBlackFloor"))
    }
}
