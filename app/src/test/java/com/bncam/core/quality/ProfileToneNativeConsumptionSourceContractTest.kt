package com.bncam.core.quality

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProfileToneNativeConsumptionSourceContractTest {
    @Test
    fun profileToneCrossesJniAndIsConsumedByCaptureGtmLtm() {
        val imageUtils = source("app/src/main/java/com/bncam/core/engine/ImageUtils.kt")
        val nativeLib = source("app/src/main/cpp/native-lib.cpp")
        val nativeConfig = source("app/src/main/cpp/NativeRenderQualityConfig.h")
        val ispCore = source("app/src/main/cpp/IspCore.cpp")
        val toneShader = source("app/src/main/cpp/vulkan/shaders/spectra_tone_resident.comp")

        listOf(
            "profileToneExposure", "profileToneHighlights", "profileToneShadows",
            "profileToneWhites", "profileToneBlacks", "profileToneContrast", "profileLocalToneBias"
        ).forEach { key ->
            assertTrue("ImageUtils must pass $key", imageUtils.contains(key))
            assertTrue("JNI bridge must accept $key", nativeLib.contains(key))
            assertTrue("Native config must own $key", nativeConfig.contains(key))
            assertTrue("IspCore must consume $key", ispCore.contains(key))
        }
        assertTrue(ispCore.contains("profileTonePlan.exposureMultiplier"))
        assertTrue(ispCore.contains("applyProfileTonalRanges(curvedLuma, profileTonePlan)"))
        assertTrue(ispCore.contains("profileTonePlan.blackAnchorDelta"))
        assertTrue(ispCore.contains("profileTonePlan.localToneStrengthScale"))
        assertTrue(ispCore.contains("automaticExposureGain * profileTonePlan.exposureMultiplier"))
        assertTrue(toneShader.contains("rgb = applyAgXTonemap(rgb);"))
        assertTrue(toneShader.contains("rgb = applyToneLookLut(rgb);"))
        assertTrue(toneShader.contains("toneLut[lutIdx * 2u + 0u]"))
        assertTrue(
            "RAW AgX must consume the tone look after the DRT",
            toneShader.indexOf("rgb = applyToneLookLut(rgb);") >
                toneShader.indexOf("rgb = applyAgXTonemap(rgb);")
        )
        assertTrue(ispCore.contains("toneMapperRequested="))
        assertTrue(ispCore.contains("AGX_VULKAN_RESIDENT"))
        assertTrue(ispCore.contains("toneProfileLookLutApplied="))
        assertTrue(ispCore.contains("POST_AGX_DISPLAY_LINEAR"))
        assertTrue(ispCore.contains("profileHighlightsExecution=DISPLAY_LINEAR_TONE_LUT"))
    }

    private fun source(path: String): String {
        val direct = File(path)
        if (direct.isFile) return direct.readText()
        val fromModule = File("../$path")
        if (fromModule.isFile) return fromModule.readText()
        error("Source not found: $path")
    }
}
