package com.bncam.core.quality

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains

class ToneMappingTruthTelemetrySourceContractTest {
    private fun source(relative: String): String = File(relative).readText()

    @Test
    fun previewShaderPublishesExactResolvedGtmAndLtmScalars() {
        listOf("raw_preview.comp", "raw_preview_image.comp").forEach { shaderName ->
            val shader = source("src/main/cpp/vulkan/shaders/$shaderName")
            assertContains(shader, "LTM_STRENGTH_INDEX = 559u")
            assertContains(shader, "LTM_MAX_LIFT_EV_INDEX = 560u")
            assertContains(shader, "LTM_MAX_COMPRESS_EV_INDEX = 561u")
            assertContains(shader, "ANALYSIS_NV21_START_INDEX = 572u")
            assertContains(shader, "statsBuffer[LTM_STRENGTH_INDEX] = floatBitsToUint(localToneStrength)")
            assertContains(shader, "uintBitsToFloat(statsBuffer[LTM_STRENGTH_INDEX])")
        }
    }

    @Test
    fun previewJniAndRendererExposeToneTruthWithoutChangingLegacyIndices() {
        val native = source("src/main/cpp/native-lib.cpp")
        val renderer = source("src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt")
        assertContains(native, "TONE_TRUTH_START_INDEX = DISPLAY_B_CLIP_INDEX + 1")
        assertContains(native, "FIRST_ACTIVATION_DIAGNOSTICS_START_INDEX = TONE_TRUTH_START_INDEX + 10")
        assertContains(native, "FIRST_ACTIVATION_DIAGNOSTICS_COUNT = 20")
        assertContains(renderer, "sceneMidtoneTarget = result.getOrElse(566)")
        assertContains(renderer, "ltmMaxCompressEv = result.getOrElse(575)")
        assertContains(renderer, "gtmShoulder=")
        assertContains(renderer, "ltmStrength=")
    }

    @Test
    fun captureShotSummarySurfacesHighSignalTonePolicy() {
        val logger = source("src/main/java/com/bncam/core/debug/ShotLogger.kt")
        assertContains(logger, "nativeRawIsp.toneSceneMidtoneTarget")
        assertContains(logger, "nativeRawIsp.toneShoulderStart")
        assertContains(logger, "nativeRawIsp.localToneStrength")
        assertContains(logger, "nativeRawIsp.highlightOccupancyPct")
    }
}
