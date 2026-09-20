package com.bncam.core.capture

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class SensorExposureEvidenceDomain { RAW, YUV }

data class SensorExposureEvidence(
    val domain: SensorExposureEvidenceDomain,
    val pipelineGeneration: Int,
    val sensorTimestampNs: Long,
    val observedElapsedRealtimeNs: Long,
    val sampleCount: Int,
    val p50: Float,
    val p90: Float,
    val p95: Float,
    val p99: Float,
    val nearClipFraction: Float,
    val saturatedFraction: Float,
    val exposureTimeNs: Long,
    val sensitivityIso: Int,
    val predictedNoiseSigma: Float,
    val signalToNoiseRatio: Float,
    val source: String
) {
    val exposureProduct: Double
        get() = exposureTimeNs.toDouble() * sensitivityIso.toDouble()

    fun compactSummary(): String =
        "domain=$domain;generation=$pipelineGeneration;source=$source;samples=$sampleCount;" +
            "p50=$p50;p90=$p90;p95=$p95;p99=$p99;nearClip=$nearClipFraction;" +
            "saturated=$saturatedFraction;exposureNs=$exposureTimeNs;iso=$sensitivityIso;" +
            "noiseSigma=$predictedNoiseSigma;snr=$signalToNoiseRatio"
}


/** Converts one fresh live-analysis sample into immutable acquisition evidence. */
object SensorExposureObserver {
    fun observe(
        statistics: ExposureStatistics,
        pipelineGeneration: Int,
        observedElapsedRealtimeNs: Long
    ): SensorExposureEvidence? {
        val exposureNs = statistics.captureExposureTimeNs?.takeIf { it > 0L } ?: return null
        val iso = statistics.captureSensitivityIso?.takeIf { it > 0 } ?: return null
        val domain = if (statistics.rawNearClipFraction != null ||
            statistics.source.contains("RAW", ignoreCase = true)
        ) SensorExposureEvidenceDomain.RAW else SensorExposureEvidenceDomain.YUV
        val nearClip = when (domain) {
            SensorExposureEvidenceDomain.RAW -> statistics.rawNearClipFraction ?: statistics.maximumDisplayClipFraction
            SensorExposureEvidenceDomain.YUV -> statistics.maximumDisplayClipFraction
        }.coerceIn(0f, 1f)
        val saturated = when (domain) {
            SensorExposureEvidenceDomain.RAW -> statistics.rawSaturatedFraction ?: nearClip
            SensorExposureEvidenceDomain.YUV -> statistics.maximumDisplayClipFraction
        }.coerceIn(0f, 1f)
        return SensorExposureEvidence(
            domain = domain,
            pipelineGeneration = pipelineGeneration,
            sensorTimestampNs = statistics.sensorTimestampNs ?: 0L,
            observedElapsedRealtimeNs = observedElapsedRealtimeNs,
            sampleCount = statistics.sampleCount,
            p50 = statistics.exposureControllerLuma(0.50f),
            p90 = statistics.exposureControllerLuma(0.90f),
            p95 = statistics.exposureControllerLuma(0.95f),
            p99 = statistics.exposureControllerLuma(0.99f),
            nearClipFraction = nearClip,
            saturatedFraction = saturated,
            exposureTimeNs = exposureNs,
            sensitivityIso = iso,
            predictedNoiseSigma = statistics.predictedNoiseSigma ?: 0f,
            signalToNoiseRatio = statistics.signalToNoiseRatio ?: 0f,
            source = statistics.source
        )
    }
}

data class SensorExposurePlan(
    val enabled: Boolean,
    val pipelineGeneration: Int,
    val evidenceSensorTimestampNs: Long,
    val requestedShiftEv: Float,
    val appliedShiftEv: Float,
    val clippingHeadroomEv: Float,
    val maxAllowedShiftEv: Float,
    val confidence: Float,
    val evidence: SensorExposureEvidence?,
    val reason: String
) {
    val recommendedShiftEv: Float get() = requestedShiftEv

    fun summary(): String =
        "sensorExposureAuthority=$enabled;generation=$pipelineGeneration;recommendedShiftEv=$recommendedShiftEv;requestedShiftEv=$requestedShiftEv;" +
            "appliedShiftEv=$appliedShiftEv;clippingHeadroomEv=$clippingHeadroomEv;" +
            "maxAllowedShiftEv=$maxAllowedShiftEv;confidence=$confidence;reason=$reason;" +
            (evidence?.compactSummary() ?: "evidence=unavailable")
}

data class SensorExposureAllocation(
    val exposureTimeNs: Long,
    val sensitivityIso: Int,
    val frameDurationNs: Long,
    val requestedExposureProduct: Double,
    val achievedExposureProduct: Double,
    val limitingConstraint: String,
    val priorityMode: CaptureExposurePriorityMode
) {
    fun summary(): String =
        "resolvedExposureNs=$exposureTimeNs;resolvedIso=$sensitivityIso;frameDurationNs=$frameDurationNs;" +
            "requestedProduct=$requestedExposureProduct;achievedProduct=$achievedExposureProduct;" +
            "priority=${priorityMode.persistedValue};limit=$limitingConstraint"
}

/**
 * Scene-evidence sensor exposure policy. This is acquisition exposure, not display/development EV.
 * It resolves a residual EV correction from the currently realized sensor frame and therefore does
 * not accumulate a fixed bias frame after frame.
 */
object SensorExposurePolicy {
    const val MAX_RAW_POSITIVE_SHIFT_EV = 1.5f
    const val MAX_YUV_POSITIVE_SHIFT_EV = 1.0f
    const val MAX_SAFE_SATURATED_FRACTION = 0.0005f // 0.05 %
    private const val TARGET_P95 = 0.92f
    private const val TARGET_P99 = 0.985f
    private const val MIN_CONFIDENCE = 0.45f

    fun resolve(
        enabled: Boolean,
        evidence: SensorExposureEvidence?,
        additionalBiasEv: Float = 0f,
        deviceMotionHigh: Boolean = false,
        nowElapsedRealtimeNs: Long
    ): SensorExposurePlan {
        if (!enabled) return disabled(evidence?.pipelineGeneration ?: -1, "standard_strategy_selected")
        if (evidence == null) return disabled(-1, "missing_sensor_exposure_evidence")
        if (evidence.sampleCount < 256 || evidence.exposureTimeNs <= 0L || evidence.sensitivityIso <= 0) {
            return disabled(evidence.pipelineGeneration, "incomplete_sensor_exposure_evidence", evidence)
        }
        val ageNs = nowElapsedRealtimeNs - evidence.observedElapsedRealtimeNs
        if (ageNs < 0L || ageNs > 900_000_000L) {
            return disabled(evidence.pipelineGeneration, "stale_sensor_exposure_evidence", evidence)
        }
        @Suppress("UNUSED_VARIABLE") val motionIsAllocatorConstraint = deviceMotionHigh
        val p50 = evidence.p50.sane01()
        val p95 = evidence.p95.sane01()
        val p99 = evidence.p99.sane01()
        if (p95 <= 0.001f || p99 <= 0.001f) {
            return disabled(evidence.pipelineGeneration, "invalid_histogram_percentiles", evidence)
        }

        val maxPositive = maxPositiveShift(evidence.domain)
        val percentileRequest = log2((TARGET_P95 / max(0.004f, p95)).toDouble()).toFloat()
        val p99Headroom = log2((TARGET_P99 / max(0.004f, p99)).toDouble()).toFloat()
        val midtoneAssist = if (p50 < 0.08f && p95 < 0.70f) {
            (0.30f * log2((0.16f / max(0.004f, p50)).toDouble()).toFloat()).coerceIn(0f, 0.55f)
        } else 0f
        var requested = max(percentileRequest, midtoneAssist) + additionalBiasEv.finiteOrZero().coerceIn(-2f, 2f)

        val nearClip = evidence.nearClipFraction.finite01()
        val saturated = evidence.saturatedFraction.finite01()
        var clippingHeadroom = p99Headroom
        if (nearClip > 0.0005f) {
            val nearClipPenalty = (0.08f + 0.32f * log2(1.0 + nearClip / 0.0005).toFloat())
                .coerceIn(0.08f, 1.2f)
            clippingHeadroom = min(clippingHeadroom, -nearClipPenalty)
        }
        if (saturated > MAX_SAFE_SATURATED_FRACTION) {
            val saturationPenalty = (0.18f + 0.42f * log2(1.0 + saturated / MAX_SAFE_SATURATED_FRACTION).toFloat())
                .coerceIn(0.18f, 1.8f)
            clippingHeadroom = min(clippingHeadroom, -saturationPenalty)
        }

        val highlightLimited = min(requested, clippingHeadroom + 0.06f)
        requested = requested.coerceIn(-2.0f, maxPositive)
        val applied = highlightLimited.coerceIn(-2.0f, maxPositive)

        val sampleConfidence = ((evidence.sampleCount - 256).toFloat() / 2048f).coerceIn(0f, 1f)
        val snrConfidence = when {
            evidence.signalToNoiseRatio <= 0f -> 0.65f
            evidence.signalToNoiseRatio >= 12f -> 1f
            else -> (0.55f + evidence.signalToNoiseRatio / 12f * 0.45f).coerceIn(0.55f, 1f)
        }
        val domainConfidence = if (evidence.domain == SensorExposureEvidenceDomain.RAW) 1f else 0.72f
        val confidence = (0.30f + 0.45f * sampleConfidence + 0.25f * snrConfidence) * domainConfidence
        if (confidence < MIN_CONFIDENCE) {
            return disabled(evidence.pipelineGeneration, "low_sensor_exposure_confidence", evidence)
        }

        val reason = when {
            saturated > MAX_SAFE_SATURATED_FRACTION -> "sensor_saturation_protection"
            nearClip > 0.0005f -> "near_clip_protection"
            applied > 0.04f -> "scene_headroom_positive_shift"
            applied < -0.04f -> "highlight_headroom_negative_shift"
            else -> "sensor_exposure_hold"
        }
        return SensorExposurePlan(
            enabled = true,
            pipelineGeneration = evidence.pipelineGeneration,
            evidenceSensorTimestampNs = evidence.sensorTimestampNs,
            requestedShiftEv = requested,
            appliedShiftEv = applied,
            clippingHeadroomEv = clippingHeadroom,
            maxAllowedShiftEv = maxPositive,
            confidence = confidence.coerceIn(0f, 1f),
            evidence = evidence,
            reason = reason
        )
    }

    private fun maxPositiveShift(domain: SensorExposureEvidenceDomain): Float =
        if (domain == SensorExposureEvidenceDomain.RAW) MAX_RAW_POSITIVE_SHIFT_EV else MAX_YUV_POSITIVE_SHIFT_EV

    private fun disabled(
        generation: Int,
        reason: String,
        evidence: SensorExposureEvidence? = null
    ) = SensorExposurePlan(
        enabled = false,
        pipelineGeneration = generation,
        evidenceSensorTimestampNs = evidence?.sensorTimestampNs ?: 0L,
        requestedShiftEv = 0f,
        appliedShiftEv = 0f,
        clippingHeadroomEv = 0f,
        maxAllowedShiftEv = evidence?.let { maxPositiveShift(it.domain) } ?: 0f,
        confidence = 0f,
        evidence = evidence,
        reason = reason
    )
}

/** Converts the residual EV plan into an actual sensor shutter/ISO request. */
object SensorExposureAllocator {
    fun allocate(
        plan: SensorExposurePlan,
        bounds: ExposureBounds,
        preferences: CaptureExposurePreferences,
        maxExposureTimeNs: Long,
        minimumFrameDurationNs: Long = bounds.minExposureNs
    ): SensorExposureAllocation? {
        val evidence = plan.evidence ?: return null
        if (!plan.enabled || plan.confidence <= 0f || evidence.exposureProduct <= 0.0) return null
        val prefs = preferences.sanitized()
        val effectiveMaxExposure = min(
            bounds.maxExposureNs,
            min(maxExposureTimeNs.coerceAtLeast(bounds.minExposureNs), prefs.maxFrameExposure.resolveNs(bounds.maxExposureNs))
        ).coerceAtLeast(bounds.minExposureNs)
        val cappedBounds = bounds.copy(maxExposureNs = effectiveMaxExposure)
        val requestedProduct = evidence.exposureProduct * 2.0.pow(plan.appliedShiftEv.toDouble())
        val choice = prefs.shotBiasExposure

        val fixedIso = when {
            choice.useSensorMaxIso -> cappedBounds.maxIso
            choice.fixedIso != null -> choice.fixedIso.coerceIn(cappedBounds.minIso, cappedBounds.maxIso)
            else -> null
        }
        if (fixedIso != null) {
            val requestedExposure = (requestedProduct / fixedIso.toDouble()).roundToLong()
            val exposure = requestedExposure.coerceIn(cappedBounds.minExposureNs, cappedBounds.maxExposureNs)
            return result(
                exposure,
                fixedIso,
                requestedProduct,
                cappedBounds,
                minimumFrameDurationNs,
                CaptureExposurePriorityMode.ISO_PRIORITY,
                if (exposure != requestedExposure) "EXPOSURE_LIMIT" else "NONE"
            )
        }

        val fixedExposure = when {
            choice.useEffectiveMaxTime -> cappedBounds.maxExposureNs
            choice.fixedExposureNs != null -> choice.fixedExposureNs.coerceIn(cappedBounds.minExposureNs, cappedBounds.maxExposureNs)
            else -> null
        }
        if (fixedExposure != null) {
            val requestedIso = (requestedProduct / fixedExposure.toDouble()).roundToInt()
            val iso = requestedIso.coerceIn(cappedBounds.minIso, cappedBounds.maxIso)
            return result(
                fixedExposure,
                iso,
                requestedProduct,
                cappedBounds,
                minimumFrameDurationNs,
                CaptureExposurePriorityMode.SHUTTER_PRIORITY,
                if (iso != requestedIso) "ISO_LIMIT" else "NONE"
            )
        }

        // Balanced ETTR: shutter owns the first part of the residual EV while it remains motion /
        // cadence safe; ISO absorbs the rest only after that ceiling is reached.
        val preferredExposure = (evidence.exposureTimeNs.toDouble() * 2.0.pow(plan.appliedShiftEv.toDouble())).roundToLong()
        val exposure = preferredExposure.coerceIn(cappedBounds.minExposureNs, cappedBounds.maxExposureNs)
        val requestedIso = (requestedProduct / exposure.toDouble()).roundToInt()
        val iso = requestedIso.coerceIn(cappedBounds.minIso, cappedBounds.maxIso)
        val limit = when {
            exposure != preferredExposure -> "MOTION_CADENCE_OR_EXPOSURE_LIMIT"
            iso != requestedIso -> "ISO_LIMIT"
            else -> "NONE"
        }
        return result(
            exposure,
            iso,
            requestedProduct,
            cappedBounds,
            minimumFrameDurationNs,
            CaptureExposurePriorityMode.BALANCED,
            limit
        )
    }

    private fun result(
        exposure: Long,
        iso: Int,
        requestedProduct: Double,
        bounds: ExposureBounds,
        minimumFrameDurationNs: Long,
        mode: CaptureExposurePriorityMode,
        limit: String
    ): SensorExposureAllocation {
        val safeExposure = exposure.coerceIn(bounds.minExposureNs, bounds.maxExposureNs)
        val safeIso = iso.coerceIn(bounds.minIso, bounds.maxIso)
        return SensorExposureAllocation(
            exposureTimeNs = safeExposure,
            sensitivityIso = safeIso,
            frameDurationNs = max(safeExposure, minimumFrameDurationNs.coerceAtLeast(bounds.minExposureNs)),
            requestedExposureProduct = requestedProduct,
            achievedExposureProduct = safeExposure.toDouble() * safeIso.toDouble(),
            limitingConstraint = limit,
            priorityMode = mode
        )
    }
}

fun sensorExposureAppliedEv(
    baseExposureNs: Long,
    baseIso: Int,
    actualExposureNs: Long,
    actualIso: Int
): Float? {
    if (baseExposureNs <= 0L || baseIso <= 0 || actualExposureNs <= 0L || actualIso <= 0) return null
    val base = baseExposureNs.toDouble() * baseIso.toDouble()
    val actual = actualExposureNs.toDouble() * actualIso.toDouble()
    if (base <= 0.0 || actual <= 0.0) return null
    return log2(actual / base).toFloat()
}

private fun Float.sane01(): Float = if (isFinite()) coerceIn(0f, 1f) else 0f
private fun Float.finite01(): Float = if (isFinite()) coerceIn(0f, 1f) else 0f
private fun Float.finiteOrZero(): Float = if (isFinite()) this else 0f
