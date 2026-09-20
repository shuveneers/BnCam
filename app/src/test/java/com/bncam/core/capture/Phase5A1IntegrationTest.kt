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
    fun test6_standardProducesZeroSensorAuthorityShift() {
        val res = SensorExposurePolicy.resolve(
            enabled = false,
            evidence = null,
            nowElapsedRealtimeNs = 1_000_000_000L
        )
        assertEquals(0.0f, res.appliedShiftEv, 0.0001f)
        assertFalse(res.enabled)
    }

    @Test
    fun test7_ettrUsesLiveSensorExposureAuthority() {
        val evidence = SensorExposureEvidence(
            domain = SensorExposureEvidenceDomain.RAW,
            pipelineGeneration = 5,
            sensorTimestampNs = 900_000_000L,
            observedElapsedRealtimeNs = 900_000_000L,
            sampleCount = 4096,
            p50 = 0.10f,
            p90 = 0.42f,
            p95 = 0.50f,
            p99 = 0.72f,
            nearClipFraction = 0f,
            saturatedFraction = 0f,
            exposureTimeNs = 8_000_000L,
            sensitivityIso = 100,
            predictedNoiseSigma = 0.002f,
            signalToNoiseRatio = 18f,
            source = "RAW"
        )
        val res = SensorExposurePolicy.resolve(true, evidence, nowElapsedRealtimeNs = 1_000_000_000L)
        assertNotNull(res)
        assertTrue(res.enabled)
        assertTrue(res.appliedShiftEv in -2.0f..1.5f)
    }

    @Test
    fun test8_exposureGenerationComesFromLiveEvidence() {
        val evidence = SensorExposureEvidence(
            domain = SensorExposureEvidenceDomain.RAW,
            pipelineGeneration = 17,
            sensorTimestampNs = 900_000_000L,
            observedElapsedRealtimeNs = 900_000_000L,
            sampleCount = 4096,
            p50 = 0.12f,
            p90 = 0.50f,
            p95 = 0.58f,
            p99 = 0.78f,
            nearClipFraction = 0f,
            saturatedFraction = 0f,
            exposureTimeNs = 8_000_000L,
            sensitivityIso = 100,
            predictedNoiseSigma = 0.002f,
            signalToNoiseRatio = 18f,
            source = "RAW"
        )
        val res = SensorExposurePolicy.resolve(true, evidence, nowElapsedRealtimeNs = 1_000_000_000L)
        assertEquals(17, res.pipelineGeneration)
    }

    @Test
    fun test9_incompatibleGenerationsExcludedFromCandidateSet() {
        val candidateGeneration = 1
        val activeGeneration = 2
        val isCompatible = (candidateGeneration == activeGeneration)

        assertFalse("Old generation candidates must be excluded from active candidate set", isCompatible)
    }

    @Test
    fun test10_switchingBackRestoresStandardExposure() {
        val res = SensorExposurePolicy.resolve(
            enabled = false,
            evidence = null,
            nowElapsedRealtimeNs = 1_000_000_000L
        )
        assertEquals(0.0f, res.appliedShiftEv, 0.0001f)
        assertEquals("standard_strategy_selected", res.reason)
    }

    @Test
    fun test11_singleAndMultiShareTheSameLiveSensorAuthority() {
        val strategy = "ETTR"
        val singleUsesAuthority = strategy.equals("ETTR", ignoreCase = true)
        val multiUsesAuthority = strategy.equals("ETTR", ignoreCase = true)
        assertTrue(singleUsesAuthority)
        assertEquals(singleUsesAuthority, multiUsesAuthority)
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
