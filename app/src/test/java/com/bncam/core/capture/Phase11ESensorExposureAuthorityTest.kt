package com.bncam.core.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase11ESensorExposureAuthorityTest {
    private fun evidence(
        domain: SensorExposureEvidenceDomain = SensorExposureEvidenceDomain.RAW,
        p50: Float = 0.10f,
        p90: Float = 0.40f,
        p95: Float = 0.50f,
        p99: Float = 0.65f,
        nearClip: Float = 0f,
        saturated: Float = 0f,
        exposureNs: Long = 10_000_000L,
        iso: Int = 100,
        observedNs: Long = 1_000_000_000L
    ) = SensorExposureEvidence(
        domain = domain,
        pipelineGeneration = 7,
        sensorTimestampNs = 99L,
        observedElapsedRealtimeNs = observedNs,
        sampleCount = 4096,
        p50 = p50,
        p90 = p90,
        p95 = p95,
        p99 = p99,
        nearClipFraction = nearClip,
        saturatedFraction = saturated,
        exposureTimeNs = exposureNs,
        sensitivityIso = iso,
        predictedNoiseSigma = 0.004f,
        signalToNoiseRatio = 20f,
        source = "RAW10_VULKAN"
    )

    @Test
    fun `RAW positive shift comes from current percentile evidence and stays bounded`() {
        val plan = SensorExposurePolicy.resolve(
            enabled = true,
            evidence = evidence(),
            nowElapsedRealtimeNs = 1_100_000_000L
        )
        assertTrue(plan.enabled)
        assertTrue(plan.appliedShiftEv > 0.05f)
        assertTrue(plan.appliedShiftEv <= SensorExposurePolicy.MAX_RAW_POSITIVE_SHIFT_EV)
        // Regression guard against the removed synthetic 1.5 * 0.65 = 0.975 EV pseudo-plan.
        assertNotEquals(0.975f, plan.requestedShiftEv, 0.0001f)
    }

    @Test
    fun `true saturation makes ETTR protective instead of adding exposure`() {
        val plan = SensorExposurePolicy.resolve(
            enabled = true,
            evidence = evidence(p95 = 0.91f, p99 = 0.999f, nearClip = 0.012f, saturated = 0.003f),
            nowElapsedRealtimeNs = 1_100_000_000L
        )
        assertTrue(plan.enabled)
        assertTrue(plan.appliedShiftEv < 0f)
        assertTrue(plan.reason == "sensor_saturation_protection")
    }

    @Test
    fun `YUV evidence never requests more than one EV positive shift`() {
        val plan = SensorExposurePolicy.resolve(
            enabled = true,
            evidence = evidence(domain = SensorExposureEvidenceDomain.YUV, p50 = 0.02f, p95 = 0.08f, p99 = 0.12f),
            nowElapsedRealtimeNs = 1_100_000_000L
        )
        assertTrue(plan.appliedShiftEv <= SensorExposurePolicy.MAX_YUV_POSITIVE_SHIFT_EV)
    }

    @Test
    fun `stale warm-buffer evidence cannot own a new request`() {
        val plan = SensorExposurePolicy.resolve(
            enabled = true,
            evidence = evidence(observedNs = 1_000_000_000L),
            nowElapsedRealtimeNs = 2_100_000_000L
        )
        assertFalse(plan.enabled)
        assertTrue(plan.reason.contains("stale"))
    }

    @Test
    fun `allocator respects cadence-motion shutter ceiling and moves residual product to ISO`() {
        val e = evidence(exposureNs = 10_000_000L, iso = 100)
        val plan = SensorExposurePolicy.resolve(
            enabled = true,
            evidence = e,
            additionalBiasEv = 1.0f,
            nowElapsedRealtimeNs = 1_100_000_000L
        )
        val allocation = SensorExposureAllocator.allocate(
            plan = plan,
            bounds = ExposureBounds(50, 6400, 100_000L, 1_000_000_000L),
            preferences = CaptureExposurePreferences(),
            maxExposureTimeNs = 20_000_000L,
            minimumFrameDurationNs = 16_666_667L
        )!!
        assertTrue(allocation.exposureTimeNs <= 20_000_000L)
        assertTrue(allocation.sensitivityIso >= 100)
        assertTrue(allocation.frameDurationNs >= allocation.exposureTimeNs)
    }
    @Test
    fun `observer converts one RAW analysis sample into exact acquisition evidence`() {
        val hist256 = IntArray(256)
        hist256[25] = 1024
        hist256[128] = 1024
        hist256[230] = 1024
        hist256[250] = 1024
        val stats = ExposureStatistics(
            source = "RAW10_VULKAN",
            lumaHistogram64 = IntArray(64).also { it[32] = 4096 },
            redHistogram64 = IntArray(64),
            greenHistogram64 = IntArray(64),
            blueHistogram64 = IntArray(64),
            sampleCount = 4096,
            shadowFraction = 0.1f,
            highlightFraction = 0.02f,
            redClipFraction = 0f,
            greenClipFraction = 0f,
            blueClipFraction = 0f,
            rawNearClipFraction = 0.0002f,
            rawSaturatedFraction = 0.0001f,
            captureExposureTimeNs = 8_000_000L,
            captureSensitivityIso = 125,
            predictedNoiseSigma = 0.003f,
            signalToNoiseRatio = 22f,
            sensorTimestampNs = 123456L,
            pipelineGeneration = 99,
            linearLumaHistogram256 = hist256
        )
        val observed = SensorExposureObserver.observe(stats, pipelineGeneration = 99, observedElapsedRealtimeNs = 77L)!!
        assertTrue(observed.domain == SensorExposureEvidenceDomain.RAW)
        assertTrue(observed.pipelineGeneration == 99)
        assertTrue(observed.sensorTimestampNs == 123456L)
        assertTrue(observed.exposureTimeNs == 8_000_000L)
        assertTrue(observed.sensitivityIso == 125)
        assertTrue(observed.nearClipFraction == 0.0002f)
        assertTrue(observed.saturatedFraction == 0.0001f)
        assertTrue(observed.p99 > observed.p50)
    }

}
