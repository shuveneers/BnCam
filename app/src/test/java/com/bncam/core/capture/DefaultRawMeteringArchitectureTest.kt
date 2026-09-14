package com.bncam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DefaultRawMeteringArchitectureTest {
    @Test
    fun `fresh valid statistic becomes current metering`() {
        val tracker = DefaultRawMeteringTracker()
        val snapshot = tracker.observe(3, "RAW10_GPU", 0.125f, 0.0f, 4096, 1_000_000_000L)
        assertTrue(snapshot.valid)
        assertEquals(DefaultRawMeteringFreshness.FRESH, snapshot.freshness)
        assertEquals(0.125f, snapshot.controllerLuma!!, 0.00001f)
    }

    @Test
    fun `short missing interval uses last known good without inventing luma`() {
        val tracker = DefaultRawMeteringTracker(freshMaxAgeNs = 100_000_000L, lastKnownGoodMaxAgeNs = 800_000_000L)
        tracker.observe(1, "RAW_SENSOR_GPU", 0.10f, 0.001f, 2048, 1_000_000_000L)
        val snapshot = tracker.resolve(1, 1_400_000_000L)
        assertEquals(DefaultRawMeteringFreshness.LAST_KNOWN_GOOD, snapshot.freshness)
        assertEquals(0.10f, snapshot.controllerLuma!!, 0.00001f)
    }

    @Test
    fun `stale metering requests bootstrap rather than using ancient value`() {
        val tracker = DefaultRawMeteringTracker(freshMaxAgeNs = 100_000_000L, lastKnownGoodMaxAgeNs = 500_000_000L)
        tracker.observe(1, "RAW10_GPU", 0.10f, null, 1024, 1_000_000_000L)
        val snapshot = tracker.resolve(1, 1_700_000_000L)
        assertEquals(DefaultRawMeteringFreshness.BOOTSTRAP_REQUIRED, snapshot.freshness)
        assertNull(snapshot.controllerLuma)
    }

    @Test
    fun `pipeline generation invalidates last known good`() {
        val tracker = DefaultRawMeteringTracker()
        tracker.observe(4, "RAW10_GPU", 0.1f, null, 1024, 1_000_000_000L)
        val next = tracker.resolve(5, 1_010_000_000L)
        assertEquals(DefaultRawMeteringFreshness.BOOTSTRAP_REQUIRED, next.freshness)
        assertNull(next.controllerLuma)
    }

    @Test
    fun `three stop dark scene produces three stop ideal product change`() {
        val tracker = DefaultRawMeteringTracker()
        val metering = tracker.observe(2, "RAW_SENSOR_GPU", 0.0225f, 0f, 4096, 2_000_000_000L)
        val target = DefaultRawExposureTargetModel.resolve(
            targetLuma = 0.18f,
            metering = metering,
            currentExposureProduct = 10_000_000.0 * 100.0
        )
        assertNotNull(target)
        assertTrue(abs(target!!.ideal.exposureErrorEv - 3.0f) < 0.02f)
        assertTrue(abs(target.ideal.idealExposureProduct / target.ideal.currentExposureProduct - 8.0) < 0.1)
    }

    @Test
    fun `highlight pressure constrains final target without altering ideal measurement`() {
        val tracker = DefaultRawMeteringTracker()
        val metering = tracker.observe(7, "RAW10_GPU", 0.09f, 0.05f, 4096, 3_000_000_000L)
        val target = DefaultRawExposureTargetModel.resolve(0.18f, metering, 1.0e9)!!
        assertTrue(target.highlightProtectionEv >= 1.9f)
        assertTrue(target.finalExposureProduct < target.ideal.idealExposureProduct)
        assertTrue(abs(target.ideal.exposureErrorEv - 1.0f) < 0.02f)
    }

    @Test
    fun `sensor product bounds constrain only final target`() {
        val tracker = DefaultRawMeteringTracker()
        val metering = tracker.observe(8, "RAW_SENSOR_GPU", 0.01f, 0f, 4096, 4_000_000_000L)
        val target = DefaultRawExposureTargetModel.resolve(
            targetLuma = 0.16f,
            metering = metering,
            currentExposureProduct = 1.0e9,
            minimumExposureProduct = 1.0e7,
            maximumExposureProduct = 2.0e9
        )!!
        assertTrue(target.ideal.idealExposureProduct > 2.0e9)
        assertEquals(2.0e9, target.finalExposureProduct, 0.1)
        assertTrue(target.productBoundApplied)
    }
}
