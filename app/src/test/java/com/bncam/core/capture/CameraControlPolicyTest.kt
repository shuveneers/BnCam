package com.bncam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraControlPolicyTest {
    @Test
    fun standardMeteringModesMatchPhotonCameraRegionContract() {
        val auto = CameraMeteringPolicy.plan(MeteringMode.AUTO_DEFAULT_AE, maxAeRegions = 3)
        val center = CameraMeteringPolicy.plan(MeteringMode.CENTER_WEIGHTED, maxAeRegions = 3)
        val average = CameraMeteringPolicy.plan(MeteringMode.FRAME_AVERAGE, maxAeRegions = 3)
        val spot = CameraMeteringPolicy.plan(MeteringMode.SPOT, maxAeRegions = 3)

        assertTrue(auto.restoreInitialAeRegions)
        assertTrue(auto.regions.isEmpty())

        assertEquals(3, center.regions.size)
        assertEquals(listOf(200, 300, 500), center.regions.map { it.weight })
        assertEquals(0.70f, center.regions[0].right - center.regions[0].left, 0.0001f)
        assertEquals(0.45f, center.regions[1].right - center.regions[1].left, 0.0001f)
        assertEquals(0.20f, center.regions[2].right - center.regions[2].left, 0.0001f)

        assertEquals(1, average.regions.size)
        assertEquals(NormalizedMeteringRegion(0f, 0f, 1f, 1f, 1000), average.regions.single())

        assertEquals(1, spot.regions.size)
        assertEquals(1000, spot.regions.single().weight)
        assertEquals(0.158f, spot.regions.single().right - spot.regions.single().left, 0.0001f)
        assertEquals(0.158f, spot.regions.single().bottom - spot.regions.single().top, 0.0001f)
    }

    @Test
    fun centerWeightedFallsBackToSingleSixtyPercentRegionBelowThreeRegionCapability() {
        val one = CameraMeteringPolicy.plan(MeteringMode.CENTER_WEIGHTED, maxAeRegions = 1)
        val two = CameraMeteringPolicy.plan(MeteringMode.CENTER_WEIGHTED, maxAeRegions = 2)

        listOf(one, two).forEach { plan ->
            assertTrue(plan.supported)
            assertEquals(1, plan.regions.size)
            assertEquals(1000, plan.regions.single().weight)
            assertEquals(0.60f, plan.regions.single().right - plan.regions.single().left, 0.0001f)
            assertEquals("center_weighted_60_fallback", plan.source)
        }
    }

    @Test
    fun unsupportedCustomMeteringFallsBackToInitialCameraAeState() {
        listOf(MeteringMode.CENTER_WEIGHTED, MeteringMode.FRAME_AVERAGE, MeteringMode.SPOT).forEach { mode ->
            val plan = CameraMeteringPolicy.plan(mode, maxAeRegions = 0)
            assertFalse(plan.supported)
            assertTrue(plan.restoreInitialAeRegions)
            assertTrue(plan.regions.isEmpty())
        }
    }

    @Test
    fun touchFocusOverrideOwnsAeIndependentlyOfSelectedMeteringMode() {
        val tap = NormalizedPoint(0.82f, 0.18f)
        MeteringMode.entries.forEach { mode ->
            val plan = CameraMeteringPolicy.plan(mode, maxAeRegions = 1, touchOverridePoint = tap)
            assertTrue(plan.supported)
            assertEquals("touch_focus_override", plan.source)
            assertEquals(1, plan.regions.size)
            assertEquals(999, plan.regions.single().weight)
        }
    }

    @Test
    fun exposureAutoHasNoManualSensorValues() {
        val plan = CameraExposurePolicy.resolve(null, null, 200, 10_000_000L, bounds())

        assertTrue(plan.autoExposure)
        assertTrue(plan.ready)
        assertNull(plan.sensitivityIso)
        assertNull(plan.exposureTimeNs)
    }

    @Test
    fun partialManualUsesMeasuredCounterpartWithoutGuessing() {
        val isoManual = CameraExposurePolicy.resolve(800, null, 125, 8_000_000L, bounds())
        val shutterManual = CameraExposurePolicy.resolve(null, 20_000_000L, 320, 5_000_000L, bounds())

        assertFalse(isoManual.autoExposure)
        assertTrue(isoManual.ready)
        assertEquals(800, isoManual.sensitivityIso)
        assertEquals(8_000_000L, isoManual.exposureTimeNs)
        assertEquals("last_capture_result", isoManual.exposureSource)

        assertTrue(shutterManual.ready)
        assertEquals(320, shutterManual.sensitivityIso)
        assertEquals(20_000_000L, shutterManual.exposureTimeNs)
        assertEquals("last_capture_result", shutterManual.isoSource)
    }

    @Test
    fun partialManualWaitsWhenMeasuredCounterpartIsUnknown() {
        val plan = CameraExposurePolicy.resolve(400, null, null, null, bounds())

        assertFalse(plan.autoExposure)
        assertFalse(plan.ready)
        assertEquals("awaiting_measured_exposureTime", plan.pendingReason)
        assertNull(plan.exposureTimeNs)
    }

    @Test
    fun manualValuesAreClampedToReportedSensorBounds() {
        val plan = CameraExposurePolicy.resolve(99_999, 9_000_000_000L, null, null, bounds())

        assertTrue(plan.ready)
        assertEquals(6400, plan.sensitivityIso)
        assertEquals(1_000_000_000L, plan.exposureTimeNs)
        assertEquals(1_000_000_000L, plan.frameDurationNs)
    }

    private fun bounds() = ExposureBounds(
        minIso = 50,
        maxIso = 6400,
        minExposureNs = 100_000L,
        maxExposureNs = 1_000_000_000L
    )
}
