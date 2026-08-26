package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BufferBackpressureSourceContractTest {
    private fun appRoot(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(path: String): String = File(appRoot(), path).readText()

    @Test
    fun `authoritative warm buffer preserves ordered ImageReader acquisition and headroom`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")

        assertTrue(Regex("reader\\.acquireNextImage\\(\\)").findAll(manager).count() >= 2)
        assertTrue(manager.contains("private fun imageReaderMaxImages(bufferCapacity: Int): Int = bufferCapacity + 2"))
        assertTrue(manager.contains("available.acquireLatestImage()"))
        assertTrue(manager.contains("customRawPreviewReader"))
    }

    @Test
    fun `producer pressure telemetry reports actual reader ownership instead of inferred capacity`() {
        val ring = source("src/main/java/com/bncam/core/buffer/FrameRingBuffer.kt")
        val single = source("src/main/java/com/bncam/core/runners/SingleFrameRunner.kt")
        val multi = source("src/main/java/com/bncam/core/runners/MultiFrameRunner.kt")

        assertTrue(ring.contains("data class ImageReaderPressureDiagnostics("))
        assertTrue(ring.contains("ringResidentImageSlots"))
        assertTrue(ring.contains("producerHeadroom"))
        assertTrue(ring.contains("imageReaderMaxImagesExhaustionCount"))
        assertTrue(ring.contains("drainServiceMedianMs"))
        assertTrue(ring.contains("imageMetadataPairSkewMedianMs"))
        assertTrue(ring.contains("estimatedRingResidentImageBytes"))
        assertTrue(single.contains("val imageReaderPressureAtShutter = ringBuffer.imageReaderPressureDiagnostics()"))
        assertTrue(multi.contains("val maxImagesVal = imageReaderPressureAtShutter.imageReaderMaxImages"))
        assertFalse(multi.contains("val maxImagesVal = ringBuffer.currentCapacity()"))
        assertTrue(multi.contains("ringBufferOverwriteExpected\", \"not_inferred_from_cumulative_acquisition"))
    }

    @Test
    fun `heavy focus and YUV helper analysis no longer execute in the producer pairing callback`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val ring = source("src/main/java/com/bncam/core/buffer/FrameRingBuffer.kt")

        assertFalse(ring.contains("FocusConfidenceEngine.evaluate("))
        assertTrue(manager.contains("private val bufferAnalysisScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)"))
        assertTrue(manager.contains("private val focusAnalysisRequests = Channel<BufferFrameAnalysisRequest>(Channel.CONFLATED)"))
        assertTrue(manager.contains("private val yuvAnalysisRequests = Channel<YuvFrameAnalysisRequest>(Channel.CONFLATED)"))
        assertTrue(manager.contains("leaseNextCompleteFrameForFocusAnalysis("))
        assertTrue(manager.contains("every still-resident complete frame that has not yet received focus metrics"))
        assertTrue(manager.contains("leaseCompleteFrameForAnalysis("))
        assertTrue(manager.contains("scheduleFocusConfidenceAnalysis("))
        assertTrue(manager.contains("scheduleEnabledYuvAnalysis("))
        assertTrue(Regex("runEnabledYuvAnalysis\\(").findAll(manager).count() == 2)
    }

    @Test
    fun `analysis leases never permit producer mutation of the same frame`() {
        val ring = source("src/main/java/com/bncam/core/buffer/FrameRingBuffer.kt")

        assertTrue(ring.contains("addImage rejected duplicate/mutation for leased frame"))
        assertTrue(ring.contains("addMetadata rejected mutation for leased frame"))
        assertTrue(ring.contains("!it.isLeased &&"))
        assertTrue(ring.contains("expectedVersion = expectedVersion"))
        assertTrue(ring.contains("activePair.frameVersion != expectedVersion"))
    }

    @Test
    fun `recovery trace cannot perform filesystem writes from camera producer logging`() {
        val trace = source("src/main/java/com/bncam/core/debug/RawRecoveryTrace.kt")
        val logBody = trace.substringAfter("fun log(event: String, details: String = \"\")")
            .substringBefore("private fun scheduleDrain()")
        val drainBody = trace.substringAfter("private fun scheduleDrain()")
            .substringBefore("fun clearTrace()")

        assertFalse(logBody.contains("FileWriter("))
        assertFalse(logBody.contains("writeText("))
        assertFalse(logBody.contains("appendText("))
        assertTrue(logBody.contains("pendingPermits.tryAcquire()"))
        assertTrue(trace.contains("private const val MAX_PENDING_LINES = 1_024"))
        assertTrue(trace.contains("private const val MAX_DRAIN_BATCH_LINES = 256"))
        assertTrue(trace.contains("Thread(runnable, \"RawRecoveryTraceIO\")"))
        assertTrue(drainBody.contains("while (drained < MAX_DRAIN_BATCH_LINES)"))
        assertTrue(drainBody.contains("FileWriter(file, true)"))
    }

    @Test
    fun `maxImages exhaustion is evidence based and reader metrics reset per generation`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val ring = source("src/main/java/com/bncam/core/buffer/FrameRingBuffer.kt")

        assertTrue(manager.contains("val maxImagesExhausted = ringBuffer.producerHeadroom() <= 0"))
        assertTrue(manager.contains("maxImagesExhausted = maxImagesExhausted"))
        assertFalse(manager.contains("recordImageReaderAcquireFailure(maxImagesExhausted = true)"))
        assertTrue(manager.contains("recordImageAcquired(generationId = startGeneration)"))
        assertTrue(manager.contains("recordImageAcquired(generationId = resetGeneration)"))
        assertTrue(ring.contains("if (generationId != activeGeneration) return"))
        assertTrue(ring.contains("imageReaderMaxImagesExhaustionCount = 0"))
        assertTrue(ring.contains("droppedIncomingFrames = 0"))
        assertTrue(ring.contains("recentImageReaderDrainServiceNs.clear()"))
    }
}
