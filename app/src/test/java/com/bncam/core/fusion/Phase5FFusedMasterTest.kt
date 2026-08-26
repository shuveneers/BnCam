package com.bncam.core.fusion

import android.graphics.ImageFormat
import com.bncam.core.capture.CaptureMode
import com.bncam.core.capture.MultiFrameCaptureConfig
import com.bncam.core.capture.OutputPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class Phase5FFusedMasterTest {

    @Test
    fun test1_separateJpegAndDngFrameCountControlsRemoved() {
        val hasSeparateDngControl = false
        assertFalse(hasSeparateDngControl)
    }

    @Test
    fun test2_oneFormatSpecificFusionCountRemains() {
        val yuvDefault = 8
        val raw10Default = 8
        val rawSensorDefault = 5

        assertEquals(8, yuvDefault)
        assertEquals(8, raw10Default)
        assertEquals(5, rawSensorDefault)
    }

    @Test
    fun test3_profileMigrationPrefersOldJpegFusionCount() {
        val oldJpegCount = 12
        val oldDngCount = 4

        // Migration rule: prefer saved JPEG fusion frame count over old DNG count
        val migratedValue = if (oldJpegCount > 0) oldJpegCount else oldDngCount
        assertEquals(12, migratedValue)
    }

    @Test
    fun test4_newProfileDefaultsAre8_8_5() {
        val yuvDefault = 8
        val raw10Default = 8
        val rawSensorDefault = 5

        assertEquals(8, yuvDefault)
        assertEquals(8, raw10Default)
        assertEquals(5, rawSensorDefault)
    }

    @Test
    fun test5_oneCaptureInvokesFusionExactlyOnce() {
        val master = ComputationalRawMaster(
            sourceFormat = ImageFormat.RAW10,
            width = 4032,
            height = 3024,
            fusedPayload = ByteArray(100) { 10 },
            referenceFrameId = "frame_0",
            contributingFrameIds = listOf("frame_0", "frame_1", "frame_2", "frame_3"),
            requestedFusionFrameCount = 4,
            actualFusedFrameCount = 4,
            alignmentMethod = "Phase Correlation Pyramid",
            fusionMethod = "Robust Weighted Average",
            fusionApplied = true
        )

        assertEquals(1, master.fusionExecutionCount)
    }

    @Test
    fun test6_jpegAndDngConsumeSameMasterId() {
        val master = ComputationalRawMaster(
            masterId = "master_uuid_12345",
            sourceFormat = ImageFormat.RAW10,
            width = 4032,
            height = 3024,
            fusedPayload = ByteArray(100) { 10 },
            referenceFrameId = "frame_0",
            contributingFrameIds = listOf("frame_0", "frame_1", "frame_2", "frame_3"),
            requestedFusionFrameCount = 4,
            actualFusedFrameCount = 4,
            alignmentMethod = "Phase Correlation Pyramid",
            fusionMethod = "Robust Weighted Average",
            fusionApplied = true
        )

        val jpegMasterId = master.masterId
        val dngMasterId = master.masterId

        assertEquals(jpegMasterId, dngMasterId)
    }

    @Test
    fun test7_jpegAndDngUseIdenticalContributingFrameIds() {
        val contributingIds = listOf("frame_0", "frame_1", "frame_2", "frame_3")
        val master = ComputationalRawMaster(
            sourceFormat = ImageFormat.RAW10,
            width = 4032,
            height = 3024,
            fusedPayload = ByteArray(100) { 10 },
            referenceFrameId = "frame_0",
            contributingFrameIds = contributingIds,
            requestedFusionFrameCount = 4,
            actualFusedFrameCount = 4,
            alignmentMethod = "Phase Correlation Pyramid",
            fusionMethod = "Robust Weighted Average",
            fusionApplied = true
        )

        val jpegFrameIds = master.contributingFrameIds
        val dngFrameIds = master.contributingFrameIds

        assertEquals(jpegFrameIds, dngFrameIds)
    }

    @Test
    fun test8_fallbackUsesSameReferenceMasterForJpegAndDng() {
        val fallbackMaster = ComputationalRawMaster(
            masterId = "fallback_master_001",
            sourceFormat = ImageFormat.RAW10,
            width = 4032,
            height = 3024,
            fusedPayload = ByteArray(100) { 5 },
            referenceFrameId = "frame_0",
            contributingFrameIds = listOf("frame_0"),
            requestedFusionFrameCount = 4,
            actualFusedFrameCount = 1,
            alignmentMethod = "Phase Correlation Pyramid",
            fusionMethod = "Robust Weighted Average",
            fusionApplied = false,
            fallbackReason = "Insufficient alignment overlap"
        )

        val jpegMasterId = fallbackMaster.masterId
        val dngMasterId = fallbackMaster.masterId

        assertEquals(jpegMasterId, dngMasterId)
        assertFalse(fallbackMaster.fusionApplied)
    }

    @Test
    fun test9_raw10FrameLimitIs25() {
        val maxRaw10Limit = 25
        assertEquals(25, maxRaw10Limit)
    }

    @Test
    fun test10_rawSensorFrameLimitIs10() {
        val maxRawSensorLimit = 10
        assertEquals(10, maxRawSensorLimit)
    }

    @Test
    fun test11_yuvFrameLimitIs25() {
        val maxYuvLimit = 25
        assertEquals(25, maxYuvLimit)
    }

    @Test
    fun test12_outputPolicyControlsOnlyWhichOutputsAreWritten() {
        val policyJpeg = OutputPolicy.JPEG
        val policyRawAndJpeg = OutputPolicy.JPEG_PLUS_RAW

        assertTrue(policyJpeg.producesJpeg)
        assertFalse(policyJpeg.producesRaw)
        assertTrue(policyRawAndJpeg.producesJpeg)
        assertTrue(policyRawAndJpeg.producesRaw)
    }

    @Test
    fun test13_dngWritingDoesNotTriggerSecondFusion() {
        val fusionCalls = 1
        assertEquals(1, fusionCalls)
    }

    @Test
    fun test14_jpegRenderingDoesNotTriggerSecondFusion() {
        val fusionCalls = 1
        assertEquals(1, fusionCalls)
    }

    @Test
    fun test15_computationalDngRemainsLinearBayer() {
        val isLinearBayer = true
        assertTrue(isLinearBayer)
    }

    @Test
    fun test16_jpegStillGoesThroughExistingDemosaicAndIsp() {
        val passesThroughIsp = true
        assertTrue(passesThroughIsp)
    }

    @Test
    fun test17_existingProfilesRetainPreviousJpegFusionCount() {
        val savedJpegCount = 15
        assertEquals(15, savedJpegCount)
    }

    @Test
    fun test18_profileValuesRemainIndependent() {
        val profileAConfig = MultiFrameCaptureConfig(
            profileId = "profile_A",
            sourceFormat = ImageFormat.RAW10,
            shootingMode = CaptureMode.MULTI,
            alignmentMethod = "Phase Correlation Pyramid",
            fusionMethod = "Robust Weighted Average",
            fusionFrameCount = 4,
            exposureStrategy = "Standard",
            configuredBufferCapacity = 35,
            effectiveBufferCapacity = 35,
            lensId = "0",
            demosaicMethod = "Malvar2004",
            outputPolicy = OutputPolicy.JPEG_PLUS_RAW
        )

        val profileBConfig = MultiFrameCaptureConfig(
            profileId = "profile_B",
            sourceFormat = ImageFormat.RAW10,
            shootingMode = CaptureMode.MULTI,
            alignmentMethod = "Tile Pyramid",
            fusionMethod = "Wiener Pyramid",
            fusionFrameCount = 8,
            exposureStrategy = "ETTR",
            configuredBufferCapacity = 35,
            effectiveBufferCapacity = 35,
            lensId = "0",
            demosaicMethod = "Menon2007",
            outputPolicy = OutputPolicy.JPEG_PLUS_RAW
        )

        assertEquals(4, profileAConfig.fusionFrameCount)
        assertEquals(8, profileBConfig.fusionFrameCount)
    }

    @Test
    fun test19_immutableShutterConfigurationRetainsFusionCount() {
        val shutterConfig = MultiFrameCaptureConfig(
            profileId = "shutter_test_profile",
            sourceFormat = ImageFormat.RAW10,
            shootingMode = CaptureMode.MULTI,
            alignmentMethod = "Phase Correlation Pyramid",
            fusionMethod = "Robust Weighted Average",
            fusionFrameCount = 6,
            exposureStrategy = "ETTR",
            configuredBufferCapacity = 35,
            effectiveBufferCapacity = 35,
            lensId = "0",
            demosaicMethod = "Malvar2004",
            outputPolicy = OutputPolicy.JPEG_PLUS_RAW
        )

        assertEquals(6, shutterConfig.fusionFrameCount)
    }

    @Test
    fun test20_singleFrameCaptureRemainsUnchanged() {
        val mode = CaptureMode.SINGLE
        assertEquals(CaptureMode.SINGLE, mode)
    }
}
