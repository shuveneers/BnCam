package com.bncam.core.capture

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin
import kotlin.math.cos

class BnAutoExposureEngineTest {
    private val limits = BnAutoSensorLimits(ExposureBounds(100, 6400, 100_000, 2_000_000_000),
        2_000_000_000, 33_000_000, 40_000_000, 800)
    private fun observation(time: Long = 10_000_000, p80: Double = 0.15, timestamp: Long = 1,
        motion: Long? = null, clip: Double = 0.0, broad: Double = 0.0) =
        BnAutoObservation(timestamp, time, 100, p80 / 8, p80, (p80 * 2).coerceAtMost(1.0),
            List(4) { clip }, 3072, broad, cameraMotionCeilingNs = motion,
            subjectMotionCeilingNs = motion, motionConfidence = if (motion != null) 0.9f else 0f)

    private fun textured(d: BnAutoExposureDecision, timestamp: Long, radiance: Double = 0.0001): BnAutoObservation {
        val warm = d.warmAllocation(limits, RawFlickerConstraint())
        val signal = radiance * warm.realizedExposureProduct / 1e9
        val gain = warm.sensitivityIso / 100.0
        return observation(warm.exposureTimeNs, signal, timestamp, 1_000_000).copy(
            iso = warm.sensitivityIso, noiseSlope = 1e-7 * gain, noiseOffset = 1e-11 * gain * gain,
            sensorLumaSamples = DoubleArray(3072) { signal * (0.8 + 0.15 * sin(it % 64 * 0.7) + 0.15 * cos(it / 64 * 0.9)) },
            sensorGradientX = DoubleArray(3072) { signal * 0.075 * cos(it % 64 * 0.7) },
            sensorGradientY = DoubleArray(3072) { signal * -0.075 * sin(it / 64 * 0.9) })
    }

    @Test fun seedIsIndependentAndInsidePhysicalBounds() {
        val d = BnAutoExposureEngine().current("main:1", limits)
        assertEquals(100, d.targetISO)
        assertEquals(10_000_000L, d.targetExposureNs)
        assertTrue(d.frameDurationNs >= d.targetExposureNs)
    }
    @Test fun daylightConvergesWithoutOscillation() {
        val engine = BnAutoExposureEngine()
        var d = engine.current("main", limits)
        val products = mutableListOf<Double>()
        repeat(30) { i ->
            val signal = 0.15 * d.exposureProduct / 1e9
            d = engine.observe("main", limits, observation(d.targetExposureNs, signal.coerceAtMost(0.9), i + 1L).copy(iso = d.targetISO))
            products += d.exposureProduct
        }
        assertTrue(products.last() > 1e9)
        assertTrue(products.takeLast(5).max() / products.takeLast(5).min() < 1.10)
    }
    @Test fun eveningTargetStaysBelowDaylight() {
        fun settle(radiance: Double): Double {
            val e = BnAutoExposureEngine()
            var d = e.current("main", limits)
            repeat(40) { i ->
                val p = (radiance * d.exposureProduct / 1e9).coerceAtMost(0.95)
                d = e.observe("main", limits, observation(d.targetExposureNs, p, i + 1L).copy(iso = d.targetISO))
            }
            return radiance * d.exposureProduct / 1e9
        }
        assertTrue(settle(0.002) < settle(0.15) * 0.5)
    }
    @Test fun isolatedLampDoesNotDarkenRoom() {
        val clear = BnAutoExposureEngine().observe("a", limits, observation())
        val lamp = BnAutoExposureEngine().observe("a", limits,
            observation(clip = 0.01).copy(p98 = 1.0))
        assertEquals(clear.exposureProduct, lamp.exposureProduct, 1.0)
    }
    @Test fun broadClippingReactsImmediately() {
        val d = BnAutoExposureEngine().observe("a", limits,
            observation(p80 = 0.8, clip = 0.10, broad = 0.5))
        assertTrue(d.exposureProduct <= 5.1e8)
        assertEquals("SENSOR_CLIPPING", d.limitingConstraint)
    }
    @Test fun subjectMotionWinsEvenWithStableCamera() {
        val d = BnAutoExposureEngine().observe("a", limits,
            observation(p80 = 0.005, motion = 1_000_000).copy(cameraMotionCeilingNs = 1_000_000_000))
        assertTrue(d.targetExposureNs <= 1_000_000)
        assertTrue(d.targetISO > 100)
        assertEquals("SUBJECT_MOTION", d.limitingConstraint)
    }
    @Test fun measuredTripodStabilityAllowsLongPhysicalShutter() {
        val e = BnAutoExposureEngine()
        var d = e.current("a", limits)
        repeat(120) { i ->
            d = e.observe("a", limits, textured(d, (i + 1L) * 33_000_000))
        }
        assertTrue(d.targetExposureNs >= 540_000_000)
        assertTrue(d.frameDurationNs >= d.targetExposureNs)
        assertTrue(d.frameDurationNs <= limits.maxFrameDurationNs)
    }
    @Test fun subjectMotionStillConstrainsWhenCameraConfidenceIsMissing() {
        val d = BnAutoExposureEngine().observe("a", limits, observation(p80 = 0.005).copy(
            subjectMotionCeilingNs = 1_000_000, subjectMotionConfidence = 0.9f,
            cameraMotionConfidence = 0f))
        assertTrue(d.targetExposureNs <= 1_000_000)
        assertEquals("SUBJECT_MOTION", d.limitingConstraint)
    }
    @Test fun invalidAndDuplicateMeasurementsHoldAndGenerationsAreSeparate() {
        val e = BnAutoExposureEngine()
        val d = e.observe("main:1", limits, observation())
        assertEquals(d.targetExposureNs, e.observe("main:1", limits, observation()).targetExposureNs)
        assertEquals(d.targetExposureNs, e.observe("main:1", limits, observation().copy(p80 = Double.NaN)).targetExposureNs)
        assertEquals(10_000_000L, e.current("tele:2", limits).targetExposureNs)
        assertEquals(0f, e.observe("main:1", limits, null).meteringConfidence)
    }
    @Test fun physicalFrameMaximumAndFlickerAreRespected() {
        val e = BnAutoExposureEngine()
        val small = limits.copy(maxFrameDurationNs = 20_000_000, minStreamFrameDurationNs = 10_000_000)
        repeat(20) { i ->
            val d = e.observe("a", small, observation(p80 = 0.001, timestamp = i + 1L,
                motion = 2_000_000_000), RawFlickerConstraint(RawFlickerFrequency.HZ_50))
            assertTrue(d.frameDurationNs <= 20_000_000)
            assertTrue(d.targetExposureNs <= 20_000_000)
            assertTrue(RawFlickerConstraint(RawFlickerFrequency.HZ_50).isExposureAligned(d.targetExposureNs))
        }
    }
    @Test fun rawMeterKeepsAbsoluteBlackWhiteAndFourCfaPlanes() {
        val pixels = ByteBuffer.allocate(64 * 48 * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(64 * 48) { pixels.putShort(if (it % 64 % 2 == 0 && it / 64 % 2 == 0) 1023 else 256) }
        val o = BnAutoRawMeter.sample(pixels, 64, 48, 128, 2, false, DoubleArray(4) { 64.0 },
            1023.0, 1, 10_000_000, 100)!!
        assertEquals(1.0, o.channelClipFractions[0], 0.0)
        assertEquals(0.0, o.channelClipFractions[1], 0.0)
        assertTrue(o.p80 in 0.39..0.41)
    }
    @Test fun bnAutoOwnsShotBiasButUserManualWins() {
        assertEquals(PhysicalSensorExposureOwner.BN_AUTO,
            PhysicalSensorExposureAuthorityPolicy.resolve(false, true, true).owner)
        assertEquals(PhysicalSensorExposureOwner.USER_MANUAL_SENSOR,
            PhysicalSensorExposureAuthorityPolicy.resolve(true, true, true).owner)
        assertFalse(CaptureExposurePreferences(exposureMode = SensorExposureMode.BN_AUTO,
            shotBiasExposure = ShotBiasExposureChoice.ISO_1600).requiresAeBaseline())
    }
    @Test fun physicalNoiseDoesNotMasqueradeAsSubjectMotion() {
        val e = BnAutoExposureEngine()
        val grid = DoubleArray(256) { if (it % 16 < 8) 0.02 else 0.15 }
        var d = e.current("a", limits)
        repeat(30) { i ->
            d = e.observe("a", limits, observation(p80 = 0.02, timestamp = (i + 1L) * 33_000_000,
                motion = 1_000_000).copy(noiseSlope = 0.001, noiseOffset = 0.000001,
                    sensorLumaSamples = grid))
        }
        assertTrue(d.reason.contains("motionEvidence=STABLE"))
        assertTrue(d.targetExposureNs <= limits.fallbackHandheldNs)
    }
    @Test fun physicallySignificantSubjectChangeImmediatelyRevokesStability() {
        val a = observation(timestamp = 1).copy(noiseSlope = 0.00001, noiseOffset = 0.0000001,
            sensorLumaSamples = DoubleArray(256) { 0.1 })
        val b = a.copy(timestampNs = 33_000_001,
            sensorLumaSamples = DoubleArray(256) { if (it < 32) 0.3 else 0.1 })
        assertEquals(BnAutoMotionEvidence.Status.MOVING, BnAutoMotionEvidence.evaluate(a, b))
    }
    @Test fun warmProducerCannotUndoTheControllerSubjectMotionCeiling() {
        val e = BnAutoExposureEngine()
        var d = e.current("moving", limits)
        repeat(30) { i ->
            d = e.observe("moving", limits, observation(p80 = 0.001,
                timestamp = i + 1L, motion = 8_000_000))
        }
        val warm = d.warmAllocation(limits, RawFlickerConstraint())
        assertTrue(warm.exposureTimeNs <= 8_000_000)
        assertTrue(warm.exposureTimeNs <= d.targetExposureNs)
        val long = d.copy(targetExposureNs = 500_000_000, targetISO = 100)
            .warmAllocation(limits, RawFlickerConstraint())
        assertTrue(long.exposureTimeNs <= limits.minStreamFrameDurationNs)
        assertEquals(50_000_000_000.0, long.realizedExposureProduct, 33_000_000.0)
    }
    @Test fun briefSubjectReversalDoesNotReleaseTheMotionShutter() {
        val e = BnAutoExposureEngine()
        val base = observation(timestamp = 1, motion = 8_000_000).copy(
            noiseSlope = 0.00001, noiseOffset = 0.0000001,
            sensorLumaSamples = DoubleArray(256) { if (it < 128) 0.1 else 0.2 })
        e.observe("a", limits, base)
        val moving = base.copy(timestampNs = 33_000_001,
            sensorLumaSamples = DoubleArray(256) { if (it < 128) 0.2 else 0.1 })
        e.observe("a", limits, moving)
        repeat(10) { i ->
            val d = e.observe("a", limits, moving.copy(timestampNs = (i + 2L) * 33_000_000 + 1))
            assertTrue(d.targetExposureNs <= 8_000_000)
            assertTrue(d.warmAllocation(limits, RawFlickerConstraint()).exposureTimeNs <= 8_000_000)
        }
        val settled = e.observe("a", limits, moving.copy(timestampNs = 600_000_001,
            cameraMotionConfidence = 0f, subjectMotionConfidence = 0f))
        assertTrue(settled.targetExposureNs > 8_000_000)
    }
    @Test fun weakOrMissingMotionNeverAuthorizesLongShutter() {
        for (confidence in listOf(0f, 0.1f, 0.9f)) {
            val e = BnAutoExposureEngine()
            var d = e.current("a", limits)
            repeat(90) { i ->
                d = e.observe("a", limits, observation(p80 = 0.001, timestamp = (i + 1L) * 33_000_000,
                    motion = 2_000_000_000).copy(motionConfidence = confidence,
                    cameraMotionConfidence = confidence, subjectMotionConfidence = confidence))
            }
            assertTrue(d.targetExposureNs <= limits.fallbackHandheldNs)
        }
    }
    @Test fun flatNoiseAndSingleDirectionTextureCannotProveCameraStability() {
        val d = BnAutoExposureEngine().current("a", limits)
        val a = textured(d, 33_000_000, 0.1)
        for (flat in listOf(true, false)) {
            val weak = a.copy(sensorGradientX = if (flat) DoubleArray(3072) else a.sensorGradientX,
                sensorGradientY = DoubleArray(3072))
            assertNull(BnAutoMotionEvidence.detailSafeCeiling(weak, weak.copy(timestampNs = 1_033_000_000)))
        }
    }
    @Test fun measurableSubpixelDriftLimitsTheDetailSafeShutter() {
        val a = textured(BnAutoExposureEngine().current("a", limits), 33_000_000, 0.1)
            .copy(noiseSlope = 1e-6, noiseOffset = 1e-7,
                sensorGradientX = DoubleArray(3072) { if (it % 2 == 0) 0.0008 else -0.0008 },
                sensorGradientY = DoubleArray(3072) { if (it % 3 == 0) 0.0008 else -0.0008 })
        val b = a.copy(timestampNs = 66_000_000,
            sensorLumaSamples = DoubleArray(3072) { a.sensorLumaSamples!![it] + a.sensorGradientX!![it] * 0.3 })
        val ceiling = BnAutoMotionEvidence.detailSafeCeiling(a, b)
        assertNotNull(ceiling)
        assertTrue(ceiling!! < 100_000_000)
    }
    @Test fun longShutterRequiresMeaningfulNoiseModelBenefit() {
        val e = BnAutoExposureEngine()
        var d = e.current("a", limits)
        repeat(120) { i ->
            d = e.observe("a", limits, textured(d, (i + 1L) * 33_000_000)
                .copy(noiseSlope = 0.0, noiseOffset = 0.0))
        }
        assertTrue(d.targetExposureNs <= limits.fallbackHandheldNs)
        assertEquals("NO_MEANINGFUL_PREDICTED_SNR_GAIN", d.limitingConstraint)
    }
    @Test fun diffuseColoredHighlightsConstrainTheBrightnessLift() {
        val e = BnAutoExposureEngine()
        val o = observation(p80 = 0.02).copy(channelP98Maximum = 0.94)
        val d = e.observe("a", limits, o)
        assertTrue(d.exposureProduct <= 1e9 * 0.85 / 0.94 * 1.01)
    }
    @Test fun lossOfMeteringImmediatelyRevokesPreviouslyProvenLongShutter() {
        val e = BnAutoExposureEngine()
        var d = e.current("a", limits)
        repeat(120) { i -> d = e.observe("a", limits, textured(d, (i + 1L) * 33_000_000)) }
        assertTrue(d.targetExposureNs >= 540_000_000)
        val missing = e.observe("a", limits, null)
        assertTrue(missing.targetExposureNs <= limits.fallbackHandheldNs)
        assertEquals(0f, missing.meteringConfidence)
    }
    @Test fun packedRaw10AndRawSensorProduceIdenticalPhotometryAndPixelGradients() {
        val width = 128; val height = 96
        val codes = IntArray(width * height) { 70 + ((it % width) * 3 + (it / width) * 7) % 700 }
        val raw16 = ByteBuffer.allocate(width * height * 2).order(ByteOrder.LITTLE_ENDIAN)
        codes.forEach { raw16.putShort(it.toShort()) }
        val packed = ByteBuffer.allocate(width * height * 5 / 4)
        for (i in codes.indices step 4) {
            repeat(4) { packed.put((codes[i + it] shr 2).toByte()) }
            packed.put((0 until 4).sumOf { (codes[i + it] and 3) shl (it * 2) }.toByte())
        }
        fun sample(buffer: ByteBuffer, raw10: Boolean) = BnAutoRawMeter.sample(buffer, width, height,
            if (raw10) width * 5 / 4 else width * 2, if (raw10) 0 else 2, raw10,
            DoubleArray(4) { 64.0 }, 1023.0, 1, 10_000_000, 100)!!
        val a = sample(raw16, false); val b = sample(packed, true)
        assertEquals(a.p80, b.p80, 0.0)
        assertEquals(a.channelP98Maximum, b.channelP98Maximum, 0.0)
        assertArrayEquals(a.sensorLumaSamples, b.sensorLumaSamples, 0.0)
        assertArrayEquals(a.sensorGradientX, b.sensorGradientX, 0.0)
        assertArrayEquals(a.sensorGradientY, b.sensorGradientY, 0.0)
        assertEquals(3.0 / 959, a.sensorGradientX!![0], 1e-12)
        assertEquals(7.0 / 959, a.sensorGradientY!![0], 1e-12)
    }
}
