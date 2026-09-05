package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase5ToneArchitectureSourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/IspCore.cpp").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun `raw production tone is log2 fllf strict ratio then pbr neutral`() {
        val core = source("src/main/cpp/IspCore.cpp")
        val shader = source("src/main/cpp/vulkan/shaders/spectra_tone_resident.comp")
        val policy = source("src/main/cpp/FastLocalLaplacianPolicy.h")

        assertTrue(policy.contains("physicalNoiseSigmaY"))
        assertTrue(core.contains("residualNoiseState.postLinearDetail.varianceY"))
        assertTrue(core.contains("request.fllfPhysicalNoiseSigmaY = fllfPlan.physicalNoiseSigmaY"))
        assertTrue(shader.contains("sigmaYAtScale / (max(localLuma, 1.0e-4) * 0.69314718056)") ||
                   shader.contains("physicalSigmaY / (max(localLuma, 1.0e-4) * 0.69314718056)"))
        assertTrue(shader.contains("float ratio = yNew / yOld;"))
        assertTrue(shader.contains("return sceneRgb * ratio;"))
        assertTrue(shader.contains("vec3 pbrNeutralToneMapping(vec3 color)"))

        val rawBranch = shader.substringAfter("void runTone")
            .substringAfter("if (pc.isRawBayer != 0u) {")
            .substringBefore("} else {")
        val fllf = rawBranch.indexOf("applyFllfLocalExposure(gid, rgb)")
        val pbr = rawBranch.indexOf("pbrNeutralToneMapping(rgb)")
        val toneLut = rawBranch.indexOf("applyToneLookLut(rgb)")
        val profile = rawBranch.indexOf("applyProfileColor(rgb)")
        assertTrue(fllf >= 0 && pbr > fllf && toneLut > pbr && profile > toneLut)
        assertFalse(rawBranch.contains("rgb *= pc.exposureGain"))
    }

    @Test
    fun `old raw global and display tone owners are unreachable`() {
        val core = source("src/main/cpp/IspCore.cpp")
        val shader = source("src/main/cpp/vulkan/shaders/spectra_tone_resident.comp")
        val production = core + "\n" + shader

        assertFalse(production.contains("DynamicRangeTonePolicy.h"))
        assertFalse(production.contains("RawGtmScenePolicy.h"))
        assertFalse(production.contains("RawCameraProfileRenderPolicy.h"))
        assertFalse(production.contains("applyAgXTonemap"))
        assertFalse(production.contains("AGX_INSET_MATRIX"))
        assertFalse(production.contains("dynamicRangeTonePlan"))
        assertFalse(production.contains("rawGtmScenePlan"))
        assertTrue(core.contains("rawColorAutomaticPostCcmRenderOwner=NONE"))
        assertTrue(core.contains("phase5DisplayMapper=KHRONOS_PBR_NEUTRAL"))
    }

    @Test
    fun `post tone covariance uses pbr neutral luma and opponent jacobians`() {
        val core = source("src/main/cpp/IspCore.cpp")
        assertTrue(core.contains("numericAxisDerivative"))
        assertTrue(core.contains("phase5YAxis"))
        assertTrue(core.contains("phase5RgAxis"))
        assertTrue(core.contains("phase5BgAxis"))
        assertTrue(core.contains("FLLF_CONSERVATIVE_STRICT_RATIO_PLUS_NUMERIC_PBR_NEUTRAL_Y_RG_BG_JACOBIANS"))
        assertTrue(core.contains("CAMERA2_SENSOR_NOISE_PROFILE_PLUS_PER_CHANNEL_LENS_SHADING_GAIN_SQUARED"))
        assertTrue(core.contains("SPATIAL_EXPOSURE_RMS_GAIN_FROM_MEAN_GAIN_SQUARED"))
        assertTrue(core.contains("sensorNoiseProfileIsoExposureRescaled=false"))
    }
}
