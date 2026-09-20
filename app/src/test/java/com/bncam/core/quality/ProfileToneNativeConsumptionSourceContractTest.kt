package com.bncam.core.quality

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProfileToneNativeConsumptionSourceContractTest {
    @Test
    fun profileToneCrossesJniAndIsConsumedAfterGlobalToneAndDisplayMapping() {
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
        assertTrue(ispCore.contains("explicitProfileExposureGain"))
        assertTrue(ispCore.contains("request.profileExposureGain = explicitProfileExposureGain"))
        assertTrue(ispCore.contains("applyProfileTonalRanges(curvedLuma, profileTonePlan)"))
        assertTrue(ispCore.contains("profileToneExposureStage=POST_DISPLAY_EXPLICIT_PROFILE_LOOK"))
        assertTrue(toneShader.contains("rgb = pbrNeutralToneMapping(rgb);"))
        assertTrue(toneShader.contains("rgb *= max(0.0, pc.presenceReserved1);"))
        assertTrue(toneShader.contains("rgb = applyToneLookLut(rgb);"))
        assertTrue(toneShader.contains("rgb = applyProfileColor(rgb, ivec2(gid));"))

        val rawBranch = toneShader.substringAfter("void runTone")
            .substringAfter("if (pc.isRawBayer != 0u) {")
            .substringBefore("} else {")
        val display = rawBranch.indexOf("pbrNeutralToneMapping(rgb)")
        val exposure = rawBranch.indexOf("pc.presenceReserved1")
        val tone = rawBranch.indexOf("applyToneLookLut(rgb)")
        val color = rawBranch.indexOf("applyProfileColor(rgb")
        assertTrue("Explicit profile look must remain after display mapping",
            display >= 0 && exposure > display && tone > exposure && color > tone)

        assertTrue(ispCore.contains("resolveGlobalSceneExposurePlan"))
        assertTrue(ispCore.contains("resolveGlobalToneMappingPlan"))
        assertTrue(ispCore.contains("toneMapperRequested=") && ispCore.contains("KHRONOS_PBR_NEUTRAL"))
        assertFalse(toneShader.contains("applyAgXTonemap"))
        assertFalse(ispCore.contains("dynamicRangeTonePlan"))
    }

    private fun source(path: String): String {
        val direct = File(path)
        if (direct.isFile) return direct.readText()
        val fromModule = File("../$path")
        if (fromModule.isFile) return fromModule.readText()
        error("Source not found: $path")
    }
}
