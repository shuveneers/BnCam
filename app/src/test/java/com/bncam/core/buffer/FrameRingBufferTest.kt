package com.bncam.core.buffer

import android.hardware.HardwareBuffer
import android.hardware.camera2.TotalCaptureResult
import com.bncam.core.capture.CameraRequestIdentity
import com.bncam.core.capture.CameraRequestSubmissionType
import com.bncam.core.capture.ControlRequestSnapshot
import com.bncam.core.capture.ControlRequestState
import com.bncam.core.capture.FrameRequestProvenance
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Field
import sun.misc.Unsafe

class FrameRingBufferTest {

    private lateinit var unsafe: Unsafe
    private lateinit var stubHardwareBuffer: HardwareBuffer
    private lateinit var stubTotalCaptureResult: TotalCaptureResult

    @Before
    fun setUp() {
        val field: Field = Unsafe::class.java.getDeclaredField("theUnsafe")
        field.isAccessible = true
        unsafe = field.get(null) as Unsafe

        stubHardwareBuffer = unsafe.allocateInstance(HardwareBuffer::class.java) as HardwareBuffer
        stubTotalCaptureResult = unsafe.allocateInstance(TotalCaptureResult::class.java) as TotalCaptureResult
    }

    private fun addDirectCompleteFrame(
        ring: FrameRingBuffer,
        timestampNs: Long,
        generationId: Int = 1,
        epoch: Long = 100L,
        exposureTimeNs: Long = 20_000_000L,
        rollingShutterSkewNs: Long = 10_000_000L
    ): ZslFramePair {
        val pair = ZslFramePair()
        pair.timestamp = timestampNs
        pair.generationId = generationId
        pair.exposureTimeNs = exposureTimeNs
        pair.rollingShutterSkewNs = rollingShutterSkewNs
        pair.controlRequestEpoch = epoch
        pair.hardwareBuffer = stubHardwareBuffer
        pair.metadata = stubTotalCaptureResult

        val identity = CameraRequestIdentity(
            pipelineGeneration = generationId,
            controlRequestEpoch = epoch
        )
        val state = ControlRequestState.create()
        val snapshot = ControlRequestSnapshot(
            identity = identity,
            state = state,
            submissionType = CameraRequestSubmissionType.REPEATING,
            submissionReason = "PREVIEW",
            meteringPolicySummary = "AUTO",
            exposurePolicySummary = "AUTO",
            submittedElapsedRealtimeNs = 1000L
        )
        pair.requestProvenance = FrameRequestProvenance(
            identity = identity,
            snapshot = snapshot,
            associationStatus = "EXACT_PROVENANCE_MATCH"
        )

        val capacityField = FrameRingBuffer::class.java.getDeclaredField("capacity")
        capacityField.isAccessible = true
        val cap = capacityField.getInt(ring)

        val bufferField = FrameRingBuffer::class.java.getDeclaredField("buffer")
        bufferField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val bufferArray = bufferField.get(ring) as Array<ZslFramePair>

        val headField = FrameRingBuffer::class.java.getDeclaredField("head")
        headField.isAccessible = true
        val head = headField.getInt(ring)

        var targetIndex = -1
        for (i in 0 until cap) {
            val idx = (head + i) % cap
            if (bufferArray[idx].timestamp == 0L && !bufferArray[idx].isLeased) {
                targetIndex = idx
                break
            }
        }
        if (targetIndex == -1) {
            for (i in 0 until cap) {
                val idx = (head + i) % cap
                if (!bufferArray[idx].isLeased) {
                    targetIndex = idx
                    break
                }
            }
        }
        if (targetIndex != -1) {
            bufferArray[targetIndex] = pair
            headField.setInt(ring, (targetIndex + 1) % cap)
        }
        return pair
    }

    @Test
    fun queriedFramesSurviveInspectionAndAreNotDestroyed() {
        val ring = FrameRingBuffer(capacity = 35)
        ring.activateGeneration(1)
        ring.recordImageReaderConfiguration(50)

        for (i in 1..10) {
            val p = addDirectCompleteFrame(ring, timestampNs = 1_000_000_000L + i * 30_000_000L)
            ring.leaseFrame(p)?.close()
        }

        val diagnostics = ring.healthDiagnostics(userShutterTimestampNs = 1_500_000_000L)
        assertEquals(35, diagnostics.targetCapacity)
        assertEquals(0, diagnostics.leasedFrames)
        assertEquals(0, diagnostics.droppedIncomingFrames)
        assertEquals("CAPTURE_READY", diagnostics.bufferState)
        assertTrue(diagnostics.captureReady)
    }

    @Test
    fun coldStartBufferStateTransitionsAndSingleFrameReadiness() {
        val ring = FrameRingBuffer(capacity = 35)
        ring.activateGeneration(1)

        val diagCold = ring.healthDiagnostics(userShutterTimestampNs = 1_500_000_000L)
        assertEquals("BUFFER_COLD", diagCold.bufferState)
        assertFalse(diagCold.captureReady)
        assertEquals(0, diagCold.validCompleteFrameCount)
        assertEquals(0.0, diagCold.warmTargetProgress, 0.001)

        // Add 1 valid frame
        addDirectCompleteFrame(ring, timestampNs = 1_000_000_000L)
        val diagReady = ring.healthDiagnostics(userShutterTimestampNs = 1_500_000_000L)
        assertEquals("CAPTURE_READY", diagReady.bufferState)
        assertTrue(diagReady.captureReady)
        assertEquals(1, diagReady.validCompleteFrameCount)
        assertEquals(1.0 / 35.0, diagReady.warmTargetProgress, 0.001)

        // Fill to 35
        for (i in 2..35) {
            addDirectCompleteFrame(ring, timestampNs = 1_000_000_000L + i * 30_000_000L)
        }
        val diagWarm = ring.healthDiagnostics(userShutterTimestampNs = 2_000_000_000L)
        assertEquals("BUFFER_WARM", diagWarm.bufferState)
        assertTrue(diagWarm.captureReady)
        assertEquals(35, diagWarm.validCompleteFrameCount)
        assertEquals(1.0, diagWarm.warmTargetProgress, 0.001)
    }

    @Test
    fun asyncColdStartWaitWakesImmediatelyWhenPreShutterFrameArrives() = runBlocking {
        val ring = FrameRingBuffer(capacity = 35)
        ring.activateGeneration(1)

        val userShutterTimestampNs = 1_500_000_000L

        val diagCold = ring.healthDiagnostics(userShutterTimestampNs)
        assertEquals("BUFFER_COLD", diagCold.bufferState)

        val deferred = async {
            ring.awaitColdStartPreShutterFrame(userShutterTimestampNs, maxWaitMs = 500L)
        }

        delay(30)
        val insertedFrame = addDirectCompleteFrame(
            ring,
            timestampNs = 1_000_000_000L,
            exposureTimeNs = 20_000_000L,
            rollingShutterSkewNs = 10_000_000L
        )

        val resultFrame = deferred.await()
        assertNotNull("Wait must wake and return the newly completed frame", resultFrame)
        assertEquals(insertedFrame.timestamp, resultFrame!!.timestamp)

        val diagAfter = ring.healthDiagnostics(userShutterTimestampNs)
        assertNotNull(diagAfter.coldStartWaitMs)
        assertTrue("coldStartWaitMs must be > 0 and < maxWaitMs", diagAfter.coldStartWaitMs!! in 0.01..500.0)
    }

    @Test
    fun coldStartWaitTimesOutIfNoEligibleFrameArrives() = runBlocking {
        val ring = FrameRingBuffer(capacity = 35)
        ring.activateGeneration(1)

        val result = ring.awaitColdStartPreShutterFrame(userShutterTimestampNs = 1_500_000_000L, maxWaitMs = 50L)
        assertNull("Wait must return null on timeout when buffer remains cold", result)
    }

    @Test
    fun coldStartWaitRejectsFrameThatOverlapsOrPostdatesShutter() = runBlocking {
        val ring = FrameRingBuffer(capacity = 35)
        ring.activateGeneration(1)
        val userShutterTimestampNs = 1_000_000_000L

        val deferred = async {
            ring.awaitColdStartPreShutterFrame(userShutterTimestampNs, maxWaitMs = 100L)
        }

        delay(10)
        addDirectCompleteFrame(
            ring,
            timestampNs = 990_000_000L,
            exposureTimeNs = 20_000_000L,
            rollingShutterSkewNs = 10_000_000L
        )

        val result = deferred.await()
        assertNull("Frame extending past user shutter moment must NOT satisfy the pre-shutter wait", result)
    }

    @Test
    fun delayedDeliveryFrameFullyExposedBeforeShutterSatisfiesWait() = runBlocking {
        val ring = FrameRingBuffer(capacity = 35)
        ring.activateGeneration(1)
        val userShutterTimestampNs = 1_500_000_000L

        val deferred = async {
            ring.awaitColdStartPreShutterFrame(userShutterTimestampNs, maxWaitMs = 300L)
        }

        delay(40)

        val delayedFrame = addDirectCompleteFrame(
            ring,
            timestampNs = 1_200_000_000L,
            exposureTimeNs = 10_000_000L,
            rollingShutterSkewNs = 10_000_000L
        )

        val result = deferred.await()
        assertNotNull(result)
        assertEquals(delayedFrame.timestamp, result!!.timestamp)
    }

    @Test
    fun clearRequestedDuringActiveLeaseDoesNotDestroyFrameImmediately() {
        val ring = FrameRingBuffer(capacity = 35)
        ring.activateGeneration(1)

        val frame = addDirectCompleteFrame(ring, timestampNs = 1_000_000_000L)
        val lease = ring.leaseFrame(frame)
        assertNotNull(lease)

        ring.clear()

        assertTrue("Leased frame must remain pinned after clear", frame.isLeased)
        assertTrue("Disposal must be marked requested", frame.disposalRequested)
        assertNotNull("Underlying hardwareBuffer must NOT be destroyed while leased", frame.hardwareBuffer)
    }

    @Test
    fun releasingFinalLeaseAfterClearClosesUnderlyingResourcesExactlyOnce() {
        val ring = FrameRingBuffer(capacity = 35)
        ring.activateGeneration(1)

        val frame = addDirectCompleteFrame(ring, timestampNs = 1_000_000_000L)
        val lease = ring.leaseFrame(frame)
        assertNotNull(lease)

        ring.clear()
        lease!!.close()

        assertFalse("Lease count must be 0", frame.isLeased)
        assertFalse("disposalRequested must be cleared after deferred close completes", frame.disposalRequested)
        assertNull("HardwareBuffer must be closed after final lease release", frame.hardwareBuffer)
        assertEquals(1, ring.healthDiagnostics().completedDeferredCloseCount)
    }

    @Test
    fun resizeLargerWhileLeasedWithoutCloningOrLeaseStateCorruption() {
        val ring = FrameRingBuffer(capacity = 20)
        ring.activateGeneration(1)

        val frame = addDirectCompleteFrame(ring, timestampNs = 1_000_000_000L)
        val lease = ring.leaseFrame(frame)
        assertNotNull(lease)

        ring.resizeBuffer(35)

        assertTrue(frame.isLeased)
        assertEquals(35, ring.healthDiagnostics().targetCapacity)

        lease!!.close()
        assertEquals(0, ring.healthDiagnostics().leasedFrames)
        assertEquals(35, ring.healthDiagnostics().writableSlots)
    }

    @Test
    fun resizeSmallerWhileLeased() {
        val ring = FrameRingBuffer(capacity = 35)
        ring.activateGeneration(1)

        val frame = addDirectCompleteFrame(ring, timestampNs = 1_000_000_000L)
        val lease = ring.leaseFrame(frame)
        assertNotNull(lease)

        for (i in 2..35) {
            addDirectCompleteFrame(ring, timestampNs = 1_000_000_000L + i * 30_000_000L)
        }

        ring.resizeBuffer(10)

        assertTrue(frame.isLeased)
        assertTrue(frame.disposalRequested)

        lease!!.close()

        assertEquals(0, ring.healthDiagnostics().leasedFrames)
        assertEquals(1, ring.healthDiagnostics().completedDeferredCloseCount)
    }

    @Test
    fun repeatedResizeDuringSameActiveLease() {
        val ring = FrameRingBuffer(capacity = 35)
        ring.activateGeneration(1)

        val frame = addDirectCompleteFrame(ring, timestampNs = 1_000_000_000L)
        val lease = ring.leaseFrame(frame)
        assertNotNull(lease)

        ring.resizeBuffer(20)
        ring.resizeBuffer(50)
        ring.resizeBuffer(10)
        ring.resizeBuffer(35)

        assertTrue(frame.isLeased)

        lease!!.close()

        val diag = ring.healthDiagnostics()
        assertEquals(0, diag.leasedFrames)
        assertEquals(diag.deferredCloseCount, diag.completedDeferredCloseCount)
    }

    @Test
    fun clearPlusResizePlusGenerationChangeDuringSameLease() {
        val ring = FrameRingBuffer(capacity = 35)
        ring.activateGeneration(1)

        val frame = addDirectCompleteFrame(ring, timestampNs = 1_000_000_000L)
        val lease = ring.leaseFrame(frame)
        assertNotNull(lease)

        ring.clear()
        ring.resizeBuffer(20)
        ring.activateGeneration(5)

        assertTrue(frame.isLeased)

        lease!!.close()

        val diag = ring.healthDiagnostics()
        assertEquals(0, diag.leasedFrames)
        assertEquals(1, diag.deferredCloseCount)
        assertEquals(1, diag.completedDeferredCloseCount)
    }

    @Test
    fun disposalRequestedMultipleTimesIncrementsDeferredCloseCountOnlyOnce() {
        val ring = FrameRingBuffer(capacity = 35)
        ring.activateGeneration(1)

        val frame = addDirectCompleteFrame(ring, timestampNs = 1_000_000_000L)
        val lease = ring.leaseFrame(frame)
        assertNotNull(lease)

        ring.clear()
        ring.clear()
        ring.clear()

        val diagPre = ring.healthDiagnostics()
        assertEquals(1, diagPre.deferredCloseCount)

        lease!!.close()

        val diagPost = ring.healthDiagnostics()
        assertEquals(1, diagPost.deferredCloseCount)
        assertEquals(1, diagPost.completedDeferredCloseCount)
    }

    @Test
    fun telemetryInvariantsVerificationAcrossClearGenerationCaptureCycles() {
        val ring = FrameRingBuffer(capacity = 35)

        for (cycle in 1..5) {
            ring.activateGeneration(cycle)
            for (i in 1..10) {
                addDirectCompleteFrame(ring, timestampNs = cycle * 10_000_000_000L + i * 30_000_000L, generationId = cycle)
            }
            val candidates = ring.queryAndLeaseCandidates(userShutterTimestampNs = cycle * 10_000_000_000L + 200_000_000L)
            assertTrue(candidates.isNotEmpty())

            ring.activateGeneration(cycle + 10)

            candidates.forEach { it.lease.close() }
        }

        val diag = ring.healthDiagnostics()
        assertEquals(0, diag.leakedLeaseCount)
        assertEquals(0, diag.leasedFrameMutations)
        assertEquals(diag.deferredCloseCount, diag.completedDeferredCloseCount)
    }

    @Test
    fun producerInsertsFramesWhileCandidatesAreScored() {
        val ring = FrameRingBuffer(capacity = 35)
        ring.activateGeneration(1)

        for (i in 1..10) {
            addDirectCompleteFrame(ring, timestampNs = 1_000_000_000L + i * 30_000_000L)
        }

        val leasedCandidates = ring.queryAndLeaseCandidates(userShutterTimestampNs = 1_500_000_000L, maxCount = 5)
        assertEquals(5, leasedCandidates.size)

        for (i in 11..50) {
            addDirectCompleteFrame(ring, timestampNs = 1_000_000_000L + i * 30_000_000L)
        }

        for (candidate in leasedCandidates) {
            assertTrue("Leased candidate must stay pinned", candidate.frame.isLeased)
            assertNotNull(candidate.frame.hardwareBuffer)
            assertNotNull(candidate.frame.metadata)
        }

        leasedCandidates.forEach { it.lease.close() }
        assertEquals(0, ring.healthDiagnostics().leasedFrames)
    }

    @Test
    fun noStaleIdentityLeaseAfterSlotReuse() {
        val ring = FrameRingBuffer(capacity = 35)
        ring.activateGeneration(1)

        val frame = addDirectCompleteFrame(ring, timestampNs = 1_000_000_000L)
        val oldVersion = frame.frameVersion

        for (i in 1..50) {
            addDirectCompleteFrame(ring, timestampNs = 2_000_000_000L + i * 30_000_000L)
        }

        val staleLease = ring.leaseFrame(frame, expectedVersion = oldVersion)
        assertNull("Stale lease request with old frameVersion must return null", staleLease)
    }
}
