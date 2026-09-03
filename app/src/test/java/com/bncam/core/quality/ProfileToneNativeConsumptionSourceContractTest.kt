package com.bncam.core.quality

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProfileToneNativeConsumptionSourceContractTest {
    @Test
    fun profileToneCrossesJniAndIsConsumedAfterPbrNeutral() {
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
        assertTrue(ispCore.contains("lookLuma * profileExposureGain"))
        assertTrue(ispCore.contains("applyProfileTonalRanges(curvedLuma, profileTonePlan)"))
        assertTrue(toneShader.contains("rgb = pbrNeutralToneMapping(rgb);"))
        assertTrue(toneShader.contains("rgb = applyToneLookLut(rgb);"))
        assertTrue(toneShader.contains("rgb = applyProfileColor(rgb);"))
        assertTrue(
            "Explicit profile tone must remain after Khronos PBR Neutral",
            toneShader.indexOf("rgb = applyToneLookLut(rgb);") >
                toneShader.indexOf("rgb = pbrNeutralToneMapping(rgb);")
        )
        assertTrue(ispCore.contains("toneMapperRequested=KHRONOS_PBR_NEUTRAL") ||
            ispCore.contains("toneMapperRequested=\" << \"KHRONOS_PBR_NEUTRAL"))
        assertTrue(ispCore.contains("POST_PBR_NEUTRAL_DISPLAY_LINEAR"))
        assertFalse(toneShader.contains("applyAgXTonemap"))
        assertFalse(ispCore.contains("GTM_SCENE_PLACEMENT"))
    }

    private fun source(path: String): String {
        val direct = File(path)
        if (direct.isFile) return direct.readText()
        val fromModule = File("../$path")
        if (fromModule.isFile) return fromModule.readText()
        error("Source not found: $path")
    }
}
