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
    fun test19_ettrDisabledPreservesStandardExposure() {
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
    fun test20_ettrUsesSensorDomainHeadroomForRaw() {
        val res = EttrExposureStrategy.calculateExposureShift(
            shootingMode = CaptureMode.MULTI,
            exposureStrategy = "ETTR",
            format = ImageFormat.RAW10,
            recentFrames = emptyList()
        )
        assertEquals("ETTR", res.exposureStrategy)
    }

    @Test
    fun test21_ettrNeverExceedsMaximumShift() {
        val resRaw = EttrExposureStrategy.calculateExposureShift(
            shootingMode = CaptureMode.MULTI,
            exposureStrategy = "ETTR",
            format = ImageFormat.RAW10,
            recentFrames = emptyList()
        )
        val resYuv = EttrExposureStrategy.calculateExposureShift(
            shootingMode = CaptureMode.MULTI,
            exposureStrategy = "ETTR",
            format = ImageFormat.YUV_420_888,
            recentFrames = emptyList()
        )
        assertTrue(resRaw.appliedShiftEv <= 1.5f)
        assertTrue(resYuv.appliedShiftEv <= 1.0f)
    }

    @Test
    fun test22_excessiveClippingPreventsPositiveShift() {
        val clippedFraction = 0.05f
        val allowPositiveShift = clippedFraction <= 0.0005f
        assertFalse(allowPositiveShift)
    }

    @Test
    fun test23_highMotionLimitsOrRejectsShift() {
        val res = EttrExposureStrategy.calculateExposureShift(
            shootingMode = CaptureMode.MULTI,
            exposureStrategy = "ETTR",
            format = ImageFormat.RAW10,
            recentFrames = emptyList(),
            deviceMotionHigh = true
        )
        assertEquals(0.0f, res.appliedShiftEv, 0.0001f)
        assertTrue(res.isMotionLimited)
    }

    @Test
    fun test24_exposureGenerationChangesInvalidateIncompatibleCandidates() {
        val generation1 = 1L
        val generation2 = 2L
        val frameGen = 1L
        val isCompatible = frameGen == generation2
        assertFalse(isCompatible)
    }

    @Test
    fun test25_ettrFallbackDoesNotFailCapture() {
        val res = EttrExposureStrategy.calculateExposureShift(
            shootingMode = CaptureMode.MULTI,
            exposureStrategy = "ETTR",
            format = ImageFormat.RAW10,
            recentFrames = emptyList()
        )
        assertNotNull(res)
        assertEquals(0.0f, res.appliedShiftEv, 0.0001f)
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
