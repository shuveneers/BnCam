@file:Suppress("SpellCheckingInspection")

package com.bncam.core.capture

import android.hardware.camera2.CaptureResult
import com.bncam.core.buffer.ZslFramePair
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Capture stability gate for BnCam warm-buffer RAW/YUV routes.
 *
 * This file implements the architectural principle: payload first, deterministic
 * candidate selection, base-frame truth, and explicit rejection of unstable input.
 */
data class FrameExposureKey(
    val timestampNs: Long,
    val exposureTimeNs: Long,
    val sensorSensitivityIso: Int,
    val postRawSensitivityBoost: Int,
    val computedTetMs: Double,
    val aeState: Int,
    val aeLock: Boolean,
    val awbState: Int,
    val afState: Int,
    val lensId: String,
    val physicalCameraId: String?,
    val profileId: String,
    val captureMode: String,
    val meteringMode: String,
    val controlRequestEpoch: Long
) {
    val aeStateLabel: String get() = labelAeState(aeState)
    val awbStateLabel: String get() = labelAwbState(awbState)
    val afStateLabel: String get() = labelAfState(afState)
}

data class ZslCandidateDecision(
    val index: Int,
    val timestampNs: Long,
    val ageMs: Double,
    val tetMs: Double,
    val exposureTimeNs: Long,
    val iso: Int,
    val postRawBoost: Int,
    val aeState: Int,
    val awbState: Int,
    val afState: Int,
    val generationMatches: Boolean,
    val metadataMatchesImage: Boolean,
    val exposureDeltaFromBase: Double,
    val sharpnessScore: Double,
    val motionScore: Double,
    val saturationPct: Double,
    val blackClipPct: Double,
    val syncScore: Double,
    val exposureScore: Double,
    val overallScore: Double,
    val accepted: Boolean,
    val selectedBase: Boolean,
    val rejectReason: String
)

data class CaptureStabilityReport(
    val captureStabilityVersion: String,
    val capturePath: String,
    val ringGenerationId: Int,
    val currentControlRequestEpoch: Long,
    val shutterTimestampNs: Long,
    val candidateCount: Int,
    val acceptedCandidateCount: Int,
    val rejectedCandidateCount: Int,
    val baseFrameIndex: Int,
    val baseFrameTimestampNs: Long,
    val baseFrameAgeMs: Double,
    val baseFrameTet: Double,
    val baseFrameExposureTimeNs: Long,
    val baseFrameIso: Int,
    val baseFramePostRawBoost: Int,
    val baseFrameAeState: Int,
    val baseFrameAwbState: Int,
    val baseFrameAfState: Int,
    val baseFrameScore: Double,
    val ringSize: Int,
    val ringCapacity: Int,
    val oldestFrameAgeMs: Double,
    val newestFrameAgeMs: Double,
    val ringTetMin: Double,
    val ringTetMax: Double,
    val ringTetRatio: Double,
    val ringGenerationMixDetected: Boolean,
    val staleFrameCount: Int,
    val metadataMissingCount: Int,
    val timestampMismatchCount: Int,
    val fallbackReason: String,
    val candidates: List<ZslCandidateDecision>
) {
    fun toDebugText(): String = buildString {
        appendLine("BNCAM CAPTURE STABILITY DEBUG")
        appendLine()
        appendLine("SUMMARY")
        kv("captureStabilityVersion", captureStabilityVersion)
        kv("capturePath", capturePath)
        kv("ringGenerationId", ringGenerationId)
        kv("currentControlRequestEpoch", currentControlRequestEpoch)
        kv("shutterTimestampNs", shutterTimestampNs)
        kv("candidateCount", candidateCount)
        kv("acceptedCandidateCount", acceptedCandidateCount)
        kv("rejectedCandidateCount", rejectedCandidateCount)
        kv("baseFrameIndex", baseFrameIndex)
        kv("baseFrameTimestampNs", baseFrameTimestampNs)
        kv("baseFrameAgeMs", fmt(baseFrameAgeMs))
        kv("baseFrameTet", fmt(baseFrameTet))
        kv("baseFrameExposureTimeNs", baseFrameExposureTimeNs)
        kv("baseFrameIso", baseFrameIso)
        kv("baseFramePostRawBoost", baseFramePostRawBoost)
        kv("baseFrameAeState", "${labelAeState(baseFrameAeState)} ($baseFrameAeState)")
        kv("baseFrameAwbState", "${labelAwbState(baseFrameAwbState)} ($baseFrameAwbState)")
        kv("baseFrameAfState", "${labelAfState(baseFrameAfState)} ($baseFrameAfState)")
        kv("baseFrameScore", fmt(baseFrameScore))
        kv("fallbackReason", fallbackReason)
        appendLine()
        appendLine("RINGBUFFER")
        kv("ringSize", ringSize)
        kv("ringCapacity", ringCapacity)
        kv("oldestFrameAgeMs", fmt(oldestFrameAgeMs))
        kv("newestFrameAgeMs", fmt(newestFrameAgeMs))
        kv("ringTetMin", fmt(ringTetMin))
        kv("ringTetMax", fmt(ringTetMax))
        kv("ringTetRatio", fmt(ringTetRatio))
        kv("ringGenerationMixDetected", ringGenerationMixDetected)
        kv("staleFrameCount", staleFrameCount)
        kv("metadataMissingCount", metadataMissingCount)
        kv("timestampMismatchCount", timestampMismatchCount)
        appendLine()
        appendLine("CANDIDATES")
        candidates.forEach { c ->
            appendLine("#${c.index} ${if (c.accepted) "ACCEPTED" else "REJECTED"}${if (c.selectedBase) " / BASE" else ""}")
            kv("timestampNs", c.timestampNs)
            kv("ageMs", fmt(c.ageMs))
            kv("tet", fmt(c.tetMs))
            kv("exposureTimeNs", c.exposureTimeNs)
            kv("iso", c.iso)
            kv("postRawBoost", c.postRawBoost)
            kv("aeState", "${labelAeState(c.aeState)} (${c.aeState})")
            kv("awbState", "${labelAwbState(c.awbState)} (${c.awbState})")
            kv("afState", "${labelAfState(c.afState)} (${c.afState})")
            kv("generationMatches", c.generationMatches)
            kv("metadataMatchesImage", c.metadataMatchesImage)
            kv("exposureDeltaFromBase", fmt(c.exposureDeltaFromBase))
            kv("sharpnessScore", fmt(c.sharpnessScore))
            kv("motionScore", fmt(c.motionScore))
            kv("saturationPct", fmt(c.saturationPct))
            kv("blackClipPct", fmt(c.blackClipPct))
            kv("syncScore", fmt(c.syncScore))
            kv("exposureScore", fmt(c.exposureScore))
            kv("overallScore", fmt(c.overallScore))
            kv("rejectReason", c.rejectReason)
            appendLine()
        }
    }

    private fun StringBuilder.kv(key: String, value: Any?) {
        appendLine("  $key: $value")
    }
}

data class StableZslSelection(
    val selectedFrames: List<ZslFramePair>,
    val framesToClose: List<ZslFramePair>,
    val baseFrame: ZslFramePair?,
    val report: CaptureStabilityReport,
    val fallbackRecommended: Boolean
)

object CaptureStabilityDefaults {
    const val VERSION = "payload_capture_stability_v1"
    const val MAX_FRAME_AGE_BEFORE_SHUTTER_MS = 500.0
    const val MAX_FRAME_AGE_AFTER_SHUTTER_MS = 120.0
    const val MAX_WALLCLOCK_STALE_MS = 2000.0
    const val MAX_TIMESTAMP_MISMATCH_MS = 2.0
    const val RAW_SINGLE_MAX_TET_RATIO_BALANCED = 1.12
    const val RAW_MULTI_MAX_TET_RATIO_BALANCED = 1.08
}

object StableZslFrameSelector {
    fun selectSingleFrame(
        candidates: List<ZslFramePair>,
        shutterTimestampNs: Long,
        activeGenerationId: Int,
        currentControlRequestEpoch: Long,
        lensId: String,
        profileId: String,
        captureMode: String,
        meteringMode: String,
        ringCapacity: Int,
        maxTetRatio: Double = CaptureStabilityDefaults.RAW_SINGLE_MAX_TET_RATIO_BALANCED,
        requireAeStableWhenKnown: Boolean = true,
        capturePathHint: String = "stable_zsl"
    ): StableZslSelection {
        return select(
            candidates = candidates,
            shutterTimestampNs = shutterTimestampNs,
            activeGenerationId = activeGenerationId,
            currentControlRequestEpoch = currentControlRequestEpoch,
            lensId = lensId,
            profileId = profileId,
            captureMode = captureMode,
            meteringMode = meteringMode,
            ringCapacity = ringCapacity,
            desiredFrameCount = 1,
            maxTetRatio = maxTetRatio,
            requireAeStableWhenKnown = requireAeStableWhenKnown,
            capturePathHint = capturePathHint
        )
    }

    fun selectMultiFrame(
        candidates: List<ZslFramePair>,
        shutterTimestampNs: Long,
        activeGenerationId: Int,
        currentControlRequestEpoch: Long,
        lensId: String,
        profileId: String,
        captureMode: String,
        meteringMode: String,
        ringCapacity: Int,
        desiredFrameCount: Int,
        maxTetRatio: Double = CaptureStabilityDefaults.RAW_MULTI_MAX_TET_RATIO_BALANCED,
        requireAeStableWhenKnown: Boolean = false,
        capturePathHint: String = "stable_zsl"
    ): StableZslSelection {
        return select(
            candidates = candidates,
            shutterTimestampNs = shutterTimestampNs,
            activeGenerationId = activeGenerationId,
            currentControlRequestEpoch = currentControlRequestEpoch,
            lensId = lensId,
            profileId = profileId,
            captureMode = captureMode,
            meteringMode = meteringMode,
            ringCapacity = ringCapacity,
            desiredFrameCount = desiredFrameCount.coerceAtLeast(1),
            maxTetRatio = maxTetRatio,
            requireAeStableWhenKnown = requireAeStableWhenKnown,
            capturePathHint = capturePathHint
        )
    }

    private fun select(
        candidates: List<ZslFramePair>,
        shutterTimestampNs: Long,
        activeGenerationId: Int,
        currentControlRequestEpoch: Long,
        lensId: String,
        profileId: String,
        captureMode: String,
        meteringMode: String,
        ringCapacity: Int,
        desiredFrameCount: Int,
        maxTetRatio: Double,
        requireAeStableWhenKnown: Boolean,
        capturePathHint: String
    ): StableZslSelection {
        val nowNs = android.os.SystemClock.elapsedRealtimeNanos()
        val provisional = candidates.mapIndexed { index, frame ->
            val meta = frame.metadata
            val metaTs = meta?.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L
            val pairTs = frame.timestamp
            val effectiveTs = if (metaTs > 0L) metaTs else pairTs
            val ageToShutterMs = if (effectiveTs > 0L) (effectiveTs - shutterTimestampNs) / 1_000_000.0 else Double.NaN
            val wallAgeMs = if (effectiveTs > 0L) (nowNs - effectiveTs) / 1_000_000.0 else Double.POSITIVE_INFINITY
            val timestampMismatchMs = if (metaTs > 0L && pairTs > 0L) abs(metaTs - pairTs) / 1_000_000.0 else Double.POSITIVE_INFINITY
            val expNs = meta?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
            val iso = meta?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
            val postBoost = try { meta?.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST) ?: 100 } catch (_: Throwable) { 100 }
            val tet = computeTetMs(expNs, iso, postBoost)
            val aeState = meta?.get(CaptureResult.CONTROL_AE_STATE) ?: -1
            val awbState = meta?.get(CaptureResult.CONTROL_AWB_STATE) ?: -1
            val afState = meta?.get(CaptureResult.CONTROL_AF_STATE) ?: -1
            val generationMatches = frame.generationId == activeGenerationId
            val provenance = frame.requestProvenance
            val requestProvenanceExact = provenance != null &&
                    frame.controlRequestEpoch > 0L &&
                    provenance.identity.pipelineGeneration == frame.generationId &&
                    provenance.identity.controlRequestEpoch == frame.controlRequestEpoch &&
                    provenance.snapshot.identity == provenance.identity
            val metadataMatchesImage = timestampMismatchMs <= CaptureStabilityDefaults.MAX_TIMESTAMP_MISMATCH_MS
            val aeKnownAndUnstable = aeState >= 0 && !isAeUsable(aeState)
            val inWindow = !ageToShutterMs.isNaN() &&
                    ageToShutterMs >= -CaptureStabilityDefaults.MAX_FRAME_AGE_BEFORE_SHUTTER_MS &&
                    ageToShutterMs <= CaptureStabilityDefaults.MAX_FRAME_AGE_AFTER_SHUTTER_MS
            val validTet = tet > 0.0
            val stale = wallAgeMs >= CaptureStabilityDefaults.MAX_WALLCLOCK_STALE_MS

            val hardRejectReason = when {
                meta == null -> "metadata_missing"
                frame.hardwareBuffer == null -> "hardware_buffer_missing"
                effectiveTs <= 0L -> "timestamp_missing"
                !generationMatches -> "wrong_ring_generation"
                !requestProvenanceExact -> "unproven_control_request_identity"
                !metadataMatchesImage -> "metadata_image_timestamp_mismatch"
                !inWindow -> "outside_shutter_window"
                stale -> "wallclock_stale"
                !validTet -> "invalid_tet"
                requireAeStableWhenKnown && aeKnownAndUnstable -> "ae_not_stable_${labelAeState(aeState)}"
                else -> ""
            }

            val syncScore = if (ageToShutterMs.isNaN()) 0.0 else max(0.0, 1.0 - abs(ageToShutterMs) / 220.0)
            val motionScore = if (expNs > 0L) max(0.05, 1.0 - ((expNs / 1_000_000.0) / 80.0)) else 0.40
            val aeScore = when {
                aeState < 0 -> 0.72
                isAeUsable(aeState) -> 1.0
                else -> 0.15
            }
            val generationScore = if (generationMatches && requestProvenanceExact) 1.0 else 0.0
            val metadataScore = if (metadataMatchesImage) 1.0 else 0.0
            val overall = (syncScore * 0.42 + motionScore * 0.18 + aeScore * 0.22 + generationScore * 0.12 + metadataScore * 0.06).coerceIn(0.0, 1.0)

            TempDecision(
                frame = frame,
                index = index,
                timestampNs = effectiveTs,
                ageMs = ageToShutterMs,
                wallAgeMs = wallAgeMs,
                tetMs = tet,
                exposureTimeNs = expNs,
                iso = iso,
                postRawBoost = postBoost,
                aeState = aeState,
                awbState = awbState,
                afState = afState,
                generationMatches = generationMatches,
                metadataMatchesImage = metadataMatchesImage,
                hardRejectReason = hardRejectReason,
                syncScore = syncScore,
                motionScore = motionScore,
                overallScore = overall
            )
        }

        val validPool = provisional.filter { it.hardRejectReason.isBlank() }
        val base = validPool.maxByOrNull { it.overallScore }
        val baseTet = base?.tetMs ?: 0.0
        val selectedUnordered = if (base == null) {
            emptyList()
        } else {
            val exposureFiltered = validPool
                .map { temp ->
                    val exposureDelta = if (baseTet > 0.0 && temp.tetMs > 0.0) {
                        max(temp.tetMs, baseTet) / max(0.000001, min(temp.tetMs, baseTet))
                    } else {
                        Double.POSITIVE_INFINITY
                    }
                    temp to exposureDelta
                }
                .filter { (_, ratio) -> ratio <= maxTetRatio || desiredFrameCount == 1 }
                .sortedWith(compareByDescending<Pair<TempDecision, Double>> { it.first === base }.thenByDescending { it.first.overallScore })
                .take(desiredFrameCount)
                .map { it.first }

            if (exposureFiltered.any { it === base }) exposureFiltered else listOf(base)
        }

        // Native RAW mergers treat the last HardwareBuffer as the anchor/base frame.
        // Keep support frames chronological and append the selected base at the end.
        val selected = if (base == null) {
            emptyList()
        } else {
            selectedUnordered.filter { it !== base }.sortedBy { it.timestampNs } + base
        }

        val selectedSet = selected.map { it.frame }.toSet()
        val finalDecisions = provisional.map { temp ->
            val exposureDelta = if (baseTet > 0.0 && temp.tetMs > 0.0) {
                max(temp.tetMs, baseTet) / max(0.000001, min(temp.tetMs, baseTet))
            } else {
                Double.POSITIVE_INFINITY
            }
            val exposureScore = if (exposureDelta.isFinite()) max(0.0, 1.0 - ((exposureDelta - 1.0) / max(0.01, maxTetRatio - 1.0))) else 0.0
            val accepted = temp.frame in selectedSet
            val selectedBase = base?.frame === temp.frame
            val reason = when {
                accepted -> if (selectedBase) "ACCEPTED_BASE" else "ACCEPTED_SUPPORT_STRICT_TET_MATCH"
                temp.hardRejectReason.isNotBlank() -> temp.hardRejectReason
                exposureDelta > maxTetRatio -> "tet_ratio_outside_limit_${fmt(exposureDelta)}"
                else -> "lower_stability_score"
            }

            ZslCandidateDecision(
                index = temp.index,
                timestampNs = temp.timestampNs,
                ageMs = temp.ageMs,
                tetMs = temp.tetMs,
                exposureTimeNs = temp.exposureTimeNs,
                iso = temp.iso,
                postRawBoost = temp.postRawBoost,
                aeState = temp.aeState,
                awbState = temp.awbState,
                afState = temp.afState,
                generationMatches = temp.generationMatches,
                metadataMatchesImage = temp.metadataMatchesImage,
                exposureDeltaFromBase = exposureDelta,
                // This metadata-only selector has no pixel sharpness measurement.
                // Report unavailable instead of falsely duplicating motion.
                sharpnessScore = -1.0,
                motionScore = temp.motionScore,
                saturationPct = -1.0,
                blackClipPct = -1.0,
                syncScore = temp.syncScore,
                exposureScore = exposureScore,
                overallScore = temp.overallScore,
                accepted = accepted,
                selectedBase = selectedBase,
                rejectReason = reason
            )
        }

        val tetValues = provisional.mapNotNull { it.tetMs.takeIf { tet -> tet > 0.0 } }
        val timestamps = provisional.mapNotNull { it.timestampNs.takeIf { ts -> ts > 0L } }
        val ages = timestamps.map { (it - shutterTimestampNs) / 1_000_000.0 }
        val ringTetMin = tetValues.minOrNull() ?: 0.0
        val ringTetMax = tetValues.maxOrNull() ?: 0.0
        val ringTetRatio = if (ringTetMin > 0.0) ringTetMax / ringTetMin else 0.0
        val acceptedCount = finalDecisions.count { it.accepted }
        val rejectedCount = finalDecisions.size - acceptedCount
        val fallbackReason = when {
            base == null -> "no_stable_base_frame"
            acceptedCount < desiredFrameCount -> "not_enough_stable_support_frames"
            ringTetRatio > maxTetRatio && desiredFrameCount > 1 -> "ring_tet_ratio_high_${fmt(ringTetRatio)}"
            else -> "none"
        }

        val report = CaptureStabilityReport(
            captureStabilityVersion = CaptureStabilityDefaults.VERSION,
            capturePath = if (fallbackReason == "none") capturePathHint else "unstable_zsl_rejected",
            ringGenerationId = activeGenerationId,
            currentControlRequestEpoch = currentControlRequestEpoch,
            shutterTimestampNs = shutterTimestampNs,
            candidateCount = candidates.size,
            acceptedCandidateCount = acceptedCount,
            rejectedCandidateCount = rejectedCount,
            baseFrameIndex = base?.index ?: -1,
            baseFrameTimestampNs = base?.timestampNs ?: 0L,
            baseFrameAgeMs = base?.ageMs ?: 0.0,
            baseFrameTet = base?.tetMs ?: 0.0,
            baseFrameExposureTimeNs = base?.exposureTimeNs ?: 0L,
            baseFrameIso = base?.iso ?: 0,
            baseFramePostRawBoost = base?.postRawBoost ?: 100,
            baseFrameAeState = base?.aeState ?: -1,
            baseFrameAwbState = base?.awbState ?: -1,
            baseFrameAfState = base?.afState ?: -1,
            baseFrameScore = base?.overallScore ?: 0.0,
            ringSize = candidates.size,
            ringCapacity = ringCapacity,
            oldestFrameAgeMs = ages.minOrNull() ?: 0.0,
            newestFrameAgeMs = ages.maxOrNull() ?: 0.0,
            ringTetMin = ringTetMin,
            ringTetMax = ringTetMax,
            ringTetRatio = ringTetRatio,
            ringGenerationMixDetected = provisional.any { !it.generationMatches },
            staleFrameCount = provisional.count { it.wallAgeMs >= CaptureStabilityDefaults.MAX_WALLCLOCK_STALE_MS },
            metadataMissingCount = candidates.count { it.metadata == null },
            timestampMismatchCount = provisional.count { !it.metadataMatchesImage },
            fallbackReason = fallbackReason,
            candidates = finalDecisions
        )

        return StableZslSelection(
            selectedFrames = selected.map { it.frame },
            framesToClose = candidates.filter { it !in selectedSet },
            baseFrame = base?.frame,
            report = report,
            fallbackRecommended = fallbackReason != "none"
        )
    }

    private data class TempDecision(
        val frame: ZslFramePair,
        val index: Int,
        val timestampNs: Long,
        val ageMs: Double,
        val wallAgeMs: Double,
        val tetMs: Double,
        val exposureTimeNs: Long,
        val iso: Int,
        val postRawBoost: Int,
        val aeState: Int,
        val awbState: Int,
        val afState: Int,
        val generationMatches: Boolean,
        val metadataMatchesImage: Boolean,
        val hardRejectReason: String,
        val syncScore: Double,
        val motionScore: Double,
        val overallScore: Double
    )
}

fun computeTetMs(exposureTimeNs: Long, iso: Int, postRawBoost: Int = 100): Double {
    if (exposureTimeNs <= 0L || iso <= 0) return 0.0
    val exposureMs = exposureTimeNs / 1_000_000.0
    val isoGain = iso / 100.0
    val postGain = (postRawBoost.coerceAtLeast(1)) / 100.0
    return exposureMs * isoGain * postGain
}

fun isAeUsable(aeState: Int): Boolean {
    return aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
            aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
            aeState == CaptureResult.CONTROL_AE_STATE_LOCKED ||
            aeState == -1
}

fun labelAeState(state: Int): String = when (state) {
    CaptureResult.CONTROL_AE_STATE_INACTIVE -> "INACTIVE"
    CaptureResult.CONTROL_AE_STATE_SEARCHING -> "SEARCHING"
    CaptureResult.CONTROL_AE_STATE_CONVERGED -> "CONVERGED"
    CaptureResult.CONTROL_AE_STATE_LOCKED -> "LOCKED"
    CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED -> "FLASH_REQUIRED"
    CaptureResult.CONTROL_AE_STATE_PRECAPTURE -> "PRECAPTURE"
    -1 -> "UNKNOWN"
    else -> "AE_$state"
}

fun labelAwbState(state: Int): String = when (state) {
    CaptureResult.CONTROL_AWB_STATE_INACTIVE -> "INACTIVE"
    CaptureResult.CONTROL_AWB_STATE_SEARCHING -> "SEARCHING"
    CaptureResult.CONTROL_AWB_STATE_CONVERGED -> "CONVERGED"
    CaptureResult.CONTROL_AWB_STATE_LOCKED -> "LOCKED"
    -1 -> "UNKNOWN"
    else -> "AWB_$state"
}

fun labelAfState(state: Int): String = when (state) {
    CaptureResult.CONTROL_AF_STATE_INACTIVE -> "INACTIVE"
    CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN -> "PASSIVE_SCAN"
    CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> "PASSIVE_FOCUSED"
    CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN -> "ACTIVE_SCAN"
    CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> "FOCUSED_LOCKED"
    CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> "NOT_FOCUSED_LOCKED"
    CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> "PASSIVE_UNFOCUSED"
    -1 -> "UNKNOWN"
    else -> "AF_$state"
}

private fun fmt(value: Double): String = when {
    value.isNaN() -> "nan"
    value.isInfinite() -> "inf"
    else -> String.format(Locale.US, "%.4f", value)
}
