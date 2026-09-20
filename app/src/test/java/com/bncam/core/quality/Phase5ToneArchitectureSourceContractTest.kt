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
    fun `raw production tone is global exposure gtm log2 fllf then pbr display`() {
        val core = source("src/main/cpp/IspCore.cpp")
        val shader = source("src/main/cpp/vulkan/shaders/spectra_tone_resident.comp")
        val fllfPolicy = source("src/main/cpp/FastLocalLaplacianPolicy.h")
        val exposurePolicy = source("src/main/cpp/GlobalSceneExposurePolicy.h")
        val gtmPolicy = source("src/main/cpp/GlobalToneMappingPolicy.h")

        assertTrue(fllfPolicy.contains("physicalNoiseSigmaY"))
        assertTrue(exposurePolicy.contains("GlobalSceneExposurePlan"))
        assertTrue(gtmPolicy.contains("GlobalToneMappingPlan"))
        assertTrue(core.contains("residualNoiseState.postLinearDetail.varianceY"))
        assertTrue(core.contains("request.fllfPhysicalNoiseSigmaY = fllfPlan.physicalNoiseSigmaY"))
        assertTrue(shader.contains("float ratio = yNew / yOld;"))
        assertTrue(shader.contains("return sceneRgb * ratio;"))
        assertTrue(shader.contains("vec3 pbrNeutralToneMapping(vec3 color)"))

        val rawBranch = shader.substringAfter("void runTone")
            .substringAfter("if (pc.isRawBayer != 0u) {")
            .substringBefore("} else {")
        val globalGtm = rawBranch.indexOf("applyRawGlobalSceneExposureAndGtm(rgb)")
        val fllf = rawBranch.indexOf("applyFllfLocalExposure(gid, rgb)")
        val pbr = rawBranch.indexOf("pbrNeutralToneMapping(rgb)")
        val toneLut = rawBranch.indexOf("applyToneLookLut(rgb)")
        val profile = rawBranch.indexOf("applyProfileColor(rgb")
        assertTrue(globalGtm >= 0 && fllf > globalGtm && pbr > fllf && toneLut > pbr && profile > toneLut)
        assertFalse(rawBranch.contains("applyBroadShadowPlacement"))
    }

    @Test
    fun `legacy dynamic range and camera look owners stay unreachable`() {
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
        assertTrue(core.contains("GLOBAL_SCENE_EXPOSURE__GTM__LOG2_FLLF__PBR_NEUTRAL_DISPLAY__PROFILE_LOOK"))
    }

    @Test
    fun `post tone covariance uses numeric final development jacobians`() {
        val core = source("src/main/cpp/IspCore.cpp")
        assertTrue(core.contains("numericAxisDerivative"))
        assertTrue(core.contains("phase11fDisplayBeforeLut"))
        assertTrue(core.contains("phase5YAxis"))
        assertTrue(core.contains("phase5RgAxis"))
        assertTrue(core.contains("phase5BgAxis"))
        assertTrue(core.contains("GLOBAL_EXPOSURE_GTM_FLLF_BOUND_PBR_PROFILE_NUMERIC_Y_RG_BG_JACOBIANS"))
        assertTrue(core.contains("CAMERA2_SENSOR_NOISE_PROFILE_PLUS_PER_CHANNEL_LENS_SHADING_GAIN_SQUARED"))
        assertTrue(core.contains("sensorNoiseProfileIsoExposureRescaled=false"))
    }
}
