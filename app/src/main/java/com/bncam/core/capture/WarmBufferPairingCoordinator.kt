package com.bncam.core.capture

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.os.SystemClock
import android.util.Log
import com.bncam.core.quality.CfaArrangementDescriptor
import com.bncam.core.quality.ChannelNoiseModel
import com.bncam.core.quality.CaptureFrameRecord
import com.bncam.core.quality.NoiseModelRecord
import com.bncam.core.quality.RawColorTransformEngine
import com.bncam.core.runtime.RawPipelineRuntimeOwner
import com.bncam.core.runtime.TimestampPairingCalibration
import com.bncam.core.runtime.TimestampPairingMode
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

data class WarmBufferPairingMetrics(
    val totalImagesReceived: Long = 0L,
    val totalResultsReceived: Long = 0L,
    val totalPairsCompleted: Long = 0L,
    val totalOrphansExpired: Long = 0L,
    val totalIdentityMismatchRejections: Long = 0L,
    val averagePairingLatencyMs: Double = 0.0,
    val pairingMode: TimestampPairingMode = TimestampPairingMode.EXACT_MATCH,
    val pairingConfidence: Double = 1.0,
    val learnedOffsetNs: Long = 0L,
    val toleranceNs: Long = 5_000_000L,
    val ambiguousMatchCount: Long = 0L,
    val brokenCount: Long = 0L
)

/**
 * Dynamic, confidence-gated image ↔ metadata pairing coordinator with self-healing.
 * Does not assume Image.timestamp and CaptureResult SENSOR_TIMESTAMP match 1:1 on all hardware.
 */
class WarmBufferPairingCoordinator(
    private var orphanTimeoutMs: Long = 800L
) {
    private val tag = "PairingCoordinator"

    private val pendingImages = ConcurrentHashMap<Long, Pair<Image, Long>>() // timestamp -> (Image, arrivalElapsedNs)
    private val pendingResults = ConcurrentHashMap<Long, Pair<TotalCaptureResult, Long>>() // timestamp -> (Result, arrivalElapsedNs)

    private val imagesReceivedCounter = AtomicLong(0L)
    private val resultsReceivedCounter = AtomicLong(0L)
    private val pairsCompletedCounter = AtomicLong(0L)
    private val orphansExpiredCounter = AtomicLong(0L)
    private val mismatchesCounter = AtomicLong(0L)
    private val totalLatencyNsAccumulator = AtomicLong(0L)
    private val ambiguousMatchCounter = AtomicLong(0L)
    private val brokenCounter = AtomicLong(0L)

    // Dynamic calibration history
    private val calibrationDeltas = ArrayDeque<Long>()
    private val pairingLatenciesMs = ArrayDeque<Double>()

    @Volatile private var pairingMode = TimestampPairingMode.EXACT_MATCH
    @Volatile private var pairingConfidence = 1.0
    @Volatile private var learnedOffsetNs = 0L
    @Volatile private var toleranceNs = 5_000_000L // 5ms default bound
    @Volatile private var isSelfHealingActive = false

    private companion object {
        const val CALIBRATION_SAMPLE_LIMIT = 15
        const val LATENCY_HISTORY_LIMIT = 50
        const val MAX_TOLERANCE_NS = 12_000_000L // Never exceed 12ms to prevent neighboring frame ambiguity (at 30-60fps)
    }

    fun setOrphanTimeoutMs(timeoutMs: Long) {
        orphanTimeoutMs = timeoutMs.coerceAtLeast(100L)
    }

    fun resetCalibration() {
        synchronized(this) {
            calibrationDeltas.clear()
            pairingLatenciesMs.clear()
            pairingMode = TimestampPairingMode.EXACT_MATCH
            pairingConfidence = 1.0
            learnedOffsetNs = 0L
            toleranceNs = 5_000_000L
            isSelfHealingActive = false
        }
        pendingImages.values.forEach { (img, _) -> runCatching { img.close() } }
        pendingImages.clear()
        pendingResults.clear()
        updateRuntimeOwnerState()
    }

    fun onImageAvailable(
        image: Image,
        logicalCameraId: String,
        lensId: String,
        stableLensKey: String,
        characteristics: CameraCharacteristics
    ): CaptureFrameRecord? {
        val totalImages = imagesReceivedCounter.incrementAndGet()
        val imageTs = image.timestamp
        val arrivalElapsed = SystemClock.elapsedRealtimeNanos()

        checkSelfHealing()

        val matchedResultPair = findAndRemoveMatchingResult(imageTs)
        if (matchedResultPair != null) {
            val result = matchedResultPair.first
            val resultArrivalElapsed = matchedResultPair.second
            val resultTs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: imageTs

            recordCalibrationSample(imageTs, resultTs)
            val pairingLatencyMs = (arrivalElapsed - resultArrivalElapsed).coerceAtLeast(0L) / 1_000_000.0
            recordLatencySample(pairingLatencyMs)

            return createRecordIfValid(
                image = image,
                result = result,
                logicalCameraId = logicalCameraId,
                lensId = lensId,
                stableLensKey = stableLensKey,
                characteristics = characteristics,
                pairingLatencyMs = pairingLatencyMs
            )
        } else {
            pendingImages[imageTs] = image to arrivalElapsed
            cleanExpiredOrphans()
            updateRuntimeOwnerState()
            return null
        }
    }

    fun onCaptureResultAvailable(
        result: TotalCaptureResult,
        logicalCameraId: String,
        lensId: String,
        stableLensKey: String,
        characteristics: CameraCharacteristics
    ): CaptureFrameRecord? {
        val totalResults = resultsReceivedCounter.incrementAndGet()
        val sensorTs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return null
        val arrivalElapsed = SystemClock.elapsedRealtimeNanos()

        checkSelfHealing()

        val matchedImagePair = findAndRemoveMatchingImage(sensorTs)
        if (matchedImagePair != null) {
            val image = matchedImagePair.first
            val imageArrivalElapsed = matchedImagePair.second
            val imageTs = image.timestamp

            recordCalibrationSample(imageTs, sensorTs)
            val pairingLatencyMs = (arrivalElapsed - imageArrivalElapsed).coerceAtLeast(0L) / 1_000_000.0
            recordLatencySample(pairingLatencyMs)

            return createRecordIfValid(
                image = image,
                result = result,
                logicalCameraId = logicalCameraId,
                lensId = lensId,
                stableLensKey = stableLensKey,
                characteristics = characteristics,
                pairingLatencyMs = pairingLatencyMs
            )
        } else {
            pendingResults[sensorTs] = result to arrivalElapsed
            cleanExpiredOrphans()
            updateRuntimeOwnerState()
            return null
        }
    }

    private fun findAndRemoveMatchingResult(imageTs: Long): Pair<TotalCaptureResult, Long>? {
        // 1. Exact match attempt
        val exact = pendingResults.remove(imageTs)
        if (exact != null) return exact

        if (pairingMode != TimestampPairingMode.DYNAMIC_OFFSET || pairingConfidence < 0.8) {
            return null
        }

        // 2. Confidence-gated learned offset matching
        val targetSensorTs = imageTs - learnedOffsetNs
        var bestKey: Long? = null
        var bestDiff = Long.MAX_VALUE
        var matchCount = 0

        for (candidateTs in pendingResults.keys) {
            val diff = abs(candidateTs - targetSensorTs)
            if (diff <= toleranceNs) {
                matchCount++
                if (diff < bestDiff) {
                    bestDiff = diff
                    bestKey = candidateTs
                }
            }
        }

        if (matchCount > 1) {
            ambiguousMatchCounter.incrementAndGet()
        }

        return if (bestKey != null) pendingResults.remove(bestKey) else null
    }

    private fun findAndRemoveMatchingImage(sensorTs: Long): Pair<Image, Long>? {
        // 1. Exact match attempt
        val exact = pendingImages.remove(sensorTs)
        if (exact != null) return exact

        if (pairingMode != TimestampPairingMode.DYNAMIC_OFFSET || pairingConfidence < 0.8) {
            return null
        }

        // 2. Confidence-gated learned offset matching
        val targetImageTs = sensorTs + learnedOffsetNs
        var bestKey: Long? = null
        var bestDiff = Long.MAX_VALUE
        var matchCount = 0

        for (candidateTs in pendingImages.keys) {
            val diff = abs(candidateTs - targetImageTs)
            if (diff <= toleranceNs) {
                matchCount++
                if (diff < bestDiff) {
                    bestDiff = diff
                    bestKey = candidateTs
                }
            }
        }

        if (matchCount > 1) {
            ambiguousMatchCounter.incrementAndGet()
        }

        return if (bestKey != null) pendingImages.remove(bestKey) else null
    }

    private fun recordCalibrationSample(imageTs: Long, sensorTs: Long) {
        val delta = imageTs - sensorTs
        synchronized(this) {
            if (calibrationDeltas.size >= CALIBRATION_SAMPLE_LIMIT) {
                calibrationDeltas.removeFirst()
            }
            calibrationDeltas.addLast(delta)

            if (calibrationDeltas.size >= 5) {
                val deltas = calibrationDeltas.toLongArray().sortedArray()
                val medianDelta = deltas[deltas.size / 2]

                var variance = 0.0
                for (d in deltas) {
                    variance += (d - medianDelta) * (d - medianDelta)
                }
                val stdDevNs = Math.sqrt(variance / deltas.size)
                val stdDevMs = stdDevNs / 1_000_000.0

                if (abs(medianDelta) < 1_000_000L && stdDevMs < 1.0) {
                    // Exact match confirmed
                    pairingMode = TimestampPairingMode.EXACT_MATCH
                    learnedOffsetNs = 0L
                    pairingConfidence = 1.0
                    toleranceNs = 5_000_000L
                } else if (stdDevMs < 3.0) {
                    // Stable non-zero offset learned with low jitter
                    pairingMode = TimestampPairingMode.DYNAMIC_OFFSET
                    learnedOffsetNs = medianDelta
                    pairingConfidence = (1.0 - (stdDevMs / 3.0)).coerceIn(0.7, 0.99)
                    toleranceNs = (stdDevNs.toLong() * 3).coerceIn(2_000_000L, MAX_TOLERANCE_NS)
                } else {
                    pairingConfidence = 0.5
                }
            }
        }
    }

    private fun recordLatencySample(latencyMs: Double) {
        synchronized(this) {
            if (pairingLatenciesMs.size >= LATENCY_HISTORY_LIMIT) {
                pairingLatenciesMs.removeFirst()
            }
            pairingLatenciesMs.addLast(latencyMs)
        }
    }

    private fun checkSelfHealing() {
        val imagesReceived = imagesReceivedCounter.get()
        val resultsReceived = resultsReceivedCounter.get()
        val pairsCompleted = pairsCompletedCounter.get()

        if (imagesReceived > 5 && resultsReceived > 5 && pairsCompleted == 0L && !isSelfHealingActive) {
            isSelfHealingActive = true
            brokenCounter.incrementAndGet()
            Log.w(tag, "PAIRING_BROKEN detected: received $imagesReceived images, $resultsReceived metadata, but 0 pairs completed. Executing self-healing recovery.")
            
            // Purge unmatched halves and reset calibration window
            pendingImages.values.forEach { (img, _) -> runCatching { img.close() } }
            pendingImages.clear()
            pendingResults.clear()
            
            synchronized(this) {
                calibrationDeltas.clear()
                pairingMode = TimestampPairingMode.EXACT_MATCH
                pairingConfidence = 0.5
                learnedOffsetNs = 0L
            }
            isSelfHealingActive = false
        }
    }

    private fun updateRuntimeOwnerState() {
        val completed = pairsCompletedCounter.get()
        val latencies = synchronized(this) { pairingLatenciesMs.toDoubleArray().sortedArray() }

        val p50 = if (latencies.isNotEmpty()) latencies[(latencies.size * 0.50).toInt().coerceIn(0, latencies.size - 1)] else 0.0
        val p95 = if (latencies.isNotEmpty()) latencies[(latencies.size * 0.95).toInt().coerceIn(0, latencies.size - 1)] else 0.0
        val p99 = if (latencies.isNotEmpty()) latencies[(latencies.size * 0.99).toInt().coerceIn(0, latencies.size - 1)] else 0.0

        val calibration = TimestampPairingCalibration(
            pairingMode = pairingMode,
            pairingConfidence = pairingConfidence,
            learnedOffsetNs = learnedOffsetNs,
            toleranceNs = toleranceNs,
            ambiguousMatchCount = ambiguousMatchCounter.get(),
            pairingLatencyP50Ms = p50,
            pairingLatencyP95Ms = p95,
            pairingLatencyP99Ms = p99,
            isSelfHealingActive = isSelfHealingActive,
            brokenCount = brokenCounter.get()
        )

        RawPipelineRuntimeOwner.updateState { state ->
            state.copy(timestampPairing = calibration)
        }
    }

    private fun createRecordIfValid(
        image: Image,
        result: TotalCaptureResult,
        logicalCameraId: String,
        lensId: String,
        stableLensKey: String,
        characteristics: CameraCharacteristics,
        pairingLatencyMs: Double
    ): CaptureFrameRecord? {
        val physicalCameraId = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)

        val cfaEnum = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            ?: CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB
        val cfaDescriptor = CfaArrangementDescriptor.from(cfaEnum)

        val noisePairArray = result.get(CaptureResult.SENSOR_NOISE_PROFILE)
        val noiseRecord = buildNoiseRecord(noisePairArray, cfaDescriptor)

        val rawBlacks = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
        val blackLevels = rawBlacks ?: floatArrayOf(64f, 64f, 64f, 64f)

        val whiteLevel = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
        val expNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 10_000_000L

        val neutralRationals = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
        val neutralDoubles = neutralRationals?.map { it.toDouble() }?.toDoubleArray()

        pairsCompletedCounter.incrementAndGet()
        totalLatencyNsAccumulator.addAndGet((pairingLatencyMs * 1_000_000.0).toLong())
        updateRuntimeOwnerState()

        return CaptureFrameRecord(
            frameTimestampNs = image.timestamp,
            logicalCameraId = logicalCameraId,
            physicalCameraId = physicalCameraId,
            resolvedLensId = lensId,
            stableLensKey = stableLensKey,
            sensorSensitivityIso = iso,
            exposureTimeNs = expNs,
            cfaArrangement = cfaDescriptor,
            noiseModel = noiseRecord,
            frameBlackLevels = blackLevels,
            frameBlackLevelSource = if (rawBlacks != null) "CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL" else "Format Fallback",
            frameWhiteLevel = whiteLevel,
            frameWhiteLevelSource = "CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL",
            colorCorrectionGains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { floatArrayOf(it.red, it.greenEven, it.greenOdd, it.blue) },
            colorCorrectionTransform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { RawColorTransformEngine.colorSpaceTransformToArray(it) },
            neutralColorPoint = neutralDoubles,
            image = image
        )
    }

    private fun buildNoiseRecord(noisePairs: Array<android.util.Pair<Double, Double>>?, cfaDescriptor: CfaArrangementDescriptor): NoiseModelRecord {
        if (noisePairs == null || noisePairs.isEmpty()) {
            return NoiseModelRecord(
                channels = emptyList(),
                source = "CalibratedFallback",
                isAuto = false
            )
        }
        val channels = noisePairs.mapIndexed { idx, pair ->
            ChannelNoiseModel(
                cfaChannelIndex = idx,
                channelName = when (idx) {
                    0 -> "R"
                    1 -> "Gr"
                    2 -> "Gb"
                    3 -> "B"
                    else -> "Ch_$idx"
                },
                slopeS = pair.first.coerceAtLeast(0.0),
                offsetO = pair.second.coerceAtLeast(0.0)
            )
        }
        return NoiseModelRecord(
            channels = channels,
            source = "CaptureResult.SENSOR_NOISE_PROFILE",
            isAuto = true
        )
    }

    private fun cleanExpiredOrphans() {
        val nowElapsedNs = SystemClock.elapsedRealtimeNanos()
        val timeoutNs = orphanTimeoutMs * 1_000_000L

        val expiredImageTimestamps = pendingImages.filter { (_, pair) ->
            nowElapsedNs - pair.second > timeoutNs
        }.keys

        for (ts in expiredImageTimestamps) {
            val removed = pendingImages.remove(ts)
            if (removed != null) {
                try {
                    removed.first.close()
                } catch (t: Throwable) {
                    Log.w(tag, "Failed to close expired orphan image for timestamp $ts", t)
                }
                orphansExpiredCounter.incrementAndGet()
            }
        }

        val expiredResultTimestamps = pendingResults.filter { (_, pair) ->
            nowElapsedNs - pair.second > timeoutNs
        }.keys

        for (ts in expiredResultTimestamps) {
            pendingResults.remove(ts)
            orphansExpiredCounter.incrementAndGet()
        }
    }

    fun metrics(): WarmBufferPairingMetrics {
        val completed = pairsCompletedCounter.get()
        val totalLatNs = totalLatencyNsAccumulator.get()
        val avgLatencyMs = if (completed > 0) (totalLatNs / completed.toDouble()) / 1_000_000.0 else 0.0
        return WarmBufferPairingMetrics(
            totalImagesReceived = imagesReceivedCounter.get(),
            totalResultsReceived = resultsReceivedCounter.get(),
            totalPairsCompleted = completed,
            totalOrphansExpired = orphansExpiredCounter.get(),
            totalIdentityMismatchRejections = mismatchesCounter.get(),
            averagePairingLatencyMs = avgLatencyMs,
            pairingMode = pairingMode,
            pairingConfidence = pairingConfidence,
            learnedOffsetNs = learnedOffsetNs,
            toleranceNs = toleranceNs,
            ambiguousMatchCount = ambiguousMatchCounter.get(),
            brokenCount = brokenCounter.get()
        )
    }


}
