package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase2UniformSensorMetadataSourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/quality/SensorMetadata.kt").isFile }
        ?: error("Unable to locate app module")

    @Test
    fun `one sensor metadata model serves every sensor role`() {
        val source = File(appDir(), "src/main/java/com/bncam/core/quality/SensorMetadata.kt").readText()
        assertTrue("data class SensorMetadata(" in source)
        assertTrue("typealias FrameSensorMetadataSnapshot = SensorMetadata" in source)
        assertFalse(Regex("class\\s+(Main|Tele|UltraWide|Front)SensorMetadata").containsMatchIn(source))
        listOf(
            "noiseProfileSoField",
            "colorCorrectionTransformField",
            "forwardMatrix1",
            "cameraCalibration1",
            "rawSizeField",
            "orientationField",
            "timestampField",
            "frameNumberField"
        ).forEach { assertTrue(it in source) }
    }

    @Test
    fun `registry creates exact typed metadata without hidden noise default`() {
        val source = File(appDir(), "src/main/java/com/bncam/core/quality/PhysicalSensorProfileRegistry.kt").readText()
        assertTrue("rawFrameSize: SizeSnapshot? = null" in source)
        assertTrue("SensorMetadata(" in source)
        assertTrue("toNoiseProfileField" in source)
        assertTrue("SENSOR_NOISE_PROFILE_ALL_ZERO" in source)
        assertTrue("recentFrameMetadataByTimestamp" in source)
        assertTrue("sensorMetadataHint: SensorMetadata? = null" in source)
        assertFalse("physical ?: result" in source)
    }

    @Test
    fun `base sensor calibration consumes uniform metadata`() {
        val source = File(appDir(), "src/main/java/com/bncam/core/quality/SensorCalibration.kt").readText()
        assertTrue("sensorMetadata: SensorMetadata" in source)
        assertTrue("resolveSensorNoiseProfile(sensorMetadata" in source)
        assertTrue("resolveWhiteBalanceGains(sensorMetadata" in source)
        assertTrue("resolveColorCorrectionMatrix(sensorMetadata" in source)
        assertFalse("captureResult?.get(CaptureResult.SENSOR_NOISE_PROFILE)" in source)
        assertFalse("captureResult?.get(CaptureResult.COLOR_CORRECTION_GAINS)" in source)
        assertFalse("captureResult?.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)" in source)
        assertFalse("characteristics.get(forwardKey)" in source)
    }

    @Test
    fun `selected anchor debug exposes authority and metadata audit in normal debug`() {
        val logger = File(appDir(), "src/main/java/com/bncam/core/debug/ShotLogger.kt").readText()
        val anchorBlock = logger.substringAfter("if (entry.selectedAnchor) {").substringBefore("if (includeFullDetail) {")
        assertTrue("Sensor Authority / Provenance" in anchorBlock)
        assertTrue("Uniform Sensor Metadata" in anchorBlock)
        assertTrue("sensorMetadataAuditLines" in anchorBlock)
        assertTrue("Fallback Used" in anchorBlock)
    }

    @Test
    fun `multi frame support calibration resolves exact authority metadata`() {
        val source = File(appDir(), "src/main/java/com/bncam/core/runners/MultiFrameRunner.kt").readText()
        assertTrue("resolveCurrentCalibrationInput" in source)
        assertTrue("sensorMetadata = frame.sensorMetadataSnapshot" in source)
        assertTrue("sensorMetadata = calibrationInput.sensorMetadata" in source)
    }
    @Test
    fun `static profiles are prewarmed and retain on demand route resolution`() {
        val manager = File(appDir(), "src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()
        val registry = File(appDir(), "src/main/java/com/bncam/core/quality/PhysicalSensorProfileRegistry.kt").readText()
        assertTrue("sensorProfileRegistry.prewarm()" in manager)
        assertTrue("fun profileFor(" in registry)
        assertTrue("buildStaticProfile" in registry)
        assertTrue("rawSensorOutputSizes" in registry)
        assertTrue("raw10OutputSizes" in registry)
    }

    @Test
    fun `raw admission fails closed when core metadata is unsafe`() {
        val ring = File(appDir(), "src/main/java/com/bncam/core/buffer/FrameRingBuffer.kt").readText()
        assertTrue("snapshot.coreRawMetadataValid" in ring)
        assertTrue("UNSAFE_TO_PROCESS:${'$'}{snapshot.coreRawMetadataStatus}" in ring)
    }

    @Test
    fun `raw runners pass selected frame metadata directly into quality resolution`() {
        val single = File(appDir(), "src/main/java/com/bncam/core/runners/SingleFrameRunner.kt").readText()
        val multi = File(appDir(), "src/main/java/com/bncam/core/runners/MultiFrameRunner.kt").readText()
        val config = File(appDir(), "src/main/java/com/bncam/core/quality/RenderQualityConfig.kt").readText()
        assertTrue("sensorMetadata = anchorFrame.sensorMetadataSnapshot" in single)
        assertTrue("sensorMetadata = anchorPair.sensorMetadataSnapshot" in multi)
        assertTrue("sensorMetadata: SensorMetadata? = null" in config)
        assertTrue("sensorMetadata = sensorMetadata" in config)
    }

}
