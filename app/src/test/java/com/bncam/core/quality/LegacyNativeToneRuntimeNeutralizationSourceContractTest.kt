package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyNativeToneRuntimeNeutralizationSourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/quality/RenderQualityConfig.kt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun `legacy native profile tone keys cannot stack on new gtm ltm`() {
        val render = source("src/main/java/com/bncam/core/quality/RenderQualityConfig.kt")
        val resolver = source("src/main/java/com/bncam/core/quality/LibpatcherProfileResolver.kt")
        val repo = source("src/main/java/com/bncam/data/settings/SettingsRepository.kt")
        val keys = source("src/main/java/com/bncam/data/settings/ProfileLensTuningSettings.kt")
        val profileEdit = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileEditScreen.kt")

        val runtimeSpecs = resolver.substringAfter("fun runtimeProfileSettingSpecs()")
            .substringBefore("fun allProfileSettingSpecs()")
        assertFalse(runtimeSpecs.contains("ProfileIspKeys.SHADOW_LIFT"))
        assertFalse(runtimeSpecs.contains("ProfileIspKeys.TOE_EXPONENT"))
        assertFalse(runtimeSpecs.contains("ProfileIspKeys.SOFT_BLACK_ANCHOR"))

        assertFalse(keys.contains("SHADOW_LIFT"))
        assertFalse(keys.contains("TOE_EXPONENT"))
        assertFalse(keys.contains("SOFT_BLACK_ANCHOR"))
        assertFalse(keys.contains("isp_shadow_lift"))
        assertFalse(keys.contains("isp_toe_exponent"))
        assertFalse(keys.contains("isp_soft_black_anchor"))
        assertFalse(repo.contains("shadowLiftFlow"))
        assertFalse(repo.contains("toeExponentFlow"))
        assertFalse(repo.contains("softBlackPointAnchorFlow"))
        assertFalse(repo.contains("setShadowLift"))
        assertFalse(repo.contains("setToeExponent"))
        assertFalse(repo.contains("setSoftBlackPointAnchor"))
        assertFalse(profileEdit.contains("ProfileIspKeys.SHADOW_LIFT"))
        assertFalse(profileEdit.contains("ProfileIspKeys.TOE_EXPONENT"))
        assertFalse(profileEdit.contains("ProfileIspKeys.SOFT_BLACK_ANCHOR"))

        assertFalse(render.contains("val shadowLift"))
        assertFalse(render.contains("val toeExponent"))
        assertFalse(render.contains("val softBlackPointAnchor"))
        assertFalse(render.contains("repo.shadowLiftFlow.first()"))
        assertFalse(render.contains("repo.toeExponentFlow.first()"))
        assertFalse(render.contains("repo.softBlackPointAnchorFlow.first()"))

        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val nativeLib = source("src/main/cpp/native-lib.cpp")
        val nativeConfig = source("src/main/cpp/NativeRenderQualityConfig.h")
        assertFalse(manager.contains("shadowLift ="))
        assertFalse(manager.contains("toeExponent ="))
        assertFalse(manager.contains("softBlackPointAnchor ="))
        assertFalse(nativeConfig.contains("shadowLift"))
        assertFalse(nativeConfig.contains("toeExponent"))
        assertFalse(nativeConfig.contains("softBlackPointAnchor"))
        val rawQualityBuilder = nativeLib.substringAfter("NativeRenderQualityConfig makeQualityConfig(")
            .substringBefore("return cfg;")
        assertFalse(rawQualityBuilder.contains("shadowLift"))
        assertFalse(rawQualityBuilder.contains("toeExponent"))
        assertFalse(rawQualityBuilder.contains("softBlackPointAnchor"))
        val yuvJni = nativeLib.substringAfter("Java_com_bncam_core_engine_ImageUtils_processNativeYuv(")
            .substringBefore("Java_com_bncam_core_engine_ImageUtils_mergeNativeRaw10DirectRaw16")
        assertFalse(yuvJni.contains("shadowLift"))
        assertFalse(yuvJni.contains("toeExponent"))
        assertFalse(yuvJni.contains("softBlackPointAnchor"))

        val portable = repo.substringAfter("private suspend fun readPortableEffectiveProfileSnapshot")
            .substringBefore("suspend fun importProfileBnc")
        assertFalse(portable.contains("ProfileIspKeys.SHADOW_LIFT"))
        assertFalse(portable.contains("ProfileIspKeys.TOE_EXPONENT"))
        assertFalse(portable.contains("ProfileIspKeys.SOFT_BLACK_ANCHOR"))
        assertTrue(portable.contains("includePortableDefaults = true"))
    }
}
