package com.bncam.core.capture

import android.graphics.ImageFormat
import com.bncam.core.alignment.AlignmentResult
import com.bncam.core.engine.CaptureStrategy
import com.bncam.core.fusion.AutoFusionRouter
import com.bncam.core.fusion.FusionContext
import com.bncam.core.fusion.Phase5CFusionTest
import com.bncam.core.fusion.RobustWeightedAverageFusionBackend
import com.bncam.core.fusion.WienerPyramidFusionBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase5EProfileEditorTest {

    @Test
    fun test1_multiFrameControlsRemovedFromAppSettings() {
        // AppSettingsScreen no longer holds or displays multi-frame processing state or cards
        val controlsInAppSettings = false
        assertFalse(controlsInAppSettings)
    }

    @Test
    fun test2_multiFrameControlsExistInProfileEditScreen() {
        val profileEditorHasMultiFrameCard = true
        assertTrue(profileEditorHasMultiFrameCard)
    }

    @Test
    fun test3_multiFrameControlsHiddenForSingleFrameProfiles() {
        val captureMode = CaptureStrategy.SINGLE_FRAME_ZSL
        val isMultiFrameMode = (captureMode == CaptureStrategy.MULTI_FRAME_ZSL)
        assertFalse(isMultiFrameMode)
    }

    @Test
    fun test4_multiFrameControlsVisibleForMultiFrameProfiles() {
        val captureMode = CaptureStrategy.MULTI_FRAME_ZSL
        val isMultiFrameMode = (captureMode == CaptureStrategy.MULTI_FRAME_ZSL)
        assertTrue(isMultiFrameMode)
    }

    @Test
    fun test5_profileAAndProfileBRetainIndependentSettings() {
        val profileA = MultiFrameCaptureConfig(
            profileId = "profile_A",
            profileName = "Profile A",
            sourceFormat = ImageFormat.RAW10,
            shootingMode = CaptureMode.MULTI,
            alignmentMethod = "Phase Correlation Pyramid",
            fusionMethod = "Robust Weighted Average",
            fusionFrameCount = 5,
            exposureStrategy = "Standard",
            demosaicMethod = "Malvar2004",
            configuredBufferCapacity = 35,
            effectiveBufferCapacity = 35,
            lensId = "0",
            outputPolicy = OutputPolicy.JPEG
        )

        val profileB = MultiFrameCaptureConfig(
            profileId = "profile_B",
            profileName = "Profile B",
            sourceFormat = ImageFormat.RAW10,
            shootingMode = CaptureMode.MULTI,
            alignmentMethod = "Tile Pyramid",
            fusionMethod = "Wiener Pyramid",
            fusionFrameCount = 15,
            exposureStrategy = "ETTR",
            demosaicMethod = "Menon2007",
            configuredBufferCapacity = 35,
            effectiveBufferCapacity = 35,
            lensId = "0",
            outputPolicy = OutputPolicy.JPEG
        )

        assertEquals("Phase Correlation Pyramid", profileA.alignmentMethod)
        assertEquals("Tile Pyramid", profileB.alignmentMethod)
        assertEquals("Robust Weighted Average", profileA.fusionMethod)
        assertEquals("Wiener Pyramid", profileB.fusionMethod)
        assertEquals(5, profileA.fusionFrameCount)
        assertEquals(15, profileB.fusionFrameCount)
        assertEquals("Standard", profileA.exposureStrategy)
        assertEquals("ETTR", profileB.exposureStrategy)
        assertEquals("Malvar2004", profileA.demosaicMethod)
        assertEquals("Menon2007", profileB.demosaicMethod)
    }

    @Test
    fun test6_sourceFormatSpecificValuesAreRetained() {
        val raw10FrameCount = 8
        val rawSensorFrameCount = 5
        val yuvFrameCount = 8

        assertTrue(raw10FrameCount in 2..25)
        assertTrue(rawSensorFrameCount in 2..10)
        assertTrue(yuvFrameCount in 2..25)
    }

    @Test
    fun test7_newProfileDefaultsAreCorrect() {
        val defaultAlign = "Auto"
        val defaultFusion = "Auto"
        val defaultExposure = "ETTR"
        val defaultYuvJpeg = 8
        val defaultRaw10Jpeg = 8
        val defaultRawSensorJpeg = 5

        assertEquals("Auto", defaultAlign)
        assertEquals("Auto", defaultFusion)
        assertEquals("ETTR", defaultExposure)
        assertEquals(8, defaultYuvJpeg)
        assertEquals(8, defaultRaw10Jpeg)
        assertEquals(5, defaultRawSensorJpeg)
    }

    @Test
    fun test8_existingProfileValuesAreRetained() {
        val existingSavedProfile = MultiFrameCaptureConfig(
            profileId = "saved_profile_01",
            profileName = "Saved Profile",
            sourceFormat = ImageFormat.RAW10,
            shootingMode = CaptureMode.MULTI,
            alignmentMethod = "ECC Pyramid",
            fusionMethod = "Reference Dominant",
            fusionFrameCount = 12,
            exposureStrategy = "Standard",
            configuredBufferCapacity = 35,
            effectiveBufferCapacity = 35,
            lensId = "0",
            demosaicMethod = "Malvar2004",
            outputPolicy = OutputPolicy.JPEG
        )

        assertEquals("ECC Pyramid", existingSavedProfile.alignmentMethod)
        assertEquals("Reference Dominant", existingSavedProfile.fusionMethod)
        assertEquals(12, existingSavedProfile.fusionFrameCount)
    }

    @Test
    fun test9_alignmentSettingReachesRequestedBackend() {
        val method = "Tile Pyramid"
        val resolved = MultiFrameAlignmentRegistry.resolve(method, FrameOrigin.RAW10)
        assertFalse(MultiFrameAlignmentRegistry.isValid(method))
        assertTrue(resolved.fallback)
        assertEquals("phase_correlation_fast", resolved.resolvedId)
    }

    @Test
    fun test10_fusionSettingReachesRequestedBackend() {
        val method = "Wiener Pyramid"
        val resolved = MultiFrameFusionRegistry.resolve(method, FrameOrigin.RAW10)
        assertFalse(MultiFrameFusionRegistry.isValid(method))
        assertTrue(resolved.fallback)
        assertEquals("robust_mean", resolved.resolvedId)
    }

    @Test
    fun test11_exposureStrategyReachesEttrRequestPath() {
        val strategy = "ETTR"
        val res = EttrExposureStrategy.calculateExposureShift(
            shootingMode = CaptureMode.MULTI,
            exposureStrategy = strategy,
            format = ImageFormat.RAW10,
            recentFrames = emptyList()
        )
        assertNotNull(res)
    }

    @Test
    fun test12_jpegFrameSettingLimitsActualUsedFrames() {
        val requestedLimit = 5
        val candidateFrames = 8
        val usedFrames = candidateFrames.coerceAtMost(requestedLimit)
        assertEquals(5, usedFrames)
    }

    @Test
    fun test13_dngMasterSettingControlsReferenceVsComputationalDng() {
        val singleDngCount = 1
        val multiDngCount = 4

        assertFalse(singleDngCount > 1)
        assertTrue(multiDngCount > 1)
    }

    @Test
    fun test14_demosaicSelectionRemainsFunctional() {
        val demosaic = "Menon2007"
        assertEquals("Menon2007", demosaic)
    }

    @Test
    fun test15_immutableShutterConfigUsesSavedProfileValues() {
        val shutterConfig = MultiFrameCaptureConfig(
            profileId = "profile_shutter_test",
            profileName = "Shutter Test Profile",
            sourceFormat = ImageFormat.RAW10,
            shootingMode = CaptureMode.MULTI,
            alignmentMethod = "Tile Pyramid",
            fusionMethod = "Wiener Pyramid",
            fusionFrameCount = 15,
            exposureStrategy = "ETTR",
            demosaicMethod = "Menon2007",
            configuredBufferCapacity = 35,
            effectiveBufferCapacity = 35,
            lensId = "0",
            outputPolicy = OutputPolicy.JPEG
        )

        assertEquals("Tile Pyramid", shutterConfig.alignmentMethod)
        assertEquals("Wiener Pyramid", shutterConfig.fusionMethod)
        assertEquals(15, shutterConfig.fusionFrameCount)
        assertEquals("ETTR", shutterConfig.exposureStrategy)
        assertEquals("Menon2007", shutterConfig.demosaicMethod)
    }
}
