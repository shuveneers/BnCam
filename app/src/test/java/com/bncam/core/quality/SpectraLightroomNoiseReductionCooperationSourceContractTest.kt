package com.bncam.core.quality

import java.io.File
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class SpectraLightroomNoiseReductionCooperationSourceContractTest {
    private fun appDir(): File {
        val direct = File("app")
        if (direct.isDirectory) return direct
        error("Cannot locate app module")
    }

    @Test
    fun `old independent NR ABI is fully removed`() {
        val app = appDir()
        val main = File(app, "src/main").walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }
        listOf("profileIndependentLuma", "profileIndependentChroma", "profileArtifactCleanup",
            "independentLuma", "independentChroma", "SpectraIndependentNrAuthority").forEach {
            assertFalse("obsolete NR lane remains: $it", main.contains(it))
        }
    }

    @Test
    fun `spectra and Lightroom NR use residual-headroom cooperation`() {
        val app = appDir()
        val isp = File(app, "src/main/cpp/IspCore.cpp").readText()
        val policy = File(app, "src/main/cpp/ProfileNoiseReductionPolicy.h").readText()
        assertTrue(isp.contains("resolveProfileNoiseReduction"))
        assertTrue(isp.contains("spectraResidualNr"))
        assertTrue(isp.contains("std::hypot(baseLumaSigma, creativeLumaSigma)"))
        assertTrue(isp.contains("std::hypot(baseChromaSigma, creativeChromaSigma)"))
        assertTrue(isp.contains("combineWithResidualHeadroom"))
        assertTrue(policy.contains("b + (safeCeiling - b) * c"))
        assertTrue(policy.contains("if (plan.luminance <= 0.0f) return 1.0f"))
        assertTrue(policy.contains("if (plan.color <= 0.0f) return 1.0f"))
    }

    @Test
    fun `all six Lightroom NR controls reach native policy`() {
        val app = appDir()
        val bridge = File(app, "src/main/cpp/native-lib.cpp").readText()
        val nativeConfig = File(app, "src/main/cpp/NativeRenderQualityConfig.h").readText()
        val request = File(app, "src/main/cpp/vulkan/VulkanSpectraResidentPostDemosaicBackend.h").readText()
        listOf("profileNrLuminance", "profileNrLuminanceDetail", "profileNrLuminanceContrast",
            "profileNrColor", "profileNrColorDetail", "profileNrColorSmoothness").forEach { name ->
            assertTrue("JNI/native config missing $name", bridge.contains(name) && nativeConfig.contains(name))
            assertTrue("Vulkan request missing $name", request.contains(name))
        }
    }
}
