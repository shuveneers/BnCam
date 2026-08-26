package com.bncam.core.buffer

import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.os.Build
import android.util.Log
import com.bncam.core.capture.FrameRequestProvenance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import java.util.ArrayDeque

data class ZslFrameTimingSnapshot(
    val sensorTimestampNs: Long,
    val exposureTimeNs: Long,
    val imageArrivalElapsedNs: Long,
    val metadataArrivalElapsedNs: Long,
    val pairCompleteElapsedNs: Long,
    val pipelineGeneration: Int,
    val controlRequestEpoch: Long,
    val requestProvenanceStatus: String,
    val timestampSource: Int,
    val timestampSourceLabel: String,
    val sensorTimestampComparableToElapsedRealtime: Boolean,
    val imageDeliveryLagMs: Double?,
    val metadataDeliveryLagMs: Double?,
    val pairCompletionLagMs: Double?
)

enum class FrameRingEventType {
    NONE,
    PAIR_COMPLETED,
    GENERATION_CHANGED,
    BUFFER_CLEARED
}

data class FrameRingEvent(
    val sequence: Long = 0L,
    val type: FrameRingEventType = FrameRingEventType.NONE,
    val pipelineGeneration: Int = -1,
    val controlRequestEpoch: Long = 0L,
    val requestProvenanceStatus: String = "UNPROVEN",
    val sensorTimestampNs: Long = 0L,
    val exposureTimeNs: Long = 0L,
    val imageArrivalElapsedNs: Long = 0L,
    val metadataArrivalElapsedNs: Long = 0L,
    val pairCompleteElapsedNs: Long = 0L,
    val format: Int = 0,
    val timestampSource: Int =
        CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN,
    val sensorTimestampComparableToElapsedRealtime: Boolean = false
)

data class StreamTimingEstimate(
    val frameDurationMedianMs: Double?,
    val imageDeliveryLagMedianMs: Double?,
    val metadataDeliveryLagMedianMs: Double?,
    val pairCompletionLagMedianMs: Double?,
    val frameDurationSampleCount: Int,
    val imageDeliveryLagSampleCount: Int,
    val metadataDeliveryLagSampleCount: Int,
    val pairCompletionLagSampleCount: Int
)

data class FrameRingBufferObservabilitySnapshot(
    val pipelineGeneration: Int,
    val timestampSourceCameraId: String,
    val timestampSource: Int,
    val timestampSourceLabel: String,
    val sensorTimestampComparableToElapsedRealtime: Boolean,
    val actualImageReaderMaxImages: Int,
    val acquiredImageCount: Int,
    val completePairCount: Int,
    val completedPairCountTotal: Int,
    val ringRetainedCount: Int,
    val ringOverwriteCount: Int,
    val completeFrames: List<ZslFrameTimingSnapshot>
)

data class ImageReaderPressureDiagnostics(
    val ringCapacity: Int,
    val imageReaderMaxImages: Int,
    val ringResidentImageSlots: Int,
    val producerHeadroom: Int,
    val leasedFrames: Int,
    val cumulativeImagesAcquired: Int,
    val imageReaderAcquireFailureCount: Int,
    val imageReaderMaxImagesExhaustionCount: Int,
    val drainCallbackCount: Int,
    val drainBatchHighWatermark: Int,
    val drainServiceMedianMs: Double?,
    val drainServiceMaxMs: Double?,
    val imageArrivalCadenceMedianMs: Double?,
    val imageMetadataPairSkewMedianMs: Double?,
    val estimatedFrameBytes: Long,
    val estimatedRingResidentImageBytes: Long,
    val ringOverwriteCount: Int,
    val droppedIncomingFrames: Int,
    val backpressureDetected: Boolean
)

class ZslFramePair {
    var frameVersion: Long = 0L
    var timestamp: Long = 0L
    var image: Image? = null
    var hardwareBuffer: HardwareBuffer? = null
    var metadata: TotalCaptureResult? = null
    var format: Int = 0
    var generationId: Int = -1
    var controlRequestEpoch: Long = 0L
    var requestProvenance: FrameRequestProvenance? = null
    var exposureTimeNs: Long = 0L
    var rollingShutterSkewNs: Long = 0L
    var imageArrivalElapsedNs: Long = 0L
    var metadataArrivalElapsedNs: Long = 0L
    var pairCompleteElapsedNs: Long = 0L
    var timestampSource: Int = CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN
    var sensorTimestampComparableToElapsedRealtime: Boolean = false
    var completionCounted: Boolean = false
    @Volatile var focusScore: Float = 0f
    @Volatile var focusConfidence: Float = 0f
    @Volatile var confidenceState: com.bncam.core.quality.FocusConfidenceState = com.bncam.core.quality.FocusConfidenceState.INDETERMINATE
    @Volatile var afState: Int = CaptureResult.CONTROL_AF_STATE_INACTIVE
    @Volatile var lensFocusDistance: Float = 0f
    @Volatile var lensState: Int = CaptureResult.LENS_STATE_STATIONARY
    var afRegion: Rect? = null
    @Volatile var focusEvaluated: Boolean = false
    @Volatile var pinCount: Int = 0
    @Volatile var disposalRequested: Boolean = false
    val isLeased: Boolean get() = pinCount > 0

    fun releaseHardwareBufferOnly() {
        // Image.getHardwareBuffer() is only valid while its owning Image remains open. Release
        // both references together, with the explicitly acquired HardwareBuffer handle first.
        try { hardwareBuffer?.close() } catch (e: Exception) {
            SafeLog.w("FrameRingBuffer", "HardwareBuffer close failed: ${e.message}")
        } finally {
            hardwareBuffer = null
            try { image?.close() } catch (e: Exception) {
                SafeLog.w("FrameRingBuffer", "Owned Image close failed: ${e.message}")
            } finally { image = null }
        }
    }

    @Synchronized
    fun performRealClose() {
        disposalRequested = false
        try { releaseHardwareBufferOnly() } finally {
            metadata = null
            timestamp = 0L
            format = 0
            generationId = -1
            controlRequestEpoch = 0L
            requestProvenance = null
            exposureTimeNs = 0L
            rollingShutterSkewNs = 0L
            imageArrivalElapsedNs = 0L
            metadataArrivalElapsedNs = 0L
            pairCompleteElapsedNs = 0L
            timestampSource = CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN
            sensorTimestampComparableToElapsedRealtime = false
            completionCounted = false
            focusScore = 0f
            focusConfidence = 0f
            confidenceState = com.bncam.core.quality.FocusConfidenceState.INDETERMINATE
            afState = CaptureResult.CONTROL_AF_STATE_INACTIVE
            lensFocusDistance = 0f
            lensState = CaptureResult.LENS_STATE_STATIONARY
            afRegion = null
            focusEvaluated = false
        }
    }

    /**
     * Closes the ZslFramePair. If pinCount > 0, defers real resource destruction until final release.
     * @return true if disposalRequested transitioned false -> true; false otherwise.
     */
    @Synchronized
    fun close(): Boolean {
        if (pinCount > 0) {
            if (!disposalRequested) {
                disposalRequested = true
                SafeLog.w("FrameRingBuffer", "Disposal requested for leased ZslFramePair (ts=$timestamp, version=$frameVersion, pinCount=$pinCount). Deferring close until lease release.")
                return true
            }
            return false
        }
        performRealClose()
        return false
    }

    @Synchronized
    fun unpinAndCheckDeferredClose(): Boolean {
        if (pinCount > 0) {
            pinCount--
        }
        if (pinCount == 0 && disposalRequested) {
            performRealClose()
            return true
        }
        return false
    }
}

class FrameLease internal constructor(
    val pair: ZslFramePair,
    private val onRelease: () -> Unit
) : AutoCloseable {
    private var released = false
    @Synchronized
    fun release() {
        if (!released) {
            released = true
            onRelease()
        }
    }
    override fun close() = release()
}

data class FrameRingBufferHealthDiagnostics(
    val targetCapacity: Int,
    val completeFrames: Int,
    val leasedFrames: Int,
    val writableSlots: Int,
    val pendingPairs: Int,
    val acquiredImageCount: Int,
    val imageReaderMaxImages: Int,
    val actualProducerHeadroom: Int,
    val evictions: Int,
    val droppedIncomingFrames: Int,
    val leaseHighWatermark: Int,
    val bufferRefillLatencyMs: Double?,
    val bufferRefillState: String,
    val bufferSpanMs: Double,
    val oldestSensorAgeMs: Double?,
    val newestSensorAgeMs: Double?,
    val frameAgeClockBasis: String,
    val pairingFailures: Int,
    val fullyPreShutterCandidateCount: Int,
    val captureReady: Boolean,
    val bufferState: String,
    val validCompleteFrameCount: Int,
    val timeToFirstCompleteFrameMs: Double?,
    val generationToSessionConfiguredMs: Double?,
    val sessionConfiguredToFirstImageMs: Double?,
    val sessionConfiguredToFirstMetadataMs: Double?,
    val sessionConfiguredToFirstCompleteFrameMs: Double?,
    val startupPairingState: String,
    val coldStartWaitMs: Double?,
    val warmTargetProgress: Double,
    val selectionFailureReason: String? = null,
    val leakedLeaseCount: Int = 0,
    val leasedFrameMutations: Int = 0,
    val closedBorrowedFrames: Int = 0,
    val deferredCloseCount: Int = 0,
    val completedDeferredCloseCount: Int = 0
)

private object SafeLog {
    fun i(tag: String, msg: String) { try { Log.i(tag, msg) } catch (_: Throwable) { println("[$tag] $msg") } }
    fun w(tag: String, msg: String) { try { Log.w(tag, msg) } catch (_: Throwable) { println("[$tag] $msg") } }
    fun e(tag: String, msg: String, t: Throwable? = null) { try { if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg) } catch (_: Throwable) { println("[$tag] $msg") } }
}

class FrameRingBuffer(private var capacity: Int = 35) {
    private val tag = "FrameRingBuffer"

    @Volatile var droppedIncomingFrames = 0
    @Volatile var leaseHighWatermark = 0
    @Volatile private var generationStartNs: Long = 0L
    @Volatile private var sessionConfiguredElapsedNs: Long = 0L
    @Volatile private var firstImageArrivalNs: Long = 0L
    @Volatile private var firstMetadataArrivalNs: Long = 0L
    @Volatile private var firstCompleteFrameArrivalNs: Long = 0L
    @Volatile var lastColdStartWaitMs: Double? = null
    @Volatile var lastSelectionFailureReason: String? = null
    @Volatile private var globalFrameVersionCounter = 0L
    @Volatile var deferredCloseCount = 0
    @Volatile var completedDeferredCloseCount = 0
    @Volatile var leasedFrameMutations = 0
    @Volatile var predictiveAfTracker: com.bncam.core.capture.PredictiveAfTracker? = null

    private fun currentElapsedRealtimeNanos(): Long {
        return try {
            android.os.SystemClock.elapsedRealtimeNanos()
        } catch (_: Throwable) {
            System.nanoTime()
        }
    }

    // PRE-ALLOCATED ARRAY: Nul object-creaties tijdens de actieve camera preview!
    private var buffer = Array(capacity) { ZslFramePair() }
    private var head = 0 // Wijst altijd naar het oudste (te overschrijven) slot

    @Volatile
    private var activeGeneration = 0

    @Volatile
    private var timestampSourceCameraId: String = "unknown"

    @Volatile
    private var timestampSource: Int =
        CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN

    @Volatile
    private var actualImageReaderMaxImages: Int = 0

    @Volatile
    var acquiredImageCount: Int = 0

    @Volatile
    var ringOverwriteCount: Int = 0

    @Volatile private var estimatedFrameBytes: Long = 0L
    @Volatile private var imageReaderMaxImagesExhaustionCount: Int = 0
    @Volatile private var imageReaderDrainCallbackCount: Int = 0
    @Volatile private var imageReaderDrainBatchHighWatermark: Int = 0
    @Volatile private var imageReaderDrainServiceMaxNs: Long = 0L
    @Volatile private var lastImageAcquiredElapsedNs: Long = 0L

    @Volatile var acceptedCompleteFrames = 0
    @Volatile var rejectedStale = 0
    @Volatile var rejectedGeneration = 0
    @Volatile var rejectedFormat = 0
    @Volatile var rejectedRequestProvenance = 0
    @Volatile var pairingFailuresCount = 0

    private var eventSequence = 0L
    private val ringEventState = MutableStateFlow(FrameRingEvent())
    private val recentRingEvents = ArrayDeque<FrameRingEvent>()
    private val recentFrameDurationNs = ArrayDeque<Long>()
    private val recentImageDeliveryLagNs = ArrayDeque<Long>()
    private val recentMetadataDeliveryLagNs = ArrayDeque<Long>()
    private val recentPairCompletionLagNs = ArrayDeque<Long>()
    private val recentImageArrivalCadenceNs = ArrayDeque<Long>()
    private val recentImageMetadataPairSkewNs = ArrayDeque<Long>()
    private val recentImageReaderDrainServiceNs = ArrayDeque<Long>()
    private var latestCompletedSensorTimestampNs = 0L

    private companion object {
        const val TIMING_SAMPLE_LIMIT = 9
        const val EVENT_HISTORY_LIMIT = 32
        const val MAX_REASONABLE_FRAME_DURATION_NS = 1_000_000_000L
    }

    private fun timestampSourceLabel(source: Int): String = when (source) {
        CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> "REALTIME"
        CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN -> "UNKNOWN"
        else -> "UNRECOGNIZED($source)"
    }

    private fun isSensorTimestampComparableToElapsedRealtime(source: Int): Boolean =
        source == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME

    private fun addTimingSample(samples: ArrayDeque<Long>, valueNs: Long) {
        if (valueNs < 0L) return
        if (samples.size == TIMING_SAMPLE_LIMIT) {
            samples.removeFirst()
        }
        samples.addLast(valueNs)
    }

    private fun medianMs(samples: ArrayDeque<Long>): Double? {
        if (samples.isEmpty()) return null
        val sorted = samples.toLongArray().sortedArray()
        val middle = sorted.size / 2
        val medianNs = if (sorted.size % 2 == 0) {
            (sorted[middle - 1].toDouble() + sorted[middle].toDouble()) / 2.0
        } else {
            sorted[middle].toDouble()
        }
        return medianNs / 1_000_000.0
    }

    private fun publishRingEvent(
        type: FrameRingEventType,
        pair: ZslFramePair? = null,
        generationId: Int = activeGeneration
    ) {
        eventSequence++
        val event = FrameRingEvent(
            sequence = eventSequence,
            type = type,
            pipelineGeneration = generationId,
            controlRequestEpoch = pair?.controlRequestEpoch ?: 0L,
            requestProvenanceStatus =
                pair?.requestProvenance?.associationStatus ?: "UNPROVEN",
            sensorTimestampNs = pair?.timestamp ?: 0L,
            exposureTimeNs = pair?.exposureTimeNs ?: 0L,
            imageArrivalElapsedNs = pair?.imageArrivalElapsedNs ?: 0L,
            metadataArrivalElapsedNs = pair?.metadataArrivalElapsedNs ?: 0L,
            pairCompleteElapsedNs = pair?.pairCompleteElapsedNs ?: 0L,
            format = pair?.format ?: 0,
            timestampSource = pair?.timestampSource
                ?: CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN,
            sensorTimestampComparableToElapsedRealtime =
                pair?.sensorTimestampComparableToElapsedRealtime ?: false
        )
        if (recentRingEvents.size == EVENT_HISTORY_LIMIT) {
            recentRingEvents.removeFirst()
        }
        recentRingEvents.addLast(event)
        ringEventState.value = event
    }

    private fun recordStreamTiming(pair: ZslFramePair) {
        if (
            latestCompletedSensorTimestampNs > 0L &&
            pair.timestamp > latestCompletedSensorTimestampNs
        ) {
            val durationNs = pair.timestamp - latestCompletedSensorTimestampNs
            if (durationNs in 1 until MAX_REASONABLE_FRAME_DURATION_NS) {
                addTimingSample(recentFrameDurationNs, durationNs)
            }
        }
        if (pair.timestamp > latestCompletedSensorTimestampNs) {
            latestCompletedSensorTimestampNs = pair.timestamp
        }
        if (!pair.sensorTimestampComparableToElapsedRealtime || pair.timestamp <= 0L) {
            return
        }
        if (pair.imageArrivalElapsedNs > 0L) {
            addTimingSample(
                recentImageDeliveryLagNs,
                pair.imageArrivalElapsedNs - pair.timestamp
            )
        }
        if (pair.metadataArrivalElapsedNs > 0L) {
            addTimingSample(
                recentMetadataDeliveryLagNs,
                pair.metadataArrivalElapsedNs - pair.timestamp
            )
        }
        if (pair.pairCompleteElapsedNs > 0L) {
            addTimingSample(
                recentPairCompletionLagNs,
                pair.pairCompleteElapsedNs - pair.timestamp
            )
        }
    }

    @Synchronized
    fun configureTimestampSource(cameraId: String, source: Int) {
        timestampSourceCameraId = cameraId
        timestampSource = source
        Log.i(
            "NearZslTiming",
            "event=TIMESTAMP_DOMAIN cameraId=$cameraId generation=$activeGeneration " +
                    "timestampSource=${timestampSourceLabel(source)}($source) " +
                    "sensorTimestampComparableToElapsedRealtime=${
                        isSensorTimestampComparableToElapsedRealtime(source)
                    }"
        )
    }

    @Synchronized
    fun recordImageReaderConfiguration(
        maxImages: Int,
        format: Int = 0,
        width: Int = 0,
        height: Int = 0,
        generationId: Int = activeGeneration
    ) {
        if (generationId != activeGeneration) return
        actualImageReaderMaxImages = maxImages
        estimatedFrameBytes = when {
            width <= 0 || height <= 0 -> 0L
            format == android.graphics.ImageFormat.RAW_SENSOR -> width.toLong() * height.toLong() * 2L
            format == android.graphics.ImageFormat.RAW10 -> (width.toLong() * height.toLong() * 5L + 3L) / 4L
            format == android.graphics.ImageFormat.YUV_420_888 -> (width.toLong() * height.toLong() * 3L + 1L) / 2L
            else -> width.toLong() * height.toLong() * 2L
        }
        SafeLog.i(
            "NearZslTiming",
            "event=IMAGE_READER_CONFIG generation=$activeGeneration actualMaxImages=$maxImages " +
                    "ringCapacity=$capacity format=$format size=${width}x$height " +
                    "estimatedFrameBytes=$estimatedFrameBytes"
        )
    }

    @Synchronized
    fun recordImageAcquired(generationId: Int = activeGeneration) {
        if (generationId != activeGeneration) return
        val nowNs = currentElapsedRealtimeNanos()
        if (lastImageAcquiredElapsedNs > 0L && nowNs > lastImageAcquiredElapsedNs) {
            addTimingSample(recentImageArrivalCadenceNs, nowNs - lastImageAcquiredElapsedNs)
        }
        lastImageAcquiredElapsedNs = nowNs
        acquiredImageCount++
    }

    @Synchronized
    fun recordImageReaderAcquireFailure(
        generationId: Int = activeGeneration,
        maxImagesExhausted: Boolean = false
    ) {
        if (generationId != activeGeneration) return
        imageReaderAcquireFailureCount++
        if (maxImagesExhausted) {
            imageReaderMaxImagesExhaustionCount++
        }
    }

    @Synchronized
    fun recordImageReaderDrain(
        generationId: Int = activeGeneration,
        callbackServiceNs: Long,
        drainedImages: Int
    ) {
        if (generationId != activeGeneration) return
        imageReaderDrainCallbackCount++
        if (drainedImages > imageReaderDrainBatchHighWatermark) {
            imageReaderDrainBatchHighWatermark = drainedImages
        }
        if (callbackServiceNs >= 0L) {
            addTimingSample(recentImageReaderDrainServiceNs, callbackServiceNs)
            if (callbackServiceNs > imageReaderDrainServiceMaxNs) {
                imageReaderDrainServiceMaxNs = callbackServiceNs
            }
        }
        if (imageReaderDrainCallbackCount <= 3 || imageReaderDrainCallbackCount % 60 == 0) {
            val residentSlots = acquiredImageSlotCount()
            val headroom = maxOf(0, actualImageReaderMaxImages - residentSlots)
            SafeLog.i(
                "NearZslBackpressure",
                "event=IMAGE_READER_PRESSURE generation=$activeGeneration callbacks=$imageReaderDrainCallbackCount " +
                    "drained=$drainedImages drainMedianMs=${medianMs(recentImageReaderDrainServiceNs)} " +
                    "drainMaxMs=${if (imageReaderDrainServiceMaxNs > 0L) imageReaderDrainServiceMaxNs / 1_000_000.0 else null} " +
                    "arrivalCadenceMedianMs=${medianMs(recentImageArrivalCadenceNs)} " +
                    "pairSkewMedianMs=${medianMs(recentImageMetadataPairSkewNs)} " +
                    "residentImages=$residentSlots maxImages=$actualImageReaderMaxImages headroom=$headroom " +
                    "leased=${leasedFrameCount()} overwrites=$ringOverwriteCount droppedIncoming=$droppedIncomingFrames " +
                    "acquireFailures=$imageReaderAcquireFailureCount maxImagesExhaustions=$imageReaderMaxImagesExhaustionCount"
            )
        }
    }

    @Synchronized
    fun activateGeneration(generationId: Int, reason: String = "pipeline_reset") {
        if (generationId == activeGeneration) return
        val oldGen = activeGeneration
        val nowNs = currentElapsedRealtimeNanos()
        val completeBefore = buffer.count { (it.hardwareBuffer != null || it.image != null) && it.metadata != null }
        clear()
        activeGeneration = generationId
        generationStartNs = nowNs
        sessionConfiguredElapsedNs = 0L
        firstImageArrivalNs = 0L
        firstMetadataArrivalNs = 0L
        firstCompleteFrameArrivalNs = 0L
        lastColdStartWaitMs = null
        lastSelectionFailureReason = null
        val completeAfter = 0
        Log.w(
            "NearZslTiming",
            "event=GENERATION_CHANGED reason=$reason oldGeneration=$oldGen newGeneration=$generationId timestampNs=$nowNs completeFramesBefore=$completeBefore completeFramesAfter=$completeAfter"
        )
        publishRingEvent(
            type = FrameRingEventType.GENERATION_CHANGED,
            generationId = generationId
        )
    }

    @Synchronized
    fun currentGeneration(): Int = activeGeneration

    @Synchronized
    fun recordSessionConfigured(generationId: Int = activeGeneration) {
        if (generationId != activeGeneration) return
        if (sessionConfiguredElapsedNs != 0L) return
        sessionConfiguredElapsedNs = currentElapsedRealtimeNanos()
        val fromGenerationMs = if (generationStartNs > 0L) {
            (sessionConfiguredElapsedNs - generationStartNs) / 1_000_000.0
        } else null
        SafeLog.i(
            "NearZslStartup",
            "event=SESSION_CONFIGURED generation=$generationId generationToSessionConfiguredMs=${fromGenerationMs ?: "null"}"
        )
    }

    @Synchronized
    fun currentEventSequence(): Long = eventSequence

    suspend fun awaitEventAfter(sequence: Long): FrameRingEvent =
        ringEventState.first { it.sequence > sequence }

    @Synchronized
    fun eventsAfter(sequence: Long): List<FrameRingEvent> =
        recentRingEvents.filter { it.sequence > sequence }

    @Synchronized
    fun streamTimingEstimate(): StreamTimingEstimate = StreamTimingEstimate(
        frameDurationMedianMs = medianMs(recentFrameDurationNs),
        imageDeliveryLagMedianMs = medianMs(recentImageDeliveryLagNs),
        metadataDeliveryLagMedianMs = medianMs(recentMetadataDeliveryLagNs),
        pairCompletionLagMedianMs = medianMs(recentPairCompletionLagNs),
        frameDurationSampleCount = recentFrameDurationNs.size,
        imageDeliveryLagSampleCount = recentImageDeliveryLagNs.size,
        metadataDeliveryLagSampleCount = recentMetadataDeliveryLagNs.size,
        pairCompletionLagSampleCount = recentPairCompletionLagNs.size
    )

    @Synchronized
    fun addImage(
        image: Image,
        generationId: Int = activeGeneration,
        expectedFormat: Int = image.format
    ): Boolean {
        val imageArrivalElapsedNs = currentElapsedRealtimeNanos()
        // Ownership transfers to the ring only after a valid HardwareBuffer is obtained. Android
        // explicitly forbids using Image.getHardwareBuffer() after Image.close(), so the Image
        // wrapper must remain open for the complete warm-buffer lifetime.
        if (generationId != activeGeneration) {
            rejectedGeneration++
            return false
        }
        val actualFormat = image.format
        if (actualFormat != expectedFormat) {
            rejectedFormat++
            return false
        }
        val ts = image.timestamp
        val timestampComparableToElapsedRealtime =
            isSensorTimestampComparableToElapsedRealtime(timestampSource)
        // SENSOR_TIMESTAMP is only guaranteed to share elapsedRealtime's time base when the
        // camera reports SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME. On UNKNOWN-clock devices the
        // numerical difference can be arbitrarily large, so treating it as frame age can reject
        // every valid warm-buffer image at the producer boundary.
        if (timestampComparableToElapsedRealtime && ts > 0L) {
            val ageMs = (imageArrivalElapsedNs - ts) / 1_000_000
            if (ageMs > 1000L) {
                rejectedStale++
                Log.w(
                    "NearZslTiming",
                    "event=IMAGE_REJECTED_STALE sensorTimestampNs=$ts " +
                            "imageArrivalElapsedNs=$imageArrivalElapsedNs rawDifferenceMs=$ageMs " +
                            "timestampSource=${timestampSourceLabel(timestampSource)} " +
                            "sensorTimestampComparableToElapsedRealtime=true generation=$generationId"
                )
                return false
            }
        }

        val acquiredBuffer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try { image.hardwareBuffer }
            catch (e: Exception) {
                Log.e(tag, "HardwareBuffer ophalen gefaald", e)
                null
            }
        } else null
        if (acquiredBuffer == null) return false
        if (firstImageArrivalNs == 0L) {
            firstImageArrivalNs = imageArrivalElapsedNs
            val fromConfiguredMs = if (sessionConfiguredElapsedNs > 0L) {
                (firstImageArrivalNs - sessionConfiguredElapsedNs) / 1_000_000.0
            } else null
            SafeLog.i(
                "NearZslStartup",
                "event=FIRST_IMAGE_ACCEPTED generation=$generationId sensorTimestampNs=$ts " +
                        "sessionConfiguredToImageMs=${fromConfiguredMs ?: "null"} format=$actualFormat"
            )
        }

        val leasedExistingPair = buffer.firstOrNull {
            it.timestamp == ts && it.generationId == generationId && it.isLeased
        }
        if (leasedExistingPair != null) {
            leasedFrameMutations++
            SafeLog.w(
                tag,
                "addImage rejected duplicate/mutation for leased frame ts=$ts " +
                    "version=${leasedExistingPair.frameVersion} pinCount=${leasedExistingPair.pinCount}"
            )
            try { acquiredBuffer.close() } catch (_: Exception) {}
            return false
        }

        val pair = getOrAllocatePair(ts, generationId)
        if (pair == null) {
            droppedIncomingFrames++
            SafeLog.w(tag, "addImage dropped frame ts=$ts: All ring buffer slots are currently leased/pinned.")
            try { acquiredBuffer.close() } catch (_: Exception) {}
            return false
        }
        pair.releaseHardwareBufferOnly()
        pair.image = image
        pair.hardwareBuffer = acquiredBuffer
        pair.format = actualFormat
        pair.imageArrivalElapsedNs = imageArrivalElapsedNs
        pair.timestampSource = timestampSource
        pair.sensorTimestampComparableToElapsedRealtime = timestampComparableToElapsedRealtime
        com.bncam.core.debug.RawRecoveryTrace.log("ADD_IMAGE", "ts=$ts, format=$actualFormat, gen=$generationId")
        checkCompletion(pair)
        return true
    }

    @Synchronized
    fun addMetadata(
        timestamp: Long,
        result: TotalCaptureResult,
        generationId: Int = activeGeneration,
        requestProvenance: FrameRequestProvenance?
    ) {
        val metadataArrivalElapsedNs = currentElapsedRealtimeNanos()
        if (generationId != activeGeneration) {
            rejectedGeneration++
            return
        }
        if (
            requestProvenance == null ||
            requestProvenance.identity.pipelineGeneration != generationId ||
            requestProvenance.identity.controlRequestEpoch <= 0L ||
            requestProvenance.snapshot.identity != requestProvenance.identity
        ) {
            rejectedRequestProvenance++
            Log.w(
                "NearZslProvenance",
                "event=METADATA_REJECTED_UNPROVEN_REQUEST sensorTimestampNs=$timestamp " +
                        "pipelineGeneration=$generationId " +
                        "controlRequestEpoch=${requestProvenance?.identity?.controlRequestEpoch ?: 0L} " +
                        "status=${requestProvenance?.associationStatus ?: "UNPROVEN"}"
            )
            return
        }
        if (firstMetadataArrivalNs == 0L) {
            firstMetadataArrivalNs = metadataArrivalElapsedNs
            val fromConfiguredMs = if (sessionConfiguredElapsedNs > 0L) {
                (firstMetadataArrivalNs - sessionConfiguredElapsedNs) / 1_000_000.0
            } else null
            SafeLog.i(
                "NearZslStartup",
                "event=FIRST_METADATA_ACCEPTED generation=$generationId sensorTimestampNs=$timestamp " +
                        "sessionConfiguredToMetadataMs=${fromConfiguredMs ?: "null"} " +
                        "provenance=${requestProvenance.associationStatus}"
            )
        }
        val leasedExistingPair = buffer.firstOrNull {
            it.timestamp == timestamp && it.generationId == generationId && it.isLeased
        }
        if (leasedExistingPair != null) {
            leasedFrameMutations++
            SafeLog.w(
                tag,
                "addMetadata rejected mutation for leased frame ts=$timestamp " +
                    "version=${leasedExistingPair.frameVersion} pinCount=${leasedExistingPair.pinCount}"
            )
            return
        }

        val pair = getOrAllocatePair(timestamp, generationId)
        if (pair == null) {
            droppedIncomingFrames++
            SafeLog.w(tag, "addMetadata dropped metadata ts=$timestamp: All ring buffer slots are currently leased/pinned.")
            return
        }
        pair.metadata = result
        pair.generationId = generationId
        pair.controlRequestEpoch = requestProvenance.identity.controlRequestEpoch
        pair.requestProvenance = requestProvenance
        pair.exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
        pair.metadataArrivalElapsedNs = metadataArrivalElapsedNs
        pair.timestampSource = timestampSource
        pair.sensorTimestampComparableToElapsedRealtime =
            isSensorTimestampComparableToElapsedRealtime(timestampSource)
        com.bncam.core.debug.RawRecoveryTrace.log("ADD_METADATA", "ts=$timestamp, gen=$generationId, prov=${requestProvenance.associationStatus}")
        checkCompletion(pair)
    }

    /**
     * Provides a complete frame only for the duration of [block]. The HardwareBuffer is borrowed:
     * callers must neither close it nor let it escape this callback. Native users that outlive the
     * callback must acquire their own AHardwareBuffer reference before returning.
     */
    @Synchronized
    fun withBorrowedCompleteFrame(
        timestamp: Long,
        generationId: Int = activeGeneration,
        block: (android.hardware.HardwareBuffer, TotalCaptureResult) -> Unit
    ): Boolean {
        if (generationId != activeGeneration) return false
        val pair = buffer.firstOrNull {
            it.timestamp == timestamp &&
                    it.generationId == generationId &&
                    it.hardwareBuffer != null &&
                    it.metadata != null &&
                    hasExactRequestProvenance(it)
        } ?: return false
        block(pair.hardwareBuffer!!, pair.metadata!!)
        return true
    }

    /**
     * Returns the newest image-backed timestamp for display priming without requiring metadata
     * pairing. The live RAW preview is intentionally image-driven; calibration metadata is sourced
     * independently from the latest capture result. No ownership escapes this lookup.
     */
    @Synchronized
    fun latestImageTimestamp(generationId: Int = activeGeneration): Long? {
        if (generationId != activeGeneration) return null
        return buffer.asSequence()
            .filter { it.generationId == generationId && it.timestamp > 0L && it.hardwareBuffer != null }
            .maxOfOrNull { it.timestamp }
    }

    /** Image-only counterpart for the latency-sensitive viewfinder path. */
    @Synchronized
    fun withBorrowedImageFrame(
        timestamp: Long,
        generationId: Int = activeGeneration,
        block: (android.hardware.HardwareBuffer) -> Unit
    ): Boolean {
        if (generationId != activeGeneration) return false
        val pair = buffer.firstOrNull {
            it.timestamp == timestamp &&
                    it.generationId == generationId &&
                    it.hardwareBuffer != null
        } ?: return false
        // The same scoped ownership rule applies: retain natively before this callback returns.
        block(pair.hardwareBuffer!!)
        return true
    }

    private fun hasExactRequestProvenance(pair: ZslFramePair): Boolean {
        val provenance = pair.requestProvenance ?: return false
        return pair.controlRequestEpoch > 0L &&
                provenance.identity.pipelineGeneration == pair.generationId &&
                provenance.identity.controlRequestEpoch == pair.controlRequestEpoch &&
                provenance.snapshot.identity == provenance.identity
    }

    private fun checkCompletion(pair: ZslFramePair) {
        if (pair.hardwareBuffer == null) {
            com.bncam.core.debug.RawRecoveryTrace.log("CHECK_COMPLETION_NO_HWBUF", "ts=${pair.timestamp}, format=${pair.format}")
            return
        }
        if (pair.metadata == null) {
            com.bncam.core.debug.RawRecoveryTrace.log("CHECK_COMPLETION_NO_META", "ts=${pair.timestamp}, format=${pair.format}")
            return
        }
        if (!hasExactRequestProvenance(pair)) {
            val prov = pair.requestProvenance
            com.bncam.core.debug.RawRecoveryTrace.log(
                "CHECK_COMPLETION_PROVENANCE_FAIL",
                "ts=${pair.timestamp}, format=${pair.format}, pairEpoch=${pair.controlRequestEpoch}, provEpoch=${prov?.identity?.controlRequestEpoch}, gen=${pair.generationId}, provGen=${prov?.identity?.pipelineGeneration}"
            )
            return
        }
        if (pair.pairCompleteElapsedNs == 0L) {
            pair.pairCompleteElapsedNs = currentElapsedRealtimeNanos()
        }
        if (firstCompleteFrameArrivalNs == 0L) {
            firstCompleteFrameArrivalNs = currentElapsedRealtimeNanos()
            val fromConfiguredMs = if (sessionConfiguredElapsedNs > 0L) {
                (firstCompleteFrameArrivalNs - sessionConfiguredElapsedNs) / 1_000_000.0
            } else null
            SafeLog.i(
                "NearZslStartup",
                "event=FIRST_COMPLETE_PAIR generation=${pair.generationId} sensorTimestampNs=${pair.timestamp} " +
                        "sessionConfiguredToCompleteMs=${fromConfiguredMs ?: "null"} " +
                        "imageArrivalElapsedNs=${pair.imageArrivalElapsedNs} metadataArrivalElapsedNs=${pair.metadataArrivalElapsedNs}"
            )
        }
        if (!pair.completionCounted) {
            pair.completionCounted = true
            acceptedCompleteFrames++
            recordStreamTiming(pair)
            if (pair.imageArrivalElapsedNs > 0L && pair.metadataArrivalElapsedNs > 0L) {
                addTimingSample(
                    recentImageMetadataPairSkewNs,
                    kotlin.math.abs(pair.imageArrivalElapsedNs - pair.metadataArrivalElapsedNs)
                )
            }
            com.bncam.core.debug.RawRecoveryTrace.log("PAIR_COMPLETE", "ts=${pair.timestamp}, format=${pair.format}, gen=${pair.generationId}, prov=${pair.requestProvenance?.associationStatus}")
            val comparable = pair.sensorTimestampComparableToElapsedRealtime
            fun deliveryLagMs(arrivalElapsedNs: Long): String {
                return if (comparable && arrivalElapsedNs > 0L && pair.timestamp > 0L) {
                    String.format(
                        java.util.Locale.US,
                        "%.3f",
                        (arrivalElapsedNs - pair.timestamp) / 1_000_000.0
                    )
                } else {
                    "unavailable_clock_domain"
                }
            }
            if (acceptedCompleteFrames <= 3 || acceptedCompleteFrames % 30 == 0) {
                Log.i(
                    "NearZslTiming",
                    "event=PAIR_COMPLETE sensorTimestampNs=${pair.timestamp} " +
                            "exposureTimeNs=${pair.exposureTimeNs} " +
                            "imageArrivalElapsedNs=${pair.imageArrivalElapsedNs} " +
                            "metadataArrivalElapsedNs=${pair.metadataArrivalElapsedNs} " +
                            "pairCompleteElapsedNs=${pair.pairCompleteElapsedNs} " +
                            "imageDeliveryLagMs=${deliveryLagMs(pair.imageArrivalElapsedNs)} " +
                            "metadataDeliveryLagMs=${deliveryLagMs(pair.metadataArrivalElapsedNs)} " +
                            "pairCompletionLagMs=${deliveryLagMs(pair.pairCompleteElapsedNs)} " +
                            "generation=${pair.generationId} " +
                            "controlRequestEpoch=${pair.controlRequestEpoch} " +
                            "requestProvenanceStatus=${pair.requestProvenance?.associationStatus} " +
                            "timestampSource=${timestampSourceLabel(pair.timestampSource)} " +
                            "sensorTimestampComparableToElapsedRealtime=$comparable " +
                            "completedPairCountTotal=$acceptedCompleteFrames"
                )
            }
            publishRingEvent(
                type = FrameRingEventType.PAIR_COMPLETED,
                pair = pair,
                generationId = pair.generationId
            )
        }
    }

    @Volatile var framesDroppedByRingBuffer = 0
    @Volatile var imageReaderAcquireFailureCount = 0

    // O(1) of maximaal O(N) met N=35. Extreem snel zonder memory allocations.
    private fun getOrAllocatePair(timestamp: Long, generationId: Int): ZslFramePair? {
        val nowNs = currentElapsedRealtimeNanos()
        // 1. Zoek of de andere helft (image of metadata) al in de buffer zit (match ONLY by timestamp)
        for (i in buffer.indices) {
            if (buffer[i].timestamp == timestamp) return buffer[i]
        }

        // 1b. Opruimen van verlopen incomplete paren (TTL > 500ms) zodat ze geen gezonde complete paren verdringen
        val incompleteTtlNs = 500_000_000L
        for (i in buffer.indices) {
            val candidate = buffer[i]
            if (!candidate.isLeased && candidate.timestamp > 0L) {
                val isIncomplete = (candidate.image != null || candidate.hardwareBuffer != null) xor (candidate.metadata != null)
                val arrival = maxOf(candidate.imageArrivalElapsedNs, candidate.metadataArrivalElapsedNs)
                if (isIncomplete && arrival > 0L && (nowNs - arrival) > incompleteTtlNs) {
                    pairingFailuresCount++
                    candidate.close()
                }
            }
        }

        // 2. Selecteer een niet-geleased slot voor overschrijving met voorkeursvolgorde:
        // A: Leeg slot (timestamp == 0L)
        // B: Incompleet slot
        // C: Oudste niet-geleased slot (vanaf head)
        var targetIndex = -1
        for (i in buffer.indices) {
            val idx = (head + i) % capacity
            if (buffer[idx].timestamp == 0L && !buffer[idx].isLeased) {
                targetIndex = idx
                break
            }
        }
        if (targetIndex == -1) {
            val incompleteGraceNs = 200_000_000L
            for (i in buffer.indices) {
                val idx = (head + i) % capacity
                val candidate = buffer[idx]
                if (!candidate.isLeased && candidate.timestamp > 0L) {
                    val isIncomplete = (candidate.image != null || candidate.hardwareBuffer != null) xor (candidate.metadata != null)
                    val arrival = maxOf(candidate.imageArrivalElapsedNs, candidate.metadataArrivalElapsedNs)
                    if (isIncomplete && arrival > 0L && (nowNs - arrival) > incompleteGraceNs) {
                        targetIndex = idx
                        break
                    }
                }
            }
        }
        if (targetIndex == -1) {
            for (i in buffer.indices) {
                val idx = (head + i) % capacity
                if (!buffer[idx].isLeased) {
                    targetIndex = idx
                    break
                }
            }
        }
        if (targetIndex == -1) {
            // NEVER overwrite a leased/pinned slot!
            return null
        }

        val pair = buffer[targetIndex]
        if (
            pair.timestamp != 0L &&
            (pair.image != null || pair.hardwareBuffer != null || pair.metadata != null)
        ) {
            ringOverwriteCount++
            SafeLog.i(
                "NearZslTiming",
                "event=RING_OVERWRITE overwrittenSensorTimestampNs=${pair.timestamp} " +
                        "overwrittenGeneration=${pair.generationId} " +
                        "overwrittenComplete=${pair.hardwareBuffer != null && pair.metadata != null} " +
                        "ringOverwriteCount=$ringOverwriteCount capacity=$capacity"
            )
        }
        if (pair.hardwareBuffer != null && pair.metadata != null) {
            framesDroppedByRingBuffer++
        }
        pair.close() // Safe reset if not leased
        pair.frameVersion = ++globalFrameVersionCounter
        pair.timestamp = timestamp
        pair.generationId = generationId

        head = (targetIndex + 1) % capacity
        return pair
    }

    @Synchronized
    fun getLatestCompleteFrames(count: Int): List<ZslFramePair> {
        return buffer.filter {
            it.generationId == activeGeneration && hasExactRequestProvenance(it) &&
                    it.timestamp != 0L && it.image != null &&
                    it.hardwareBuffer != null && it.metadata != null
        }
            .sortedBy { it.metadata?.get(CaptureResult.SENSOR_TIMESTAMP) ?: it.timestamp }
            .takeLast(count)
    }

    @Synchronized
    fun queryCandidates(
        userShutterTimestampNs: Long = 0L,
        maxCount: Int = 50,
        shutterTimestampDomain: String = "ELAPSED_REALTIME"
    ): List<ZslFramePair> {
        val valid = buffer.filter {
            it.generationId == activeGeneration &&
                    hasExactRequestProvenance(it) &&
                    it.timestamp != 0L &&
                    (it.hardwareBuffer != null || (it.format == android.graphics.ImageFormat.YUV_420_888 && it.image != null)) &&
                    it.metadata != null
        }
        val filtered = if (userShutterTimestampNs > 0L) {
            valid.filter { pair ->
                NearZslEligibilityPolicy.isFullyPreShutter(
                    pair,
                    userShutterTimestampNs,
                    shutterTimestampDomain
                )
            }
        } else {
            valid
        }
        return filtered.sortedBy {
            try { it.metadata?.get(CaptureResult.SENSOR_TIMESTAMP) } catch (_: Throwable) { null } ?: it.timestamp
        }.takeLast(maxCount)
    }

    fun recordSelectionFailureReason(reason: String) {
        lastSelectionFailureReason = reason
    }

    suspend fun awaitColdStartPreShutterFrame(
        userShutterTimestampNs: Long,
        maxWaitMs: Long = 200L,
        shutterTimestampDomain: String = "ELAPSED_REALTIME"
    ): ZslFramePair? {
        val startNs = currentElapsedRealtimeNanos()
        val maxWaitNs = maxWaitMs * 1_000_000L
        val initialCandidates = queryCandidates(userShutterTimestampNs, maxCount = capacity, shutterTimestampDomain = shutterTimestampDomain)
        if (initialCandidates.isNotEmpty()) {
            lastColdStartWaitMs = 0.0
            return initialCandidates.last()
        }

        var seq = currentEventSequence()
        while (currentElapsedRealtimeNanos() - startNs < maxWaitNs) {
            val candidates = queryCandidates(userShutterTimestampNs, maxCount = capacity, shutterTimestampDomain = shutterTimestampDomain)
            if (candidates.isNotEmpty()) {
                val elapsedMs = (currentElapsedRealtimeNanos() - startNs) / 1_000_000.0
                lastColdStartWaitMs = elapsedMs
                return candidates.last()
            }
            kotlinx.coroutines.withTimeoutOrNull(25L) {
                awaitEventAfter(seq)
            }
            seq = currentEventSequence()
        }
        val finalResult = queryCandidates(userShutterTimestampNs, maxCount = capacity, shutterTimestampDomain = shutterTimestampDomain).lastOrNull()
        val elapsedMs = (currentElapsedRealtimeNanos() - startNs) / 1_000_000.0
        lastColdStartWaitMs = elapsedMs
        return finalResult
    }

    data class LeasedCandidate(
        val frame: ZslFramePair,
        val lease: FrameLease,
        val frameVersion: Long,
        val timestampNs: Long
    )

    @Synchronized
    fun queryAndLeaseCandidates(
        userShutterTimestampNs: Long = 0L,
        maxCount: Int = 50,
        shutterTimestampDomain: String = "ELAPSED_REALTIME",
        excludeFrameVersions: Set<Long> = emptySet(),
        expectedFormat: Int? = null
    ): List<LeasedCandidate> {
        if (maxCount <= 0) return emptyList()
        val valid = buffer.filter {
            it.generationId == activeGeneration &&
                    it.frameVersion !in excludeFrameVersions &&
                    (expectedFormat == null || it.format == expectedFormat) &&
                    hasExactRequestProvenance(it) &&
                    it.timestamp != 0L &&
                    it.hardwareBuffer != null &&
                    it.metadata != null
        }
        val filtered = if (userShutterTimestampNs > 0L) {
            valid.filter { pair ->
                NearZslEligibilityPolicy.isFullyPreShutter(
                    pair,
                    userShutterTimestampNs,
                    shutterTimestampDomain
                )
            }
        } else {
            valid
        }
        val selected = filtered.sortedBy {
            try { it.metadata?.get(CaptureResult.SENSOR_TIMESTAMP) } catch (_: Throwable) { null } ?: it.timestamp
        }.takeLast(maxCount)

        com.bncam.core.debug.RawRecoveryTrace.log(
            "QUERY_LEASE_CANDIDATES",
            "userShutterTs=$userShutterTimestampNs, totalValid=${valid.size}, filteredPreShutter=${filtered.size}, selectedLeased=${selected.size}, " +
                "excludedFrameVersions=${excludeFrameVersions.size}, expectedFormat=${expectedFormat ?: -1}, currentGen=$activeGeneration"
        )

        return selected.mapNotNull { pair ->
            val lease = leaseFrameInternal(pair) ?: return@mapNotNull null
            LeasedCandidate(
                frame = pair,
                lease = lease,
                frameVersion = pair.frameVersion,
                timestampNs = pair.timestamp
            )
        }
    }

    @Synchronized
    fun leaseBurstAtomic(pairs: List<ZslFramePair>): List<FrameLease>? {
        val leases = mutableListOf<FrameLease>()
        for (pair in pairs) {
            val lease = leaseFrameInternal(pair)
            if (lease == null) {
                leases.forEach { it.release() }
                return null
            }
            leases.add(lease)
        }
        return leases
    }

    @Synchronized
    fun leaseFrame(pair: ZslFramePair, expectedVersion: Long = 0L): FrameLease? {
        return leaseFrameInternal(pair, expectedVersion)
    }

    /**
     * Waits for one exact post-shutter Camera2 request to become a complete ring pair. HDR uses
     * this instead of [queryCandidates], which intentionally exposes pre-shutter ZSL only.
     */
    suspend fun awaitAndLeaseExactRequestFrame(
        sensorTimestampNs: Long,
        generationId: Int,
        controlRequestEpoch: Long,
        expectedFormat: Int,
        maxWaitMs: Long = 800L
    ): FrameLease? {
        if (sensorTimestampNs <= 0L || controlRequestEpoch <= 0L || generationId != currentGeneration()) {
            return null
        }
        val deadlineNs = currentElapsedRealtimeNanos() + maxWaitMs.coerceAtLeast(1L) * 1_000_000L
        var sequence = currentEventSequence()
        while (currentElapsedRealtimeNanos() <= deadlineNs) {
            synchronized(this) {
                val pair = buffer.firstOrNull { candidate ->
                    candidate.timestamp == sensorTimestampNs &&
                        candidate.generationId == generationId &&
                        candidate.controlRequestEpoch == controlRequestEpoch &&
                        candidate.format == expectedFormat &&
                        candidate.hardwareBuffer != null &&
                        candidate.metadata != null &&
                        hasExactRequestProvenance(candidate)
                }
                if (pair != null) {
                    return leaseFrameInternal(pair, pair.frameVersion)
                }
            }
            kotlinx.coroutines.withTimeoutOrNull(30L) { awaitEventAfter(sequence) }
            sequence = currentEventSequence()
        }
        return synchronized(this) {
            val pair = buffer.firstOrNull { candidate ->
                candidate.timestamp == sensorTimestampNs &&
                    candidate.generationId == generationId &&
                    candidate.controlRequestEpoch == controlRequestEpoch &&
                    candidate.format == expectedFormat &&
                    candidate.hardwareBuffer != null &&
                    candidate.metadata != null &&
                    hasExactRequestProvenance(candidate)
            } ?: return@synchronized null
            leaseFrameInternal(pair, pair.frameVersion)
        }
    }

    /**
     * Epoch-authoritative variant for one-shot still requests when SENSOR_TIMESTAMP is unavailable
     * to the caller. One-shot capture intent changes make the control epoch unique to the still
     * transaction; exact request provenance is still mandatory.
     */
    suspend fun awaitAndLeaseExactRequestEpochFrame(
        generationId: Int,
        controlRequestEpoch: Long,
        expectedFormat: Int,
        maxWaitMs: Long = 800L
    ): FrameLease? {
        if (controlRequestEpoch <= 0L || generationId != currentGeneration()) return null
        val deadlineNs = currentElapsedRealtimeNanos() + maxWaitMs.coerceAtLeast(1L) * 1_000_000L
        var sequence = currentEventSequence()
        while (currentElapsedRealtimeNanos() <= deadlineNs) {
            synchronized(this) {
                val pair = buffer.firstOrNull { candidate ->
                    candidate.generationId == generationId &&
                        candidate.controlRequestEpoch == controlRequestEpoch &&
                        candidate.format == expectedFormat &&
                        candidate.hardwareBuffer != null &&
                        candidate.metadata != null &&
                        hasExactRequestProvenance(candidate)
                }
                if (pair != null) return leaseFrameInternal(pair, pair.frameVersion)
            }
            kotlinx.coroutines.withTimeoutOrNull(30L) { awaitEventAfter(sequence) }
            sequence = currentEventSequence()
        }
        return synchronized(this) {
            val pair = buffer.firstOrNull { candidate ->
                candidate.generationId == generationId &&
                    candidate.controlRequestEpoch == controlRequestEpoch &&
                    candidate.format == expectedFormat &&
                    candidate.hardwareBuffer != null &&
                    candidate.metadata != null &&
                    hasExactRequestProvenance(candidate)
            } ?: return@synchronized null
            leaseFrameInternal(pair, pair.frameVersion)
        }
    }

    @Synchronized
    fun leaseCompleteFrameForAnalysis(
        timestampNs: Long,
        generationId: Int,
        expectedFormat: Int
    ): FrameLease? {
        if (generationId != activeGeneration) return null
        val pair = buffer.firstOrNull {
            it.timestamp == timestampNs &&
                it.generationId == generationId &&
                it.format == expectedFormat &&
                it.image != null &&
                it.hardwareBuffer != null &&
                it.metadata != null &&
                hasExactRequestProvenance(it)
        } ?: return null
        return leaseFrameInternal(pair, pair.frameVersion)
    }

    @Synchronized
    fun leaseNextCompleteFrameForFocusAnalysis(
        generationId: Int,
        expectedFormat: Int
    ): FrameLease? {
        if (generationId != activeGeneration) return null
        val pair = buffer
            .asSequence()
            .filter {
                it.timestamp != 0L &&
                    it.generationId == generationId &&
                    it.format == expectedFormat &&
                    it.image != null &&
                    it.hardwareBuffer != null &&
                    it.metadata != null &&
                    !it.focusEvaluated &&
                    !it.isLeased &&
                    hasExactRequestProvenance(it)
            }
            .minByOrNull { it.timestamp }
            ?: return null
        return leaseFrameInternal(pair, pair.frameVersion)
    }

    @Synchronized
    fun commitFocusMetrics(
        pair: ZslFramePair,
        expectedVersion: Long,
        metrics: com.bncam.core.quality.FocusMetrics
    ): Boolean {
        val activePair = buffer.firstOrNull { it === pair } ?: return false
        if (activePair.frameVersion != expectedVersion ||
            activePair.generationId != activeGeneration ||
            activePair.disposalRequested) {
            return false
        }
        activePair.focusScore = metrics.focusScore
        activePair.focusConfidence = metrics.focusConfidence
        activePair.confidenceState = metrics.confidenceState
        activePair.afState = metrics.afState
        activePair.lensFocusDistance = metrics.lensFocusDistance
        activePair.lensState = metrics.lensState
        activePair.afRegion = metrics.afRegion
        activePair.focusEvaluated = true
        predictiveAfTracker?.updateHistory(activePair)
        return true
    }

    private fun leaseFrameInternal(pair: ZslFramePair, expectedVersion: Long = 0L): FrameLease? {
        val target = buffer.firstOrNull {
            it === pair || (it.timestamp == pair.timestamp && it.generationId == pair.generationId)
        } ?: return null
        if (expectedVersion > 0L && target.frameVersion != expectedVersion) {
            return null
        }
        target.pinCount++
        val activeCount = buffer.count { it.isLeased }
        if (activeCount > leaseHighWatermark) {
            leaseHighWatermark = activeCount
        }
        return FrameLease(target) {
            synchronized(this@FrameRingBuffer) {
                val deferredExecuted = target.unpinAndCheckDeferredClose()
                if (deferredExecuted) {
                    completedDeferredCloseCount++
                }
            }
        }
    }

    @Synchronized
    fun leaseFrames(pairs: List<ZslFramePair>): List<FrameLease> {
        return pairs.mapNotNull { leaseFrame(it) }
    }

    @Synchronized
    fun takeLatestCompleteFrames(count: Int): List<ZslFramePair> {
        return getLatestCompleteFrames(count)
    }

    @Synchronized
    fun takeCompleteFrame(
        timestampNs: Long,
        generationId: Int,
        expectedFormat: Int
    ): ZslFramePair? {
        return buffer.firstOrNull {
            it.timestamp == timestampNs &&
                    it.generationId == generationId &&
                    hasExactRequestProvenance(it) &&
                    it.format == expectedFormat &&
                    it.image != null &&
                    it.hardwareBuffer != null &&
                    it.metadata != null
        }
    }

    private fun transferPairOwnership(pair: ZslFramePair): ZslFramePair {
        val copy = ZslFramePair().apply {
            this.timestamp = pair.timestamp
            this.image = pair.image
            this.hardwareBuffer = pair.hardwareBuffer
            this.metadata = pair.metadata
            this.format = pair.format
            this.generationId = pair.generationId
            this.controlRequestEpoch = pair.controlRequestEpoch
            this.requestProvenance = pair.requestProvenance
            this.exposureTimeNs = pair.exposureTimeNs
            this.imageArrivalElapsedNs = pair.imageArrivalElapsedNs
            this.metadataArrivalElapsedNs = pair.metadataArrivalElapsedNs
            this.pairCompleteElapsedNs = pair.pairCompleteElapsedNs
            this.timestampSource = pair.timestampSource
            this.sensorTimestampComparableToElapsedRealtime =
                pair.sensorTimestampComparableToElapsedRealtime
            this.completionCounted = pair.completionCounted
        }
        pair.timestamp = 0L
        pair.image = null
        pair.hardwareBuffer = null
        pair.metadata = null
        pair.format = 0
        pair.generationId = -1
        pair.controlRequestEpoch = 0L
        pair.requestProvenance = null
        pair.exposureTimeNs = 0L
        pair.imageArrivalElapsedNs = 0L
        pair.metadataArrivalElapsedNs = 0L
        pair.pairCompleteElapsedNs = 0L
        pair.timestampSource =
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN
        pair.sensorTimestampComparableToElapsedRealtime = false
        pair.completionCounted = false
        return copy
    }

    @Synchronized fun currentCapacity(): Int = capacity

    @Synchronized
    fun observabilitySnapshot(): FrameRingBufferObservabilitySnapshot {
        val retainedPairs = buffer.filter {
            it.generationId == activeGeneration &&
                    it.timestamp != 0L &&
                    (it.image != null || it.hardwareBuffer != null || it.metadata != null)
        }
        val completePairs = retainedPairs.filter {
            hasExactRequestProvenance(it) &&
                    it.image != null &&
                    it.hardwareBuffer != null &&
                    it.metadata != null
        }.sortedBy { it.timestamp }
        val comparable =
            isSensorTimestampComparableToElapsedRealtime(timestampSource)
        fun lagMs(sensorTimestampNs: Long, arrivalElapsedNs: Long): Double? {
            return if (comparable && sensorTimestampNs > 0L && arrivalElapsedNs > 0L) {
                (arrivalElapsedNs - sensorTimestampNs) / 1_000_000.0
            } else {
                null
            }
        }
        val frames = completePairs.map { pair ->
            ZslFrameTimingSnapshot(
                sensorTimestampNs = pair.timestamp,
                exposureTimeNs = pair.exposureTimeNs,
                imageArrivalElapsedNs = pair.imageArrivalElapsedNs,
                metadataArrivalElapsedNs = pair.metadataArrivalElapsedNs,
                pairCompleteElapsedNs = pair.pairCompleteElapsedNs,
                pipelineGeneration = pair.generationId,
                controlRequestEpoch = pair.controlRequestEpoch,
                requestProvenanceStatus =
                    pair.requestProvenance?.associationStatus ?: "UNPROVEN",
                timestampSource = pair.timestampSource,
                timestampSourceLabel = timestampSourceLabel(pair.timestampSource),
                sensorTimestampComparableToElapsedRealtime =
                    pair.sensorTimestampComparableToElapsedRealtime,
                imageDeliveryLagMs =
                    lagMs(pair.timestamp, pair.imageArrivalElapsedNs),
                metadataDeliveryLagMs =
                    lagMs(pair.timestamp, pair.metadataArrivalElapsedNs),
                pairCompletionLagMs =
                    lagMs(pair.timestamp, pair.pairCompleteElapsedNs)
            )
        }
        return FrameRingBufferObservabilitySnapshot(
            pipelineGeneration = activeGeneration,
            timestampSourceCameraId = timestampSourceCameraId,
            timestampSource = timestampSource,
            timestampSourceLabel = timestampSourceLabel(timestampSource),
            sensorTimestampComparableToElapsedRealtime = comparable,
            actualImageReaderMaxImages = actualImageReaderMaxImages,
            acquiredImageCount = acquiredImageCount,
            completePairCount = completePairs.size,
            completedPairCountTotal = acceptedCompleteFrames,
            ringRetainedCount = retainedPairs.size,
            ringOverwriteCount = ringOverwriteCount,
            completeFrames = frames
        )
    }

    @Synchronized fun completeFrameCount(): Int = buffer.count {
        it.generationId == activeGeneration && hasExactRequestProvenance(it) &&
                it.timestamp != 0L && it.hardwareBuffer != null && it.metadata != null
    }

    @Synchronized fun incompleteFrameCount(): Int = buffer.count {
        it.generationId == activeGeneration && it.timestamp != 0L &&
                ((it.image != null || it.hardwareBuffer != null) xor (it.metadata != null))
    }

    @Synchronized fun leasedFrameCount(): Int = buffer.count { it.isLeased }

    @Synchronized fun writableSlotCount(): Int = buffer.count { !it.isLeased }

    @Synchronized fun acquiredImageSlotCount(): Int = buffer.count { it.hardwareBuffer != null || it.image != null }

    @Synchronized fun producerHeadroom(): Int =
        maxOf(0, actualImageReaderMaxImages - acquiredImageSlotCount())

    @Synchronized
    fun imageReaderPressureDiagnostics(): ImageReaderPressureDiagnostics {
        val residentSlots = acquiredImageSlotCount()
        val headroom = maxOf(0, actualImageReaderMaxImages - residentSlots)
        val leased = leasedFrameCount()
        val detected = imageReaderMaxImagesExhaustionCount > 0 ||
            droppedIncomingFrames > 0 ||
            (actualImageReaderMaxImages > 0 && headroom <= 1)
        return ImageReaderPressureDiagnostics(
            ringCapacity = capacity,
            imageReaderMaxImages = actualImageReaderMaxImages,
            ringResidentImageSlots = residentSlots,
            producerHeadroom = headroom,
            leasedFrames = leased,
            cumulativeImagesAcquired = acquiredImageCount,
            imageReaderAcquireFailureCount = imageReaderAcquireFailureCount,
            imageReaderMaxImagesExhaustionCount = imageReaderMaxImagesExhaustionCount,
            drainCallbackCount = imageReaderDrainCallbackCount,
            drainBatchHighWatermark = imageReaderDrainBatchHighWatermark,
            drainServiceMedianMs = medianMs(recentImageReaderDrainServiceNs),
            drainServiceMaxMs = if (imageReaderDrainServiceMaxNs > 0L) imageReaderDrainServiceMaxNs / 1_000_000.0 else null,
            imageArrivalCadenceMedianMs = medianMs(recentImageArrivalCadenceNs),
            imageMetadataPairSkewMedianMs = medianMs(recentImageMetadataPairSkewNs),
            estimatedFrameBytes = estimatedFrameBytes,
            estimatedRingResidentImageBytes = estimatedFrameBytes * residentSlots.toLong(),
            ringOverwriteCount = ringOverwriteCount,
            droppedIncomingFrames = droppedIncomingFrames,
            backpressureDetected = detected
        )
    }

    @Synchronized
    fun healthDiagnostics(userShutterTimestampNs: Long = 0L): FrameRingBufferHealthDiagnostics {
        val nowNs = currentElapsedRealtimeNanos()
        val completeList = buffer.filter {
            it.generationId == activeGeneration &&
                    hasExactRequestProvenance(it) &&
                    it.timestamp != 0L &&
                    it.hardwareBuffer != null &&
                    it.metadata != null
        }.sortedBy { try { it.metadata?.get(CaptureResult.SENSOR_TIMESTAMP) } catch (_: Throwable) { null } ?: it.timestamp }

        val leasedCount = leasedFrameCount()
        val writableCount = writableSlotCount()
        val pendingCount = incompleteFrameCount()
        val acquiredCount = acquiredImageSlotCount()
        val maxImg = actualImageReaderMaxImages
        val headroom = producerHeadroom()

        val oldestTs = completeList.firstOrNull()?.timestamp ?: 0L
        val newestTs = completeList.lastOrNull()?.timestamp ?: 0L
        val bufferSpanMs = if (completeList.size > 1 && newestTs > oldestTs) {
            (newestTs - oldestTs) / 1_000_000.0
        } else 0.0

        val refNs = if (userShutterTimestampNs > 0L) userShutterTimestampNs else nowNs
        val timestampComparable = isSensorTimestampComparableToElapsedRealtime(timestampSource)
        val frameAgeClockBasis: String
        val oldestAgeMs: Double?
        val newestAgeMs: Double?
        if (timestampComparable) {
            frameAgeClockBasis = "SENSOR_TIMESTAMP_REALTIME"
            oldestAgeMs = if (oldestTs > 0L) (refNs - oldestTs) / 1_000_000.0 else null
            newestAgeMs = if (newestTs > 0L) (refNs - newestTs) / 1_000_000.0 else null
        } else {
            frameAgeClockBasis = "PAIR_COMPLETION_ELAPSED_REALTIME"
            fun completionAgeMs(pair: ZslFramePair?): Double? {
                if (pair == null) return null
                val completionNs = pair.pairCompleteElapsedNs.takeIf { it > 0L }
                    ?: maxOf(pair.imageArrivalElapsedNs, pair.metadataArrivalElapsedNs)
                return if (completionNs > 0L) (refNs - completionNs) / 1_000_000.0 else null
            }
            oldestAgeMs = completionAgeMs(completeList.firstOrNull())
            newestAgeMs = completionAgeMs(completeList.lastOrNull())
        }

        val preShutterCandidates = if (userShutterTimestampNs > 0L) {
            queryCandidates(userShutterTimestampNs, maxCount = capacity)
        } else {
            completeList
        }

        val newestCompletionElapsedNs = completeList.lastOrNull()?.let { pair ->
            pair.pairCompleteElapsedNs.takeIf { it > 0L }
                ?: maxOf(pair.imageArrivalElapsedNs, pair.metadataArrivalElapsedNs)
        } ?: 0L
        val refillLatencyMs = if (timestampComparable && newestTs > 0L && nowNs > newestTs) {
            (nowNs - newestTs) / 1_000_000.0
        } else if (!timestampComparable && newestCompletionElapsedNs > 0L && nowNs > newestCompletionElapsedNs) {
            (nowNs - newestCompletionElapsedNs) / 1_000_000.0
        } else null

        val refillState = when {
            completeList.size >= capacity * 3 / 4 -> "WARM_REFILL_READY"
            completeList.size > 0 -> "WARM_REFILL_ACTIVE"
            else -> "WARM_REFILL_EMPTY"
        }

        val validCompleteCount = completeList.size
        val fullyPreShutterCount = preShutterCandidates.size
        val isReady = fullyPreShutterCount >= 1 || (userShutterTimestampNs == 0L && validCompleteCount >= 1)
        val stateLabel = when {
            validCompleteCount == 0 -> "BUFFER_COLD"
            validCompleteCount >= capacity -> "BUFFER_WARM"
            isReady -> "CAPTURE_READY"
            else -> "BUFFER_WARMING"
        }

        val timeToFirstMs = if (firstCompleteFrameArrivalNs > 0L && generationStartNs > 0L) {
            (firstCompleteFrameArrivalNs - generationStartNs) / 1_000_000.0
        } else null

        fun deltaMs(startNs: Long, endNs: Long): Double? =
            if (startNs > 0L && endNs >= startNs) (endNs - startNs) / 1_000_000.0 else null
        val generationToConfiguredMs = deltaMs(generationStartNs, sessionConfiguredElapsedNs)
        val configuredToImageMs = deltaMs(sessionConfiguredElapsedNs, firstImageArrivalNs)
        val configuredToMetadataMs = deltaMs(sessionConfiguredElapsedNs, firstMetadataArrivalNs)
        val configuredToCompleteMs = deltaMs(sessionConfiguredElapsedNs, firstCompleteFrameArrivalNs)
        val startupPairingState = when {
            firstCompleteFrameArrivalNs > 0L -> "COMPLETE_PAIR_READY"
            sessionConfiguredElapsedNs == 0L -> "WAITING_SESSION_CONFIGURED"
            firstImageArrivalNs == 0L && firstMetadataArrivalNs == 0L -> "WAITING_IMAGE_AND_METADATA"
            firstImageArrivalNs == 0L -> "WAITING_IMAGE"
            firstMetadataArrivalNs == 0L -> "WAITING_METADATA"
            else -> "IMAGE_METADATA_PRESENT_PAIR_NOT_COMPLETE"
        }

        val progress = (validCompleteCount.toDouble() / capacity.coerceAtLeast(1)).coerceIn(0.0, 1.0)

        return FrameRingBufferHealthDiagnostics(
            targetCapacity = capacity,
            completeFrames = completeList.size,
            leasedFrames = leasedCount,
            writableSlots = writableCount,
            pendingPairs = pendingCount,
            acquiredImageCount = acquiredCount,
            imageReaderMaxImages = maxImg,
            actualProducerHeadroom = headroom,
            evictions = ringOverwriteCount,
            droppedIncomingFrames = droppedIncomingFrames,
            leaseHighWatermark = leaseHighWatermark,
            bufferRefillLatencyMs = refillLatencyMs,
            bufferRefillState = refillState,
            bufferSpanMs = bufferSpanMs,
            oldestSensorAgeMs = oldestAgeMs,
            newestSensorAgeMs = newestAgeMs,
            frameAgeClockBasis = frameAgeClockBasis,
            pairingFailures = rejectedStale + rejectedGeneration + rejectedFormat + rejectedRequestProvenance + pairingFailuresCount,
            fullyPreShutterCandidateCount = fullyPreShutterCount,
            captureReady = isReady,
            bufferState = stateLabel,
            validCompleteFrameCount = validCompleteCount,
            timeToFirstCompleteFrameMs = timeToFirstMs,
            generationToSessionConfiguredMs = generationToConfiguredMs,
            sessionConfiguredToFirstImageMs = configuredToImageMs,
            sessionConfiguredToFirstMetadataMs = configuredToMetadataMs,
            sessionConfiguredToFirstCompleteFrameMs = configuredToCompleteMs,
            startupPairingState = startupPairingState,
            coldStartWaitMs = lastColdStartWaitMs,
            warmTargetProgress = progress,
            selectionFailureReason = lastSelectionFailureReason,
            leakedLeaseCount = buffer.count { it.isLeased },
            leasedFrameMutations = leasedFrameMutations,
            closedBorrowedFrames = 0,
            deferredCloseCount = deferredCloseCount,
            completedDeferredCloseCount = completedDeferredCloseCount
        ).also { diag ->
            com.bncam.core.runtime.RawPipelineRuntimeOwner.updateState { state ->
                state.copy(
                    bufferHealth = com.bncam.core.runtime.BufferHealthState(
                        logicalHistoryTarget = 35,
                        physicalResidentLimit = capacity,
                        minimumCaptureReadyFrames = 1,
                        maximumCaptureLeaseFrames = 20,
                        producerReserve = headroom,
                        completeFramesAvailable = diag.completeFrames,
                        isSingleFrameCaptureReady = diag.captureReady,
                        isMultiFrameCaptureReady = diag.completeFrames >= 3,
                        oldestFrameAgeMs = diag.oldestSensorAgeMs,
                        newestFrameAgeMs = diag.newestSensorAgeMs,
                        leakedLeaseCount = diag.leakedLeaseCount,
                        leasedFrameMutations = diag.leasedFrameMutations,
                        closedBorrowedFrames = diag.closedBorrowedFrames,
                        deferredCloseCount = diag.deferredCloseCount,
                        completedDeferredCloseCount = diag.completedDeferredCloseCount
                    )
                )
            }
        }
    }

    @Synchronized
    fun completeFrameFormats(): Set<Int> = buffer.asSequence()
        .filter {
            it.generationId == activeGeneration && hasExactRequestProvenance(it) &&
                    it.timestamp != 0L && it.image != null && it.hardwareBuffer != null && it.metadata != null
        }
        .map { it.format }
        .toSet()

    @Synchronized
    fun freshMetadataCompleteFrameCount(referenceTimestampNs: Long, freshnessWindowMs: Double): Int {
        return buffer.count { pair ->
            val metadata = pair.metadata
            val timestampNs = metadata?.get(CaptureResult.SENSOR_TIMESTAMP) ?: pair.timestamp
            val exposureNs = metadata?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
            val iso = metadata?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
                pair.generationId == activeGeneration &&
                hasExactRequestProvenance(pair) &&
                pair.image != null &&
                pair.hardwareBuffer != null &&
                metadata != null &&
                timestampNs > 0L &&
                exposureNs > 0L &&
                iso > 0 &&
                run {
                    // The readiness reference is elapsedRealtime. Compare SENSOR_TIMESTAMP only
                    // when that domain is explicitly compatible; otherwise use the completed-pair
                    // arrival marker, which is always captured in elapsedRealtime.
                    val freshnessTimestampNs = if (
                        pair.sensorTimestampComparableToElapsedRealtime && timestampNs > 0L
                    ) {
                        timestampNs
                    } else {
                        pair.pairCompleteElapsedNs.takeIf { it > 0L }
                            ?: maxOf(pair.imageArrivalElapsedNs, pair.metadataArrivalElapsedNs)
                    }
                    freshnessTimestampNs > 0L &&
                        kotlin.math.abs(referenceTimestampNs - freshnessTimestampNs) / 1_000_000.0 <=
                            freshnessWindowMs
                }
        }
    }

    @Synchronized
    fun resizeBuffer(newCapacity: Int) {
        if (this.capacity == newCapacity) return
        require(newCapacity > 0)
        val oldBuffer = buffer
        val retained = oldBuffer
            .filter {
                it.generationId == activeGeneration && hasExactRequestProvenance(it) &&
                        it.timestamp != 0L && it.image != null && it.hardwareBuffer != null && it.metadata != null
            }
            .sortedBy { it.timestamp }
            .takeLast(newCapacity)
        val retainedSet = retained.toSet()
        oldBuffer.filterNot { it in retainedSet }.forEach { pair ->
            if (pair.close()) {
                deferredCloseCount++
            }
        }

        this.capacity = newCapacity
        val newArray = Array(capacity) { ZslFramePair() }
        for (i in retained.indices) {
            newArray[i] = retained[i]
        }
        buffer = newArray
        head = if (retained.size < capacity) retained.size else 0
    }

    @Synchronized
    fun clear() {
        buffer.forEach { pair ->
            if (pair.close()) {
                deferredCloseCount++
            }
        }
        acceptedCompleteFrames = 0
        rejectedStale = 0
        rejectedGeneration = 0
        rejectedFormat = 0
        framesDroppedByRingBuffer = 0
        imageReaderAcquireFailureCount = 0
        acquiredImageCount = 0
        ringOverwriteCount = 0
        droppedIncomingFrames = 0
        actualImageReaderMaxImages = 0
        estimatedFrameBytes = 0L
        imageReaderMaxImagesExhaustionCount = 0
        imageReaderDrainCallbackCount = 0
        imageReaderDrainBatchHighWatermark = 0
        imageReaderDrainServiceMaxNs = 0L
        lastImageAcquiredElapsedNs = 0L
        timestampSourceCameraId = "unknown"
        timestampSource =
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN
        recentFrameDurationNs.clear()
        recentImageDeliveryLagNs.clear()
        recentMetadataDeliveryLagNs.clear()
        recentPairCompletionLagNs.clear()
        recentImageArrivalCadenceNs.clear()
        recentImageMetadataPairSkewNs.clear()
        recentImageReaderDrainServiceNs.clear()
        latestCompletedSensorTimestampNs = 0L
        head = 0
        publishRingEvent(
            type = FrameRingEventType.BUFFER_CLEARED,
            generationId = activeGeneration
        )
    }
}
