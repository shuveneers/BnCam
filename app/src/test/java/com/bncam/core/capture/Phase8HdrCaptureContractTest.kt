package com.bncam.core.capture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase8HdrCaptureContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/capture/CaptureRecipeFactory.kt").isFile }
        ?: error("Cannot locate app module")

    @Test fun `legacy bracket fusion remains implemented but hidden`() {
        assertEquals(MethodAvailability.IMPLEMENTED, MultiFrameFusionRegistry.BRACKETED_HDR.availability)
        assertFalse(MultiFrameFusionRegistry.BRACKETED_HDR.exposed)
    }

    @Test fun `hdr enhanced uses temporal raw fusion and immutable user frame ceiling`() {
        val factory = File(appDir, "src/main/java/com/bncam/core/capture/CaptureRecipeFactory.kt").readText()
        val recipe = File(appDir, "src/main/java/com/bncam/core/capture/CaptureRecipe.kt").readText()
        assertTrue(factory.contains("repository.hdrEnhancedFrameSettingFlow.first()"))
        assertTrue(factory.contains("MultiFrameFusionRegistry.HDR_ENHANCED_TEMPORAL.id"))
        assertTrue(factory.contains("reason = \"hdr_enhanced_deliberate_raw_temporal_stack\""))
        assertTrue(recipe.contains("val hdrEnhancedFrameSetting: String"))
        assertTrue(factory.contains("if (computationalHdrRouteEnabled) return 1"))
    }

    @Test fun `hdr frame ceiling is a global app setting rather than profile state`() {
        val repository = File(appDir, "src/main/java/com/bncam/data/settings/SettingsRepository.kt").readText()
        assertTrue(repository.contains("booleanPreferencesKey(\"computational_hdr_enabled\")"))
        assertTrue(repository.contains("computationalHdrEnabledFlow"))
        assertTrue(repository.contains("stringPreferencesKey(\"hdr_enhanced_frames\")"))
        assertTrue(repository.contains("hdrEnhancedFrameSettingFlow"))
        assertTrue(repository.contains("setHdrEnhancedFrameSetting"))
        assertFalse(repository.contains("profileKey(profileId, computationalHdrEnabledKey"))
    }
}
