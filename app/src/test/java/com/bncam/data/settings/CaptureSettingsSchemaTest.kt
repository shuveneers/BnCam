package com.bncam.data.settings

import com.bncam.core.capture.CaptureMode
import com.bncam.core.capture.FrameOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSettingsSchemaTest {
    @Test
    fun activeSettingsDeclareRecipeAndTraceOwnership() {
        val active = CaptureSettingsSchema.inventory().activeAndExposed
        assertTrue(active.isNotEmpty())
        assertTrue(active.all { it.capturedInRecipe && it.appearsInTrace })
        val demosaicOptions = CaptureSettingsSchema.definition(CaptureSettingKeys.DEMOSAIC_METHOD)
            ?.validRangeOrOptions.orEmpty()
        assertTrue(demosaicOptions.contains("Malvar Inspired"))
        assertTrue(demosaicOptions.contains("RCD Inspired"))
        assertTrue(demosaicOptions.contains("AMAZE Inspired"))
        assertTrue(demosaicOptions.contains("Auto"))
        assertFalse(demosaicOptions.contains("Bilinear"))
        assertFalse(demosaicOptions.contains("Menon"))
    }

    @Test
    fun ineffectiveSettingsAreUnavailableAndLegacyValuesRemainInventoriedNotDeleted() {
        val inventory = CaptureSettingsSchema.inventory()
        val unavailableKeys = inventory.unavailable.map { it.stableKey }.toSet()
        assertEquals(
            setOf(
                CaptureSettingKeys.BASE_TEMPORAL_BIAS,
                CaptureSettingKeys.SELECTION_ACCEPT_ALL,
                CaptureSettingKeys.SELECTION_ALIGNABLE_ONLY,
                CaptureSettingKeys.SELECTION_PREFER_RECENT,
                CaptureSettingKeys.MERGE_SUBPIXEL,
                CaptureSettingKeys.MERGE_LINEAR_INTERPOLATION
            ),
            unavailableKeys
        )
        assertTrue(inventory.unavailable.all { it.visibility == CaptureSettingVisibility.HIDDEN })
        assertTrue(inventory.exposedButIneffective.isEmpty())
        assertTrue(
            inventory.duplicates.any {
                it.stableKey == CaptureSettingKeys.LEGACY_JPEG_FRAMES_RAW10
            }
        )
        assertTrue(inventory.requiringMigration.isNotEmpty())
    }

    @Test
    fun sourceAndModeApplicabilityAreTyped() {
        val rawSensorFrames =
            CaptureSettingsSchema.definition(CaptureSettingKeys.FUSION_FRAMES_RAW_SENSOR)!!
        assertEquals(setOf(FrameOrigin.RAW_SENSOR), rawSensorFrames.applicableSources)
        assertEquals(setOf(CaptureMode.MULTI), rawSensorFrames.applicableModes)
        assertFalse(CaptureMode.SINGLE in rawSensorFrames.applicableModes)
    }

    @Test
    fun processingFrameCountRemainsProfileOwnedWhileDngCountMigratesToAppOutput() {
        val processing =
            CaptureSettingsSchema.definition(CaptureSettingKeys.FUSION_FRAMES_RAW10)!!
        val legacyDng =
            CaptureSettingsSchema.definition(CaptureSettingKeys.DNG_MASTER_FRAMES_RAW10)!!

        assertEquals(CaptureSettingState.ACTIVE, processing.state)
        assertEquals(CaptureSettingState.OBSOLETE, legacyDng.state)
        assertFalse(processing.stableKey == legacyDng.stableKey)
        assertTrue(legacyDng.visibility == CaptureSettingVisibility.HIDDEN)
        assertTrue(legacyDng.migrationBehavior.contains("App Settings"))
        assertTrue(
            CaptureSettingsSchema.inventory().obsolete.any {
                it.stableKey == CaptureSettingKeys.DNG_MASTER_FRAMES_RAW10
            }
        )
    }
}
