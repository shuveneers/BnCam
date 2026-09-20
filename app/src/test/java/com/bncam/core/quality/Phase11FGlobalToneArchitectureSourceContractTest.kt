package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase11FGlobalToneArchitectureSourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/IspCore.cpp").isFile }
        ?: error("Cannot locate app module")

    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun `global scene exposure is evidence based and distinct from Camera2 exposure`() {
        val policy = source("src/main/cpp/GlobalSceneExposurePolicy.h")
        val core = source("src/main/cpp/IspCore.cpp")

        listOf("p35", "p50", "p75", "p90", "p95", "p99", "rawClipFraction",
            "physicalNoiseSigmaY", "requestedEv", "highlightLimitedEv", "appliedEv",
            "highlightHeadroomEv", "sceneRangeEv", "sceneKey", "reason").forEach {
            assertTrue("Global scene exposure must contain $it", policy.contains(it))
        }
        assertTrue(core.contains("resolveGlobalSceneExposurePlan"))
        assertTrue(core.contains("globalSceneExposureGain"))
        assertTrue(core.contains("globalSceneExposureRequestedEv="))
        assertTrue(core.contains("globalSceneExposureHighlightLimitedEv="))
        assertTrue(core.contains("globalSceneExposureEv="))
        assertFalse(policy.contains("CaptureRequest"))
        assertFalse(policy.contains("SENSOR_EXPOSURE_TIME"))
        assertFalse(policy.contains("SENSOR_SENSITIVITY"))
    }

    @Test
    fun `raw runtime order is global exposure then gtm then fllf then display then profile`() {
        val shader = source("src/main/cpp/vulkan/shaders/spectra_tone_resident.comp")
        val rawBranch = shader.substringAfter("void runTone")
            .substringAfter("if (pc.isRawBayer != 0u) {")
            .substringBefore("} else {")
        val globalAndGtm = rawBranch.indexOf("applyRawGlobalSceneExposureAndGtm(rgb)")
        val fllf = rawBranch.indexOf("applyFllfLocalExposure(gid, rgb)")
        val display = rawBranch.indexOf("pbrNeutralToneMapping(rgb)")
        val profileExposure = rawBranch.indexOf("pc.presenceReserved1")
        val profileTone = rawBranch.indexOf("applyToneLookLut(rgb)")
        val profileColor = rawBranch.indexOf("applyProfileColor(rgb")

        assertTrue(globalAndGtm >= 0)
        assertTrue(fllf > globalAndGtm)
        assertTrue(display > fllf)
        assertTrue(profileExposure > display)
        assertTrue(profileTone > profileExposure)
        assertTrue(profileColor > profileTone)
        assertFalse(rawBranch.contains("applyBroadShadowPlacement"))
        assertFalse(rawBranch.contains("rgb *= max(0.0, pc.exposureGain)"))
    }

    @Test
    fun `fllf pyramid observes the already globally placed and gtm mapped scene`() {
        val shader = source("src/main/cpp/vulkan/shaders/spectra_tone_resident.comp")
        val fllfSampler = shader.substringAfter("float fllfSceneLogLumaAt")
            .substringBefore("float fllfParam")
        assertTrue(fllfSampler.contains("applyRawGlobalSceneExposureAndGtm(workingAt(x, y))"))
        assertTrue(shader.contains("float rawGtmMapLuma(float y)"))
        assertTrue(shader.contains("if (luma <= shoulderStart) return luma;"))
    }

    @Test
    fun `gtm is one upper range owner and pbr is a gentle final display boundary`() {
        val policy = source("src/main/cpp/GlobalToneMappingPolicy.h")
        val shader = source("src/main/cpp/vulkan/shaders/spectra_tone_resident.comp")
        assertTrue(policy.contains("GTM owns only the upper scene-linear range"))
        assertTrue(policy.contains("p95CompressionEv"))
        assertTrue(policy.contains("p99CompressionEv"))
        assertTrue(shader.contains("const float startCompression = 0.88;"))
        assertTrue(shader.contains("Phase 11F moves the primary scene-linear shoulder to GTM"))
    }

    @Test
    fun `automatic and explicit profile exposure use separate runtime lanes`() {
        val core = source("src/main/cpp/IspCore.cpp")
        val backend = source("src/main/cpp/vulkan/VulkanSpectraResidentToneBackend.cpp")
        val header = source("src/main/cpp/vulkan/VulkanSpectraResidentToneBackend.h")
        assertTrue(core.contains("request.exposureGain = globalSceneExposureGain"))
        assertTrue(core.contains("request.profileExposureGain = explicitProfileExposureGain"))
        assertTrue(header.contains("float profileExposureGain = 1.0f"))
        assertTrue(backend.contains("push.presenceReserved1 = std::clamp(request.profileExposureGain"))
    }

    @Test
    fun `final display diagnostics are sampled from existing bgr publication without new gpu readback`() {
        val backend = source("src/main/cpp/vulkan/VulkanSpectraResidentToneBackend.cpp")
        val header = source("src/main/cpp/vulkan/VulkanSpectraResidentToneBackend.h")
        val core = source("src/main/cpp/IspCore.cpp")
        assertTrue(backend.contains("sampleDisplayPublicationStatistics"))
        assertTrue(backend.contains("publicationPacked_.mapped"))
        assertTrue(header.contains("float displayP50"))
        assertTrue(header.contains("float displayP95"))
        assertTrue(header.contains("float displayClippingFraction"))
        assertTrue(core.contains("displayP50="))
        assertTrue(core.contains("displayP95="))
        assertTrue(core.contains("displayClipping="))
    }
}
