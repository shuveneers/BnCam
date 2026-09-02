package com.bncam.core.quality

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameSensorMetadataSnapshotTest {
    private fun snapshot(
        noise: List<Double>? = null,
        rows: Int = 0,
        columns: Int = 0,
        shading: List<Float>? = null
    ) = FrameSensorMetadataSnapshot(
        route = SensorRouteKey("logical", "physical"),
        staticFingerprint = "abc123",
        metadataSource = "PHYSICAL_CAPTURE_RESULT",
        sensorTimestampNs = 10L,
        frameNumber = 1L,
        sensitivityIso = 100,
        exposureTimeNs = 10_000_000L,
        frameDurationNs = 33_333_333L,
        postRawSensitivityBoost = 100,
        rollingShutterSkewNs = 1_000_000L,
        dynamicBlackLevels = listOf(64f, 64f, 64f, 64f),
        dynamicWhiteLevel = 4095,
        noiseProfileSo = noise,
        colorCorrectionGains = listOf(2f, 1f, 1f, 1.5f),
        colorCorrectionTransform = null,
        neutralColorPoint = listOf(0.5f, 1f, 0.67f),
        lensShadingMapMode = 1,
        lensShadingRows = rows,
        lensShadingColumns = columns,
        lensShadingGainFactors = shading,
        aeState = 2,
        awbState = 2,
        afState = 2,
        sceneFlicker = 0,
        lensState = 0,
        oisMode = 1,
        focusDistanceDiopters = 0f,
        focalLengthMm = 6f,
        aperture = 1.8f
    )

    @Test
    fun physicalNoiseRequiresCompleteSoPairs() {
        assertTrue(snapshot(noise = listOf(0.01, 0.001, 0.011, 0.001)).hasPhysicalNoiseModel)
        assertFalse(snapshot(noise = listOf(0.01, 0.001, 0.011)).hasPhysicalNoiseModel)
        assertFalse(snapshot(noise = null).hasPhysicalNoiseModel)
    }

    @Test
    fun lensShadingRequiresExactFourChannelMapSize() {
        assertTrue(snapshot(rows = 2, columns = 2, shading = List(16) { 1f }).hasDynamicLensShadingMap)
        assertFalse(snapshot(rows = 2, columns = 2, shading = List(15) { 1f }).hasDynamicLensShadingMap)
    }
}
