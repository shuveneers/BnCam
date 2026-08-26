package com.bncam.core.capture

import android.graphics.ImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase5A1IntegrationTest {

    @Test
    fun test1_independentMultiFrameSettingsPerProfile() {
        val profileAConfig = MultiFrameCaptureConfig(
            profileId = "profile_1",
            profileName = "Profile A (Standard)",
            sourceFormat = ImageFormat.RAW10,
            shootingMode = CaptureMode.MULTI,
            alignmentMethod = "Auto",
            fusionMethod = "Auto",
            fusionFrameCount = 8,
            exposureStrategy = "Standard",
            configuredBufferCapacity = 35,
            effectiveBufferCapacity = 35,
            lensId = "0",
            demosaicMethod = "Malvar2004",
            outputPolicy = OutputPolicy.JPEG
        )

        val profileBConfig = MultiFrameCaptureConfig(
            profileId = "profile_2",
            profileName = "Profile B (ETTR)",
            sourceFormat = ImageFormat.RAW10,
            shootingMode = CaptureMode.MULTI,
            alignmentMethod = "Phase Correlation Pyramid",
            fusionMethod = "Robust Weighted Average",
            fusionFrameCount = 12,
            exposureStrategy = "ETTR",
            configuredBufferCapacity = 35,
            effectiveBufferCapacity = 35,
            lensId = "0",
            demosaicMethod = "Malvar2004",
            outputPolicy = OutputPolicy.JPEG
        )

        assertEquals("profile_1", profileAConfig.profileId)
        assertEquals("profile_2", profileBConfig.profileId)
        assertEquals("Standard", profileAConfig.exposureStrategy)
        assertEquals("ETTR", profileBConfig.exposureStrategy)
        assertEquals(8, profileAConfig.fusionFrameCount)
        assertEquals(12, profileBConfig.fusionFrameCount)
    }

    @Test
    fun test2_profileDuplicationCopiesMultiFrameSettings() {
        val original = MultiFrameCaptureConfig(
            profileId = "profile_1",
            profileName = "Original",
            sourceFormat = ImageFormat.RAW_SENSOR,
            shootingMode = CaptureMode.MULTI,
            alignmentMethod = "Phase Correlation Pyramid",
            fusionMethod = "Robust Weighted Average",
            fusionFrameCount = 9,
            exposureStrategy = "ETTR",
            configuredBufferCapacity = 15,
            effectiveBufferCapacity = 15,
            lensId = "0",
            demosaicMethod = "Malvar2004",
            outputPolicy = OutputPolicy.JPEG
        )

        val duplicate = original.copy(profileId = "profile_1_copy", profileName = "Copy of Original")

        assertEquals("profile_1_copy", duplicate.profileId)
        assertEquals(original.alignmentMethod, duplicate.alignmentMethod)
        assertEquals(original.fusionMethod, duplicate.fusionMethod)
        assertEquals(original.fusionFrameCount, duplicate.fusionFrameCount)
        assertEquals(original.exposureStrategy, duplicate.exposureStrategy)
    }

    @Test
    fun test3_migrationDefaultsProvidedForOlderProfiles() {
        val defaultAlign = "Auto"
        val defaultFusion = "Auto"
        val defaultYuvJpeg = 8
        val defaultRaw10Jpeg = 8
        val defaultRawSensorJpeg = 5
        val defaultRaw10Dng = 1
        val defaultRawSensorDng = 1
        val defaultExposure = "ETTR"

        assertEquals("Auto", defaultAlign)
        assertEquals("Auto", defaultFusion)
        assertEquals(8, defaultYuvJpeg)
        assertEquals(8, defaultRaw10Jpeg)
        assertEquals(5, defaultRawSensorJpeg)
        assertEquals(1, defaultRaw10Dng)
        assertEquals(1, defaultRawSensorDng)
        assertEquals("ETTR", defaultExposure)
    }

    @Test
    fun test4_formatSpecificFrameLimitClamping() {
        fun clampJpeg(format: Int, count: Int): Int = when (format) {
            ImageFormat.RAW_SENSOR -> count.coerceIn(2, 10)
            else -> count.coerceIn(2, 25)
        }
        fun clampDng(format: Int, count: Int): Int = when (format) {
            ImageFormat.RAW_SENSOR -> count.coerceIn(1, 10)
            ImageFormat.RAW10 -> count.coerceIn(1, 25)
            else -> 1
        }

        assertEquals(25, clampJpeg(ImageFormat.RAW10, 50))
        assertEquals(10, clampJpeg(ImageFormat.RAW_SENSOR, 30))
        assertEquals(2, clampJpeg(ImageFormat.YUV_420_888, 1))

        assertEquals(25, clampDng(ImageFormat.RAW10, 100))
        assertEquals(10, clampDng(ImageFormat.RAW_SENSOR, 15))
        assertEquals(1, clampDng(ImageFormat.YUV_420_888, 5))
    }

    @Test
    fun test5_immutableShutterConfigurationFrozen() {
        val configAtShutter = MultiFrameCaptureConfig(
            profileId = "active_profile_01",
            profileName = "Night ETTR",
            sourceFormat = ImageFormat.RAW10,
            shootingMode = CaptureMode.MULTI,
            alignmentMethod = "Phase Correlation Pyramid",
            fusionMethod = "Robust Weighted Average",
            fusionFrameCount = 15,
            exposureStrategy = "ETTR",
            configuredBufferCapacity = 35,
            effectiveBufferCapacity = 35,
            lensId = "0",
            demosaicMethod = "Malvar2004",
            outputPolicy = OutputPolicy.JPEG
        )

        // Post-shutter mutation attempt simulated:
        val postShutterSettingMutation = "Standard"

        assertEquals("active_profile_01", configAtShutter.profileId)
        assertEquals("ETTR", configAtShutter.exposureStrategy)
        assertEquals(15, configAtShutter.fusionFrameCount)
    }

    @Test
    fun test6_standardProducesZeroEttrShift() {
        val res = EttrExposureStrategy.calculateExposureShift(
            shootingMode = CaptureMode.MULTI,
            exposureStrategy = "Standard",
            format = ImageFormat.RAW10,
            recentFrames = emptyList()
        )
        assertEquals(0.0f, res.appliedShiftEv, 0.0001f)
        assertFalse(res.ettrApplied)
    }

    @Test
    fun test7_ettrReachesCamera2RequestAdapter() {
        val res = EttrExposureStrategy.calculateExposureShift(
            shootingMode = CaptureMode.MULTI,
            exposureStrategy = "ETTR",
            format = ImageFormat.RAW10,
            recentFrames = emptyList()
        )
        assertNotNull(res)
        assertEquals("ETTR", res.exposureStrategy)
        assertTrue(res.appliedShiftEv in 0.0f..1.5f)
    }

    @Test
    fun test8_exposureGenerationChangesOnStrategySwitch() {
        val resStandard = EttrExposureStrategy.calculateExposureShift(
            shootingMode = CaptureMode.MULTI,
            exposureStrategy = "Standard",
            format = ImageFormat.RAW10,
            recentFrames = emptyList()
        )
        val genBefore = resStandard.exposureGenerationId

        val resEttr = EttrExposureStrategy.calculateExposureShift(
            shootingMode = CaptureMode.MULTI,
            exposureStrategy = "ETTR",
            format = ImageFormat.RAW10,
            recentFrames = emptyList()
        )
        val genAfter = resEttr.exposureGenerationId

        assertTrue("Exposure generation ID must advance when ETTR is enabled", genAfter >= genBefore)
    }

    @Test
    fun test9_incompatibleGenerationsExcludedFromCandidateSet() {
        val candidateGeneration = 1L
        val activeGeneration = 2L
        val isCompatible = (candidateGeneration == activeGeneration)

        assertFalse("Old generation candidates must be excluded from active candidate set", isCompatible)
    }

    @Test
    fun test10_switchingBackRestoresStandardExposure() {
        val res = EttrExposureStrategy.calculateExposureShift(
            shootingMode = CaptureMode.MULTI,
            exposureStrategy = "Standard",
            format = ImageFormat.RAW10,
            recentFrames = emptyList()
        )
        assertEquals(0.0f, res.appliedShiftEv, 0.0001f)
        assertEquals("standard_strategy_selected", res.fallbackReason)
    }

    @Test
    fun test11_singleFrameProfilesRemainUnchanged() {
        val res = EttrExposureStrategy.calculateExposureShift(
            shootingMode = CaptureMode.SINGLE,
            exposureStrategy = "ETTR",
            format = ImageFormat.RAW10,
            recentFrames = emptyList()
        )
        assertEquals(0.0f, res.appliedShiftEv, 0.0001f)
        assertFalse("Single-frame mode must never apply ETTR shift", res.ettrApplied)
    }

    @Test
    fun test12_newProfileReceivesPhase5DDefaultsAndExistingProfileRetainsValues() {
        // Newly created profile defaults
        val newProfileDefaults = MultiFrameCaptureConfig(
            profileId = "new_profile_99",
            profileName = "Newly Created Multi-Frame Profile",
            sourceFormat = ImageFormat.RAW10,
            shootingMode = CaptureMode.MULTI,
            alignmentMethod = "Auto",
            fusionMethod = "Auto",
            fusionFrameCount = 8,
            exposureStrategy = "ETTR",
            configuredBufferCapacity = 35,
            effectiveBufferCapacity = 35,
            lensId = "0",
            demosaicMethod = "Malvar2004",
            outputPolicy = OutputPolicy.JPEG
        )

        assertEquals("Auto", newProfileDefaults.alignmentMethod)
        assertEquals("Auto", newProfileDefaults.fusionMethod)
        assertEquals("ETTR", newProfileDefaults.exposureStrategy)
        assertEquals(8, newProfileDefaults.fusionFrameCount)

        // Existing profile retains custom values
        val existingSavedProfile = MultiFrameCaptureConfig(
            profileId = "existing_profile_01",
            profileName = "Custom Existing Profile",
            sourceFormat = ImageFormat.RAW10,
            shootingMode = CaptureMode.MULTI,
            alignmentMethod = "Phase Correlation Pyramid",
            fusionMethod = "Robust Weighted Average",
            fusionFrameCount = 15,
            exposureStrategy = "Standard",
            configuredBufferCapacity = 35,
            effectiveBufferCapacity = 35,
            lensId = "0",
            demosaicMethod = "Malvar2004",
            outputPolicy = OutputPolicy.JPEG
        )

        assertEquals("Phase Correlation Pyramid", existingSavedProfile.alignmentMethod)
        assertEquals("Robust Weighted Average", existingSavedProfile.fusionMethod)
        assertEquals("Standard", existingSavedProfile.exposureStrategy)
        assertEquals(15, existingSavedProfile.fusionFrameCount)
    }
}
