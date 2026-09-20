package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveRawToneSourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/IspCore.cpp").isFile }
        ?: error("Cannot locate app module")

    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun `raw tone uses explicit evidence based global placement gtm local fllf and display mapping`() {
        val isp = source("src/main/cpp/IspCore.cpp")
        val exposurePolicy = source("src/main/cpp/GlobalSceneExposurePolicy.h")
        val gtmPolicy = source("src/main/cpp/GlobalToneMappingPolicy.h")
        val fllf = source("src/main/cpp/FastLocalLaplacianPolicy.h")
        val shader = source("src/main/cpp/vulkan/shaders/spectra_tone_resident.comp")

        listOf("p35", "p50", "p75", "p90", "p95", "p99", "rawClipFraction",
            "physicalNoiseSigmaY", "highlightHeadroomEv", "sceneRangeEv").forEach {
            assertTrue("Global scene placement must observe $it", exposurePolicy.contains(it))
        }
        assertTrue(exposurePolicy.contains("GlobalSceneExposurePlan"))
        assertTrue(gtmPolicy.contains("GTM owns only the upper scene-linear range"))
        assertTrue(gtmPolicy.contains("if (luma <= shoulderStart) return luma;"))
        assertTrue(fllf.contains("LOCAL redistribution stage"))
        assertTrue(isp.contains("resolveGlobalSceneExposurePlan"))
        assertTrue(isp.contains("resolveGlobalToneMappingPlan"))
        assertTrue(isp.contains("request.adaptiveExposureEnabled = false"))

        val runTone = shader.substringAfter("void runTone(uvec2 gid)")
            .substringAfter("if (pc.isRawBayer != 0u) {")
            .substringBefore("} else {")
        val globalGtm = runTone.indexOf("applyRawGlobalSceneExposureAndGtm(rgb)")
        val fllfIndex = runTone.indexOf("applyFllfLocalExposure")
        val display = runTone.indexOf("pbrNeutralToneMapping")
        val explicitProfile = runTone.indexOf("pc.presenceReserved1")
        assertTrue(globalGtm >= 0 && fllfIndex > globalGtm && display > fllfIndex &&
            explicitProfile > display)

        // Temporary/global presentation hacks and retired alternative tone owners must not return.
        assertFalse(shader.contains("effectiveRawBaseVibrance = max"))
        assertFalse(shader.contains("kRawBlackFloor"))
        assertFalse(shader.contains("applyBroadShadowPlacement"))
        assertFalse(shader.contains("applyAgXTonemap"))
        assertFalse(isp.contains("dynamicRangeTonePlan"))
    }

    @Test
    fun `gtm can be exact identity and fllf noise is propagated through global tone derivative`() {
        val isp = source("src/main/cpp/IspCore.cpp")
        val backend = source("src/main/cpp/vulkan/VulkanSpectraResidentToneBackend.cpp")
        val header = source("src/main/cpp/vulkan/VulkanSpectraResidentToneBackend.h")
        val shader = source("src/main/cpp/vulkan/shaders/spectra_tone_resident.comp")

        assertTrue(header.contains("bool gtmEnabled = false"))
        assertTrue(backend.contains("request.gtmEnabled ?"))
        assertTrue(backend.contains(": 0.0f"))
        assertTrue(shader.contains("if (pc.shoulderStrength <= 1.0e-5) return luma;"))
        assertTrue(isp.contains("phase11fFllfToneDerivative"))
        assertTrue(isp.contains("phase11fFllfPhysicalNoiseSigmaY"))
        assertTrue(isp.contains("phase5FllfPhysicalNoiseSigmaY * phase11fFllfToneDerivative"))
    }
}
