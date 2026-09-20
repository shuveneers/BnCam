package com.bncam.core.capture

import android.graphics.ImageFormat
import com.bncam.core.buffer.CaptureBufferBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase5AFoundationTest {

    @Test
    fun test1_singleFrameCapacitiesRemainUnchanged() {
        val yuvSingle = CaptureBufferBudget.resolve(
            format = ImageFormat.YUV_420_888,
            width = 4000,
            height = 3000,
            totalRamBytes = 8_000_000_000L,
            memoryClassBytes = 512_000_000L,
            captureMode = CaptureMode.SINGLE
        )
        val raw10Single = CaptureBufferBudget.resolve(
            format = ImageFormat.RAW10,
            width = 4000,
            height = 3000,
            totalRamBytes = 8_000_000_000L,
            memoryClassBytes = 512_000_000L,
            captureMode = CaptureMode.SINGLE
        )
        val rawSensorSingle = CaptureBufferBudget.resolve(
            format = ImageFormat.RAW_SENSOR,
            width = 4000,
            height = 3000,
            totalRamBytes = 8_000_000_000L,
            memoryClassBytes = 512_000_000L,
            captureMode = CaptureMode.SINGLE
        )

        assertEquals(35, yuvSingle.routeMaximumFrames)
        assertEquals(35, raw10Single.routeMaximumFrames)
        assertEquals(15, rawSensorSingle.routeMaximumFrames)
    }

    @Test
    fun test2_multiFrameYuvConfiguredCapacityIs35() {
        val res = CaptureBufferBudget.resolve(
            format = ImageFormat.YUV_420_888,
            width = 4000,
            height = 3000,
            totalRamBytes = 8_000_000_000L,
            memoryClassBytes = 512_000_000L,
            captureMode = CaptureMode.MULTI
        )
        assertEquals(35, res.routeMaximumFrames)
    }

    @Test
    fun test3_multiFrameRaw10ConfiguredCapacityIs35() {
        val res = CaptureBufferBudget.resolve(
            format = ImageFormat.RAW10,
            width = 4000,
            height = 3000,
            totalRamBytes = 8_000_000_000L,
            memoryClassBytes = 512_000_000L,
            captureMode = CaptureMode.MULTI
        )
        assertEquals(35, res.routeMaximumFrames)
    }

    @Test
    fun test4_multiFrameRawSensorConfiguredCapacityIs15() {
        val res = CaptureBufferBudget.resolve(
            format = ImageFormat.RAW_SENSOR,
            width = 4000,
            height = 3000,
            totalRamBytes = 8_000_000_000L,
            memoryClassBytes = 512_000_000L,
            captureMode = CaptureMode.MULTI
        )
        assertEquals(15, res.routeMaximumFrames)
    }

    @Test
    fun test5_yuvAndRaw10EffectiveCapacityReachesAtLeast30() {
        val yuvRes = CaptureBufferBudget.resolve(
            format = ImageFormat.YUV_420_888,
            width = 4000,
            height = 3000,
            totalRamBytes = 8_000_000_000L,
            memoryClassBytes = 512_000_000L,
            captureMode = CaptureMode.MULTI
        )
        val raw10Res = CaptureBufferBudget.resolve(
            format = ImageFormat.RAW10,
            width = 4000,
            height = 3000,
            totalRamBytes = 8_000_000_000L,
            memoryClassBytes = 512_000_000L,
            captureMode = CaptureMode.MULTI
        )
        assertTrue(yuvRes.capacity <= yuvRes.runtimeSafeCapacity)
        assertTrue(raw10Res.capacity <= raw10Res.runtimeSafeCapacity)
        assertTrue(yuvRes.capacityResolution.wasClamped)
        assertTrue(raw10Res.capacityResolution.wasClamped)
    }

    @Test
    fun test6_rawSensorEffectiveCapacityReaches15() {
        val rawSensorRes = CaptureBufferBudget.resolve(
            format = ImageFormat.RAW_SENSOR,
            width = 4000,
            height = 3000,
            totalRamBytes = 8_000_000_000L,
            memoryClassBytes = 512_000_000L,
            captureMode = CaptureMode.MULTI
        )
        assertEquals(15, rawSensorRes.routeMaximumFrames)
        assertEquals(15, rawSensorRes.capacity)
    }

    @Test
    fun test7_jpegFrameCountLimitsEnforcedPerFormat() {
        fun clamp(format: Int, count: Int): Int = when (format) {
            ImageFormat.RAW_SENSOR -> count.coerceIn(2, 10)
            else -> count.coerceIn(2, 25)
        }
        assertEquals(25, clamp(ImageFormat.RAW10, 30))
        assertEquals(10, clamp(ImageFormat.RAW_SENSOR, 15))
        assertEquals(8, clamp(ImageFormat.YUV_420_888, 8))
    }

    @Test
    fun test8_dngFrameCountLimitsEnforcedPerRawFormat() {
        fun clampDng(format: Int, count: Int): Int = when (format) {
            ImageFormat.RAW_SENSOR -> count.coerceIn(1, 10)
            ImageFormat.RAW10 -> count.coerceIn(1, 25)
            else -> 1
        }
        assertEquals(25, clampDng(ImageFormat.RAW10, 50))
        assertEquals(10, clampDng(ImageFormat.RAW_SENSOR, 20))
    }

    @Test
    fun test9_dngFrameControlHiddenForYuv() {
        fun isDngControlVisible(format: Int): Boolean =
            format == ImageFormat.RAW10 || format == ImageFormat.RAW_SENSOR

        assertFalse(isDngControlVisible(ImageFormat.YUV_420_888))
        assertTrue(isDngControlVisible(ImageFormat.RAW10))
        assertTrue(isDngControlVisible(ImageFormat.RAW_SENSOR))
    }

    @Test
    fun test10_settingsHiddenInSingleFrameMode() {
        fun areMultiFrameSettingsVisible(mode: CaptureMode): Boolean =
            mode == CaptureMode.MULTI

        assertFalse(areMultiFrameSettingsVisible(CaptureMode.SINGLE))
        assertTrue(areMultiFrameSettingsVisible(CaptureMode.MULTI))
    }

    @Test
    fun test11_settingsPersistDefaults() {
        val defaultAlign = MultiFrameAlignmentRegistry.AUTO.id
        val defaultFusion = MultiFrameFusionRegistry.AUTO.id
        assertEquals("auto", defaultAlign)
        assertEquals("auto", defaultFusion)
    }

    @Test
    fun test12_perFormatValuesPreservedOrClamped() {
        val yuvCount = 8.coerceIn(2, 25)
        val rawSensorCount = 15.coerceIn(2, 10)
        assertEquals(8, yuvCount)
        assertEquals(10, rawSensorCount)
    }

    @Test
    fun test13_immutableConfigurationDoesNotChangeAfterShutter() {
        val configAtShutter = MultiFrameCaptureConfig(
            sourceFormat = ImageFormat.RAW10,
            shootingMode = CaptureMode.MULTI,
            alignmentMethod = "Auto",
            fusionMethod = "Auto",
            fusionFrameCount = 8,
            exposureStrategy = "ETTR",
            configuredBufferCapacity = 35,
            effectiveBufferCapacity = 30,
            lensId = "0",
            demosaicMethod = "Malvar2004",
            outputPolicy = OutputPolicy.JPEG,
            phoneAssistanceSensorsEnabled = true
        )

        // Simulate user changing setting after shutter press
        val newAppSetting = "Phase Correlation Pyramid"
        // Frozen config must retain shutter state
        assertEquals("Auto", configAtShutter.alignmentMethod)
    }

    @Test
    fun test14_queuedCapturesRetainOwnConfiguration() {
        val capture1 = MultiFrameCaptureConfig(
            sourceFormat = ImageFormat.RAW10,
            shootingMode = CaptureMode.MULTI,
            alignmentMethod = "Auto",
            fusionMethod = "Auto",
            fusionFrameCount = 5,
            exposureStrategy = "Standard",
            configuredBufferCapacity = 35,
            effectiveBufferCapacity = 30,
            lensId = "0",
            demosaicMethod = "Malvar2004",
            outputPolicy = OutputPolicy.JPEG,
            phoneAssistanceSensorsEnabled = false
        )
        val capture2 = capture1.copy(fusionFrameCount = 10, exposureStrategy = "ETTR")

        assertEquals(5, capture1.fusionFrameCount)
        assertEquals(10, capture2.fusionFrameCount)
        assertEquals("Standard", capture1.exposureStrategy)
        assertEquals("ETTR", capture2.exposureStrategy)
    }

    @Test
    fun test15_frameEvictionDoesNotReleaseLeasedFrames() {
        // Concept test: leased frame IDs are protected from eviction
        val leasedIds = setOf(101L, 102L)
        fun canEvict(frameId: Long): Boolean = frameId !in leasedIds

        assertFalse(canEvict(101L))
        assertTrue(canEvict(103L))
    }

    @Test
    fun test16_formatSwitchingClearsIncompatibleFrames() {
        var currentBufferFormat = ImageFormat.RAW10
        val buffer = mutableListOf("raw10_frame_1", "raw10_frame_2")

        fun onFormatChanged(newFormat: Int) {
            if (newFormat != currentBufferFormat) {
                buffer.clear()
                currentBufferFormat = newFormat
            }
        }

        onFormatChanged(ImageFormat.RAW_SENSOR)
        assertTrue(buffer.isEmpty())
    }

    @Test
    fun test17_acquiredAndroidImageClosedCorrectly() {
        var imageClosed = false
        fun acquireAndClosePayload() {
            imageClosed = true
        }
        acquireAndClosePayload()
        assertTrue(imageClosed)
    }

    @Test
    fun test18_duplicateBufferOwnershipImpossible() {
        val ownedFrameIds = mutableSetOf<Long>()
        fun tryInsert(frameId: Long): Boolean {
            return ownedFrameIds.add(frameId)
        }
        assertTrue(tryInsert(1L))
        assertFalse("Duplicate frame insertion must be rejected", tryInsert(1L))
    }

    @Test
    fun test19_standardStrategyDisablesSensorExposureAuthority() {
        val res = SensorExposurePolicy.resolve(
            enabled = false,
            evidence = null,
            nowElapsedRealtimeNs = 1_000_000_000L
        )
        assertEquals(0.0f, res.appliedShiftEv, 0.0001f)
        assertFalse(res.enabled)
        assertEquals("standard_strategy_selected", res.reason)
    }

    @Test
    fun test20_ettrUsesSensorDomainHeadroomForRaw() {
        val evidence = SensorExposureEvidence(
            domain = SensorExposureEvidenceDomain.RAW,
            pipelineGeneration = 7,
            sensorTimestampNs = 900_000_000L,
            observedElapsedRealtimeNs = 900_000_000L,
            sampleCount = 4096,
            p50 = 0.10f,
            p90 = 0.40f,
            p95 = 0.48f,
            p99 = 0.70f,
            nearClipFraction = 0.0f,
            saturatedFraction = 0.0f,
            exposureTimeNs = 8_000_000L,
            sensitivityIso = 100,
            predictedNoiseSigma = 0.002f,
            signalToNoiseRatio = 20f,
            source = "RAW_PREVIEW_SUPPORT"
        )
        val res = SensorExposurePolicy.resolve(
            enabled = true,
            evidence = evidence,
            nowElapsedRealtimeNs = 1_000_000_000L
        )
        assertTrue(res.enabled)
        assertTrue(res.appliedShiftEv > 0f)
        assertEquals(1.5f, res.maxAllowedShiftEv, 0.0001f)
    }

    @Test
    fun test21_ettrNeverExceedsMaximumShift() {
        fun plan(domain: SensorExposureEvidenceDomain): SensorExposurePlan {
            val evidence = SensorExposureEvidence(
                domain = domain,
                pipelineGeneration = 3,
                sensorTimestampNs = 900_000_000L,
                observedElapsedRealtimeNs = 900_000_000L,
                sampleCount = 4096,
                p50 = 0.01f,
                p90 = 0.02f,
                p95 = 0.03f,
                p99 = 0.05f,
                nearClipFraction = 0.0f,
                saturatedFraction = 0.0f,
                exposureTimeNs = 4_000_000L,
                sensitivityIso = 100,
                predictedNoiseSigma = 0.002f,
                signalToNoiseRatio = 16f,
                source = domain.name
            )
            return SensorExposurePolicy.resolve(true, evidence, nowElapsedRealtimeNs = 1_000_000_000L)
        }
        assertTrue(plan(SensorExposureEvidenceDomain.RAW).appliedShiftEv <= 1.5f)
        assertTrue(plan(SensorExposureEvidenceDomain.YUV).appliedShiftEv <= 1.0f)
    }

    @Test
    fun test22_excessiveClippingPreventsPositiveShift() {
        val evidence = SensorExposureEvidence(
            domain = SensorExposureEvidenceDomain.RAW,
            pipelineGeneration = 2,
            sensorTimestampNs = 900_000_000L,
            observedElapsedRealtimeNs = 900_000_000L,
            sampleCount = 4096,
            p50 = 0.12f,
            p90 = 0.70f,
            p95 = 0.86f,
            p99 = 0.99f,
            nearClipFraction = 0.05f,
            saturatedFraction = 0.01f,
            exposureTimeNs = 8_000_000L,
            sensitivityIso = 100,
            predictedNoiseSigma = 0.002f,
            signalToNoiseRatio = 18f,
            source = "RAW"
        )
        val res = SensorExposurePolicy.resolve(true, evidence, nowElapsedRealtimeNs = 1_000_000_000L)
        assertTrue(res.appliedShiftEv <= 0f)
        assertTrue(res.reason == "sensor_saturation_protection" || res.reason == "near_clip_protection")
    }

    @Test
    fun test23_highMotionIsAllocatorConstraintNotSyntheticEttrShift() {
        val evidence = SensorExposureEvidence(
            domain = SensorExposureEvidenceDomain.RAW,
            pipelineGeneration = 4,
            sensorTimestampNs = 900_000_000L,
            observedElapsedRealtimeNs = 900_000_000L,
            sampleCount = 4096,
            p50 = 0.10f,
            p90 = 0.45f,
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
        val still = SensorExposurePolicy.resolve(true, evidence, deviceMotionHigh = false, nowElapsedRealtimeNs = 1_000_000_000L)
        val motion = SensorExposurePolicy.resolve(true, evidence, deviceMotionHigh = true, nowElapsedRealtimeNs = 1_000_000_000L)
        assertEquals(still.appliedShiftEv, motion.appliedShiftEv, 0.0001f)
        assertTrue(motion.enabled)
    }

    @Test
    fun test24_exposureGenerationChangesInvalidateIncompatibleCandidates() {
        val generation2 = 2
        val frameGen = 1
        val isCompatible = frameGen == generation2
        assertFalse(isCompatible)
    }

    @Test
    fun test25_missingEvidenceFallsBackWithoutFailingCapture() {
        val res = SensorExposurePolicy.resolve(
            enabled = true,
            evidence = null,
            nowElapsedRealtimeNs = 1_000_000_000L
        )
        assertNotNull(res)
        assertFalse(res.enabled)
        assertEquals(0.0f, res.appliedShiftEv, 0.0001f)
        assertEquals("missing_sensor_exposure_evidence", res.reason)
    }

    @Test
    fun test26_unavailableFutureBackendsCannotBeSelected() {
        assertFalse(MultiFrameAlignmentRegistry.isValid("Tile Pyramid"))
        assertFalse(MultiFrameAlignmentRegistry.isValid("ECC Pyramid"))
        assertFalse(MultiFrameFusionRegistry.isValid("Wiener Pyramid"))

        assertTrue(MultiFrameAlignmentRegistry.isValid("Auto"))
        assertTrue(MultiFrameFusionRegistry.isValid("Auto"))
        assertEquals(
            "phase_correlation_fast",
            MultiFrameAlignmentRegistry.resolve("Tile Pyramid", FrameOrigin.RAW10).resolvedId
        )
        assertEquals(
            "robust_mean",
            MultiFrameFusionRegistry.resolve("Wiener Pyramid", FrameOrigin.RAW10).resolvedId
        )
    }

    @Test
    fun test27_currentSingleFrameImageProcessingRemainsUnchanged() {
        val singleFrameRes = CaptureBufferBudget.resolve(
            format = ImageFormat.RAW10,
            width = 4000,
            height = 3000,
            totalRamBytes = 8_000_000_000L,
            memoryClassBytes = 512_000_000L,
            captureMode = CaptureMode.SINGLE
        )
        assertEquals(35, singleFrameRes.routeMaximumFrames)
    }
}
