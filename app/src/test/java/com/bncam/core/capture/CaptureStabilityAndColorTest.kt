package com.bncam.core.capture

import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import com.bncam.core.buffer.FrameRingBuffer
import com.bncam.core.buffer.ZslFramePair
import com.bncam.core.runners.HeuristicCandidate
import com.bncam.core.runners.FrameSelectionEngine
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Field
import sun.misc.Unsafe

class CaptureStabilityAndColorTest {

    private lateinit var unsafe: Unsafe
    private lateinit var mockHardwareBuffer: HardwareBuffer
    private lateinit var mockTotalCaptureResult: TotalCaptureResult

    @Before
    fun setUp() {
        // Safe access to Unsafe for class allocation without constructors
        val field: Field = Unsafe::class.java.getDeclaredField("theUnsafe")
        field.isAccessible = true
        unsafe = field.get(null) as Unsafe

        mockHardwareBuffer = unsafe.allocateInstance(HardwareBuffer::class.java) as HardwareBuffer
        mockTotalCaptureResult = unsafe.allocateInstance(TotalCaptureResult::class.java) as TotalCaptureResult

        FrameGenerationId.reset()
        FrameGenerationId.forceWarmState()
        CaptureReadinessGate.resetOverrides()
        RawColorPipelineAuditor.testCfaOverride = null
    }

    @After
    fun tearDown() {
        CaptureReadinessGate.resetOverrides()
        RawColorPipelineAuditor.testCfaOverride = null
    }

    private fun exactProvenance(
        pipelineGeneration: Int,
        controlRequestEpoch: Long = 1L
    ): FrameRequestProvenance {
        val identity = CameraRequestIdentity(
            pipelineGeneration = pipelineGeneration,
            controlRequestEpoch = controlRequestEpoch
        )
        val snapshot = ControlRequestSnapshot(
            identity = identity,
            state = ControlRequestState.create(),
            submissionType = CameraRequestSubmissionType.REPEATING,
            submissionReason = "TEST",
            meteringPolicySummary = "test",
            exposurePolicySummary = "test",
            submittedElapsedRealtimeNs = 1L
        )
        return FrameRequestProvenance(identity, snapshot)
    }

    private fun setBufferCompleteFrameCount(ringBuffer: FrameRingBuffer, count: Int, generationId: Int) {
        val bufferField = FrameRingBuffer::class.java.getDeclaredField("buffer")
        bufferField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val bufferArray = bufferField.get(ringBuffer) as Array<ZslFramePair>

        for (i in 0 until count) {
            val pair = bufferArray[i]
            pair.timestamp = 1000L + i
            pair.hardwareBuffer = mockHardwareBuffer
            pair.metadata = mockTotalCaptureResult
            pair.generationId = generationId
            pair.controlRequestEpoch = 1L
            pair.requestProvenance = exactProvenance(generationId)
        }
    }

    @Test
    fun testGenerationIdInvalidation() {
        val initialGen = FrameGenerationId.get()
        assertEquals(1, initialGen)

        val nextGen = FrameGenerationId.increment()
        assertEquals(2, nextGen)

        FrameGenerationId.reset()
        assertEquals(1, FrameGenerationId.get())
    }

    @Test
    fun testOldGenerationFramesRejected() {
        FrameGenerationId.reset() // Generation is 1
        val currentGen = FrameGenerationId.get()

        val frameCurrent = ZslFramePair().apply {
            timestamp = 1000L
            generationId = currentGen
            controlRequestEpoch = 1L
        }
        val frameOld = ZslFramePair().apply {
            timestamp = 900L
            generationId = currentGen - 1
            controlRequestEpoch = 1L
        }

        val candCurrent = HeuristicCandidate(
            frame = frameCurrent,
            index = 0,
            timestampNs = 1000L,
            deltaMs = -20.0,
            sharpnessScore = 0.8,
            motionScore = 0.9,
            evScore = 0.8,
            alignabilityScore = 0.8,
            clippingScore = 0.8,
            isStable = true,
            metadataComplete = true,
            aeState = 2,
            awbState = 2,
            focusState = 2
        )

        val candOld = HeuristicCandidate(
            frame = frameOld,
            index = 1,
            timestampNs = 900L,
            deltaMs = -120.0,
            sharpnessScore = 0.99, // Super high sharpness but old generation!
            motionScore = 0.9,
            evScore = 0.8,
            alignabilityScore = 0.8,
            clippingScore = 0.8,
            isStable = true,
            metadataComplete = true,
            aeState = 2,
            awbState = 2,
            focusState = 2
        )

        val result = FrameSelectionEngine.selectBestCandidate(
            candidates = listOf(candCurrent, candOld),
            freshnessWindowMs = 350.0
        )

        // The older generation frame must be rejected, so candCurrent must win!
        assertEquals(0, result.selected.candidate.index)
        assertTrue(ZslCandidateAuditor.getLogs().any { it.timestampNs == 900L && it.rejectReason.contains("wrong_generation") })
    }

    @Test
    fun testUnderfilledBufferStates() {
        val ringBuffer = FrameRingBuffer(10)
        ringBuffer.activateGeneration(1)

        // 0 frames -> COLD_EMPTY
        var state = CaptureReadinessGate.determineState(ringBuffer, null, null, ImageFormat.RAW10)
        assertEquals(ReadinessState.COLD_EMPTY, state)

        // 1 frame -> FILLING
        setBufferCompleteFrameCount(ringBuffer, 1, 1)
        state = CaptureReadinessGate.determineState(ringBuffer, null, null, ImageFormat.RAW10)
        assertEquals(ReadinessState.FILLING, state)

        // 2 frames -> FILLING
        setBufferCompleteFrameCount(ringBuffer, 2, 1)
        state = CaptureReadinessGate.determineState(ringBuffer, null, null, ImageFormat.RAW10)
        assertEquals(ReadinessState.FILLING, state)

        // 3 frames but missing metadata overrides -> METADATA_UNSTABLE
        setBufferCompleteFrameCount(ringBuffer, 3, 1)
        state = CaptureReadinessGate.determineState(ringBuffer, null, null, ImageFormat.RAW10)
        assertEquals(ReadinessState.METADATA_UNSTABLE, state)

        // 3 frames with valid metadata overrides -> READY
        CaptureReadinessGate.testTimestampOverride = 12345678L
        CaptureReadinessGate.testExposureOverride = 10000000L
        CaptureReadinessGate.testSensitivityOverride = 100
        state = CaptureReadinessGate.determineState(ringBuffer, null, null, ImageFormat.RAW10)
        assertEquals(ReadinessState.READY, state)
    }

    @Test
    fun testAFScanningAndLensMovingStates() {
        val ringBuffer = FrameRingBuffer(10)
        ringBuffer.activateGeneration(1)
        setBufferCompleteFrameCount(ringBuffer, 3, 1)

        CaptureReadinessGate.testTimestampOverride = 12345678L
        CaptureReadinessGate.testExposureOverride = 10000000L
        CaptureReadinessGate.testSensitivityOverride = 100

        // AF Scanning -> AF_SCANNING state
        CaptureReadinessGate.testAfStateOverride = 3 // CONTROL_AF_STATE_ACTIVE_SCAN is 3
        var state = CaptureReadinessGate.determineState(ringBuffer, null, null, ImageFormat.RAW10)
        assertEquals(ReadinessState.AF_SCANNING, state)

        // Lens Moving -> AF_SCANNING state
        CaptureReadinessGate.testAfStateOverride = 2 // FOCUSED_LOCKED
        CaptureReadinessGate.testLensStateOverride = 1 // LENS_STATE_MOVING
        state = CaptureReadinessGate.determineState(ringBuffer, null, null, ImageFormat.RAW10)
        assertEquals(ReadinessState.AF_SCANNING, state)
    }

    @Test
    fun testAEAwbConvergingStates() {
        val ringBuffer = FrameRingBuffer(10)
        ringBuffer.activateGeneration(1)
        setBufferCompleteFrameCount(ringBuffer, 3, 1)

        CaptureReadinessGate.testTimestampOverride = 12345678L
        CaptureReadinessGate.testExposureOverride = 10000000L
        CaptureReadinessGate.testSensitivityOverride = 100
        CaptureReadinessGate.testAfStateOverride = 2 // FOCUSED_LOCKED

        // AE Precapture / Searching -> AE_AWB_CONVERGING state
        CaptureReadinessGate.testAeStateOverride = 1 // CONTROL_AE_STATE_SEARCHING
        var state = CaptureReadinessGate.determineState(ringBuffer, null, null, ImageFormat.RAW10)
        assertEquals(ReadinessState.AE_AWB_CONVERGING, state)

        // AWB Searching -> AE_AWB_CONVERGING state
        CaptureReadinessGate.testAeStateOverride = 2 // CONVERGED
        CaptureReadinessGate.testAwbStateOverride = 1 // CONTROL_AWB_STATE_SEARCHING
        state = CaptureReadinessGate.determineState(ringBuffer, null, null, ImageFormat.RAW10)
        assertEquals(ReadinessState.AE_AWB_CONVERGING, state)
    }

    @Test
    fun captureRouteTreatsTransient3AAsDegradedReadyInsteadOfBlockingShutter() {
        val ringBuffer = FrameRingBuffer(10)
        ringBuffer.activateGeneration(1)
        setBufferCompleteFrameCount(ringBuffer, 3, 1)

        CaptureReadinessGate.testTimestampOverride = 12345678L
        CaptureReadinessGate.testExposureOverride = 10000000L
        CaptureReadinessGate.testSensitivityOverride = 100
        val requirement = WarmBufferReadinessPolicy.captureRoute(
            format = ImageFormat.RAW10,
            captureMode = CaptureMode.MULTI,
            requestedFrameCount = 8,
            bufferCapacity = 10
        )

        CaptureReadinessGate.testAfStateOverride = CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN
        var state = CaptureReadinessGate.determineState(
            ringBuffer,
            null,
            null,
            ImageFormat.RAW10,
            requirement
        )
        assertEquals(ReadinessState.READY_DEGRADED, state)

        CaptureReadinessGate.testAfStateOverride = CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
        CaptureReadinessGate.testAeStateOverride = CaptureResult.CONTROL_AE_STATE_SEARCHING
        state = CaptureReadinessGate.determineState(
            ringBuffer,
            null,
            null,
            ImageFormat.RAW10,
            requirement
        )
        assertEquals(ReadinessState.READY_DEGRADED, state)
    }

    @Test
    fun testSharpOlderFrameWinsOverRecentBlurry() {
        FrameGenerationId.reset()
        FrameGenerationId.forceColdStart()
        val currentGen = FrameGenerationId.get()

        val frameRecentBlurry = ZslFramePair().apply {
            timestamp = 1000L
            generationId = currentGen
            controlRequestEpoch = 2L
        }
        val frameOlderSharp = ZslFramePair().apply {
            timestamp = 850L
            generationId = currentGen
            controlRequestEpoch = 1L
        }

        // Candidate 0: Recent but blurry (low sharpness score)
        val candRecent = HeuristicCandidate(
            frame = frameRecentBlurry,
            index = 0,
            timestampNs = 1000L,
            deltaMs = -10.0,
            sharpnessScore = 0.2, // Blurry!
            motionScore = 0.9,
            evScore = 0.8,
            alignabilityScore = 0.8,
            clippingScore = 0.8,
            isStable = true,
            metadataComplete = true,
            aeState = 2,
            awbState = 2,
            focusState = 2
        )

        // Candidate 1: Older but sharp (high sharpness score)
        val candOlder = HeuristicCandidate(
            frame = frameOlderSharp,
            index = 1,
            timestampNs = 850L,
            deltaMs = -160.0,
            sharpnessScore = 0.95, // Sharp!
            motionScore = 0.9,
            evScore = 0.8,
            alignabilityScore = 0.8,
            clippingScore = 0.8,
            isStable = true,
            metadataComplete = true,
            aeState = 2,
            awbState = 2,
            focusState = 2
        )

        val result = FrameSelectionEngine.selectBestCandidate(
            candidates = listOf(candRecent, candOlder),
            freshnessWindowMs = 350.0
        )

        // Candidate 1 must win because of its high sharpness score, overriding recency
        assertEquals(1, result.selected.candidate.index)
    }

    @Test
    fun testRawColorPipelineAuditorDiagnostics() {
        // CFA pattern mismatch check
        RawColorPipelineAuditor.testCfaOverride = 0 // RGGB
        val auditMismatched = RawColorPipelineAuditor.audit(
            sensorMetadata = null,
            cfaPattern = 1, // GRBG
            effectiveWbGains = floatArrayOf(1.5f, 1.0f, 1.0f, 2.0f),
            colorMatrix = floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f),
            lensShadingMap = null
        )
        assertFalse(auditMismatched.cfaPatternMatched)
        assertTrue(auditMismatched.diagnostics.any { it.contains("CFA pattern mismatch") })

        // CFA pattern matched check
        val auditMatched = RawColorPipelineAuditor.audit(
            sensorMetadata = null,
            cfaPattern = 0, // RGGB
            effectiveWbGains = floatArrayOf(1.5f, 1.0f, 1.0f, 2.0f),
            colorMatrix = floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f),
            lensShadingMap = null
        )
        assertTrue(auditMatched.cfaPatternMatched)
    }

    @Test
    fun testMatrixMultiplication() {
        val resolverClass = Class.forName("com.bncam.core.quality.SensorCalibrationResolver")
        val multiplyMethod = resolverClass.getDeclaredMethod("multiply3x3", FloatArray::class.java, FloatArray::class.java)
        multiplyMethod.isAccessible = true

        val identity = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f
        )
        val matrix = floatArrayOf(
            1f, 2f, 3f,
            4f, 5f, 6f,
            7f, 8f, 9f
        )

        val resolverInstance = resolverClass.getField("INSTANCE").get(null)
        val result = multiplyMethod.invoke(resolverInstance, identity, matrix) as FloatArray
        assertArrayEquals(matrix, result, 1e-5f)
    }

    @Test
    fun testMatrixInversion() {
        val resolverClass = Class.forName("com.bncam.core.quality.SensorCalibrationResolver")
        val invertMethod = resolverClass.getDeclaredMethod("invert3x3", FloatArray::class.java)
        invertMethod.isAccessible = true

        val matrix = floatArrayOf(
            1f, 0f, 2f,
            0f, 3f, 0f,
            4f, 0f, 5f
        )
        val expected = floatArrayOf(
            -1.6666666f, 0f, 0.6666667f,
            0f, 0.3333333f, 0f,
            1.3333333f, 0f, -0.3333333f
        )
        val resolverInstance = resolverClass.getField("INSTANCE").get(null)
        val result = invertMethod.invoke(resolverInstance, matrix) as FloatArray
        assertArrayEquals(expected, result, 1e-4f)
    }

    @Test
    fun testMatrixValidationAndAuditor() {
        val resolverClass = Class.forName("com.bncam.core.quality.SensorCalibrationResolver")
        val validateMethod = resolverClass.getDeclaredMethod("validateColorMatrix", FloatArray::class.java)
        validateMethod.isAccessible = true

        val validMatrix = floatArrayOf(
            1.2f, -0.1f, -0.1f,
            -0.1f, 1.3f, -0.1f,
            -0.1f, -0.1f, 1.4f
        )
        val resolverInstance = resolverClass.getField("INSTANCE").get(null)
        val result = validateMethod.invoke(resolverInstance, validMatrix) as Triple<*, *, *>
        assertEquals(true, result.first)
    }

    @Test
    fun testCfaNameMapping() {
        val resolverClass = Class.forName("com.bncam.core.quality.SensorCalibrationResolver")
        val cfaNameMethod = resolverClass.getDeclaredMethod("cfaName", Int::class.java)
        cfaNameMethod.isAccessible = true

        val resolverInstance = resolverClass.getField("INSTANCE").get(null)
        assertEquals("RGGB", cfaNameMethod.invoke(resolverInstance, 0))
        assertEquals("GRBG", cfaNameMethod.invoke(resolverInstance, 1))
        assertEquals("GBRG", cfaNameMethod.invoke(resolverInstance, 2))
        assertEquals("BGGR", cfaNameMethod.invoke(resolverInstance, 3))
    }

    @Test
    fun testZslTeleSharpnessDominance() {
        FrameGenerationId.reset()
        FrameGenerationId.forceWarmState()
        val currentGen = FrameGenerationId.get()

        val frameRecent = ZslFramePair().apply {
            timestamp = 1000L
            generationId = currentGen
            controlRequestEpoch = 2L
        }
        val frameOlderSharp = ZslFramePair().apply {
            timestamp = 850L
            generationId = currentGen
            controlRequestEpoch = 1L
        }

        val candRecent = HeuristicCandidate(
            frame = frameRecent,
            index = 0,
            timestampNs = 1000L,
            deltaMs = -10.0,
            sharpnessScore = 0.5,
            motionScore = 0.8,
            motionProxy = 0.8,
            evScore = 0.8,
            alignabilityScore = 0.8,
            clippingScore = 0.8,
            isStable = true,
            metadataComplete = true,
            aeState = 2,
            awbState = 2,
            focusState = 2
        )

        val candOlderSharp = HeuristicCandidate(
            frame = frameOlderSharp,
            index = 1,
            timestampNs = 850L,
            deltaMs = -160.0,
            sharpnessScore = 0.65, // > 12% sharper (0.5 * 1.12 = 0.56)
            motionScore = 0.8,
            motionProxy = 0.8,
            evScore = 0.8,
            alignabilityScore = 0.8,
            clippingScore = 0.8,
            isStable = true,
            metadataComplete = true,
            aeState = 2,
            awbState = 2,
            focusState = 2
        )

        // 1. With isTele = true, the older sharper frame must win!
        val resultTele = FrameSelectionEngine.selectBestCandidate(
            candidates = listOf(candRecent, candOlderSharp),
            freshnessWindowMs = 350.0,
            isTele = true
        )
        assertEquals(1, resultTele.selected.candidate.index)

        // 2. Under the post-gate quality-dominant contract, the sharper frame wins even on non-tele
        val resultNonTele = FrameSelectionEngine.selectBestCandidate(
            candidates = listOf(candRecent, candOlderSharp),
            freshnessWindowMs = 350.0,
            isTele = false
        )
        assertEquals(1, resultNonTele.selected.candidate.index)
    }
}
