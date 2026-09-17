package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeuralFixedMasterAuthoritySourceContractTest {
    private fun source(path: String): String = sequenceOf(File("."), File("app"))
        .map { File(it, path) }
        .firstOrNull { it.isFile }
        ?.readText() ?: error("Missing source: $path")

    @Test
    fun `neural master authority is binary and stored strength cannot attenuate it`() {
        val ui = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileTuningSubScreens.kt")
        val render = source("src/main/java/com/bncam/core/quality/RenderQualityConfig.kt")
        val image = source("src/main/java/com/bncam/core/engine/ImageUtils.kt")
        val native = source("src/main/cpp/native-lib.cpp")
        val policy = source("src/main/cpp/SpectraNeuralProductionPolicy.h")
        val isp = source("src/main/cpp/IspCore.cpp")
        val block = ui.substringAfter("fun ProfileDenoiseSettingsScreen").substringBefore("fun ProfileMultiFrameSettingsScreen")

        assertFalse(block.contains("title = \"Strength\""))
        assertFalse(block.contains("key = ProfileIspKeys.NEURAL_DENOISE_STRENGTH"))
        assertTrue(render.contains("get() = if (spectraEnabled) SpectraProfileDefaults.MASTER_AUTHORITY else 0f"))
        assertTrue(image.contains("SpectraProfileDefaults.MASTER_AUTHORITY else 0.0f"))
        assertTrue(native.contains("(void) profileSpectraStrength;"))
        assertTrue(native.contains("cfg.profileSpectraStrength = 1.0f;"))
        assertTrue(policy.contains("out.noiseReduction = out.enabled ? 1.0f : 0.0f;"))
        assertFalse(isp.contains("uiConfig.profileSpectraStrength,"))
    }
}
