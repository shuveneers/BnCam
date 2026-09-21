package com.bncam.core.buffer

import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.hardware.camera2.TotalCaptureResult
import com.bncam.core.capture.*
import com.bncam.core.quality.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import sun.misc.Unsafe

/** Tests the real bounded ring/lease wait; only Android payloads and arrival events are fixtures. */
class ColdRawSinglePairingTest {
    private val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").let {
        it.isAccessible = true
        it.get(null) as Unsafe
    }
    private fun ring() = FrameRingBuffer(4).also { it.activateGeneration(1) }
    private fun pair(ring: FrameRingBuffer, format: Int, timestamp: Long = 9001L, epoch: Long = 7L): ZslFramePair {
        val identity = CameraRequestIdentity(1, epoch)
        val snapshot = ControlRequestSnapshot(
            identity, ControlRequestState.create(), CameraRequestSubmissionType.ONE_SHOT,
            "COLD_RAW_SINGLE", "AUTO", "AUTO", 1000L
        )
        val pair = ZslFramePair().apply {
            this.timestamp = timestamp
            generationId = 1
            frameVersion = timestamp
            this.format = format
            controlRequestEpoch = epoch
            requestProvenance = FrameRequestProvenance(identity, snapshot, "EXACT_PROVENANCE_MATCH")
            sensorMetadataSnapshot = metadata(timestamp)
            metadata = unsafe.allocateInstance(TotalCaptureResult::class.java) as TotalCaptureResult
            hardwareBuffer = unsafe.allocateInstance(HardwareBuffer::class.java) as HardwareBuffer
        }
        val field = FrameRingBuffer::class.java.getDeclaredField("buffer").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val entries = field.get(ring) as Array<ZslFramePair>
        entries[entries.indexOfFirst { it.timestamp == 0L }] = pair
        return pair
    }
    private fun arrived(ring: FrameRingBuffer, pair: ZslFramePair) {
        FrameRingBuffer::class.java.getDeclaredMethod(
            "publishRingEvent", FrameRingEventType::class.java, ZslFramePair::class.java, Int::class.javaPrimitiveType
        ).apply { isAccessible = true }.invoke(ring, FrameRingEventType.PAIR_COMPLETED, pair, 1)
    }

    @Test fun warmExactRawPairIsLeasedImmediately() = runBlocking {
        for (format in listOf(ImageFormat.RAW10, ImageFormat.RAW_SENSOR)) {
            val ring = ring()
            val pair = pair(ring, format)
            val lease = ring.awaitAndLeaseExactRequestFrame(9001L, 1, 7L, format)
            assertSame(pair, lease!!.pair)
            assertEquals(1, pair.pinCount)
            lease.release()
            lease.release()
            assertEquals(0, pair.pinCount)
        }
    }

    @Test fun coldRaw10AndRawSensorAcceptBothArrivalOrdersAndIgnoreDuplicates() = runBlocking {
        for (format in listOf(ImageFormat.RAW10, ImageFormat.RAW_SENSOR)) {
            for (imageFirst in listOf(true, false)) {
                val ring = ring()
                val pair = pair(ring, format)
                val result = pair.metadata
                val buffer = pair.hardwareBuffer
                if (imageFirst) pair.metadata = null else pair.hardwareBuffer = null
                val waiting = async(start = CoroutineStart.UNDISPATCHED) {
                    ring.awaitAndLeaseExactRequestFrame(9001L, 1, 7L, format)
                }
                assertFalse(waiting.isCompleted)
                pair.metadata = result
                pair.hardwareBuffer = buffer
                arrived(ring, pair)
                arrived(ring, pair)
                val lease = waiting.await()
                assertSame(pair, lease!!.pair)
                assertEquals(1, pair.pinCount)
                arrived(ring, pair)
                assertEquals(1, pair.pinCount)
                lease.release()
                assertEquals(0, pair.pinCount)
            }
        }
    }

    @Test fun neighboringTimestampWrongEpochAndWrongFormatNeverSubstituteForStill() = runBlocking {
        val ring = ring()
        val neighbor = pair(ring, ImageFormat.RAW10, 9000L)
        assertNull(ring.awaitAndLeaseExactRequestFrame(9001L, 1, 7L, ImageFormat.RAW10, 1L))
        assertNull(ring.awaitAndLeaseExactRequestFrame(9000L, 1, 8L, ImageFormat.RAW10, 1L))
        assertNull(ring.awaitAndLeaseExactRequestFrame(9000L, 1, 7L, ImageFormat.RAW_SENSOR, 1L))
        assertEquals(0, neighbor.pinCount)
    }

    @Test fun generationSwitchAndShutdownClearWakePendingCapture() = runBlocking {
        for (switchGeneration in listOf(true, false)) {
            val ring = ring()
            val waiting = async(start = CoroutineStart.UNDISPATCHED) {
                ring.awaitAndLeaseExactRequestFrame(9001L, 1, 7L, ImageFormat.RAW10, 60_000L)
            }
            if (switchGeneration) ring.activateGeneration(2) else ring.clear()
            assertNull(withTimeout(500L) { waiting.await() })
        }
    }

    @Test fun cancellationDoesNotLeaseLateFrame() = runBlocking {
        val ring = ring()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            ring.awaitAndLeaseExactRequestFrame(9001L, 1, 7L, ImageFormat.RAW_SENSOR)
        }
        waiting.cancelAndJoin()
        val late = pair(ring, ImageFormat.RAW_SENSOR)
        arrived(ring, late)
        assertTrue(waiting.isCancelled)
        assertEquals(0, late.pinCount)
    }

    @Test fun clearCannotBeHiddenByImmediatelyFollowingPairEvent() = runBlocking {
        val ring = ring()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            ring.awaitAndLeaseExactRequestFrame(9001L, 1, 7L, ImageFormat.RAW10)
        }
        ring.clear()
        val late = pair(ring, ImageFormat.RAW10)
        arrived(ring, late)
        assertNull(waiting.await())
        assertEquals(0, late.pinCount)
    }

    @Test fun staleSensorMetadataAndRetiredGenerationAreRejected() = runBlocking {
        val ring = ring()
        val pair = pair(ring, ImageFormat.RAW10)
        pair.sensorMetadataSnapshot = metadata(8999L)
        assertNull(ring.awaitAndLeaseExactRequestFrame(9001L, 1, 7L, ImageFormat.RAW10, 1L))
        assertNull(ring.awaitAndLeaseExactRequestFrame(9001L, 2, 7L, ImageFormat.RAW10, 1L))
        assertEquals(0, pair.pinCount)
    }

    private fun metadata(timestamp: Long): SensorMetadata {
        val sensor = SensorIdentity("4", "4", null, SensorAuthorityType.STANDALONE)
        val capture = CaptureIdentity(sensor, 44L, timestamp, 8)
        fun <T> v(value: T, source: String) = SensorMetadataValue.valid(value, source)
        fun <T> u(source: String, reason: String = "VALUE_UNAVAILABLE") = SensorMetadataValue.unavailable<T>(source, reason)
        val black = v(listOf(64f, 64f, 64f, 64f), "CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL")
        val white = v(4095, "CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL")
        return SensorMetadata(
            route = SensorRouteKey("4", null),
            staticFingerprint = "fingerprint",
            metadataSource = "STANDALONE_CAPTURE_RESULT",
            sensorIdentity = sensor,
            captureIdentity = capture,
            cfa = v(0, "CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT"),
            staticBlackLevel = black,
            dynamicBlackLevel = black,
            effectiveBlackLevel = black,
            staticWhiteLevelField = white,
            dynamicWhiteLevelField = white,
            effectiveWhiteLevelField = white,
            sensitivityIsoField = v(200, "CaptureResult.SENSOR_SENSITIVITY"),
            exposureTimeNsField = v(20_000_000L, "CaptureResult.SENSOR_EXPOSURE_TIME"),
            frameDurationNsField = v(33_333_333L, "CaptureResult.SENSOR_FRAME_DURATION"),
            maxAnalogSensitivityIso = v(800, "CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY"),
            analogSensitivityIso = v(200, "DERIVED"),
            analogGainRelativeToMinimum = v(2.0, "DERIVED"),
            sensorDigitalGainRatio = v(1.0, "DERIVED"),
            postRawSensitivityBoostField = v(100, "CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST"),
            postRawDigitalGainRatio = v(1.0, "DERIVED"),
            combinedDigitalGainRatio = v(1.0, "DERIVED"),
            noiseProfileSoField = v(listOf(0.01, 0.001, 0.011, 0.001), "CaptureResult.SENSOR_NOISE_PROFILE"),
            colorCorrectionGainsField = v(listOf(2f, 1f, 1f, 1.5f), "CaptureResult.COLOR_CORRECTION_GAINS"),
            colorCorrectionTransformField = u("CaptureResult.COLOR_CORRECTION_TRANSFORM"),
            neutralColorPointField = v(listOf(0.5f, 1f, 0.67f), "CaptureResult.SENSOR_NEUTRAL_COLOR_POINT"),
            referenceIlluminant1Field = v(21, "CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1"),
            referenceIlluminant2Field = v(17, "CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2"),
            colorTransform1 = u("CameraCharacteristics.SENSOR_COLOR_TRANSFORM1"),
            colorTransform2 = u("CameraCharacteristics.SENSOR_COLOR_TRANSFORM2"),
            forwardMatrix1 = u("CameraCharacteristics.SENSOR_FORWARD_MATRIX1"),
            forwardMatrix2 = u("CameraCharacteristics.SENSOR_FORWARD_MATRIX2"),
            cameraCalibration1 = u("CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1"),
            cameraCalibration2 = u("CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2"),
            activeArrayField = v(RectSnapshot(0, 0, 4096, 3072), "CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE"),
            rawSizeField = v(SizeSnapshot(4096, 3072), "PipelineIdentity.ImageReader"),
            orientationField = v(90, "CameraCharacteristics.SENSOR_ORIENTATION"),
            timestampField = v(timestamp, "CaptureResult.SENSOR_TIMESTAMP"),
            frameNumberField = v(44L, "CaptureResult.frameNumber"),
            rollingShutterSkewNsField = u("CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW"),
            lensShadingMapMode = null,
            lensShadingRows = 0,
            lensShadingColumns = 0,
            lensShadingGainFactors = null,
            aeState = null,
            awbState = null,
            afState = null,
            sceneFlicker = null,
            lensState = null,
            oisMode = null,
            focusDistanceDiopters = null,
            focalLengthMm = null,
            aperture = null,
            rawSourceId = "4",
            captureResultSourceId = "4",
            characteristicsSourceId = "4",
            calibrationSourceId = "4"
        )
    }


}
