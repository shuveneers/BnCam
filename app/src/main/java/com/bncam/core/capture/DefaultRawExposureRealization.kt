package com.bncam.core.capture

import kotlin.math.abs
import kotlin.math.max

enum class DefaultRawExposureRoute {
    API36_EXPOSURE_TIME_PRIORITY,
    MANUAL_FALLBACK,
    AE_BOOTSTRAP
}

data class DefaultRawExposureRealizationTruth(
    val route: DefaultRawExposureRoute,
    val pipelineGeneration: Int,
    val controlRequestEpoch: Long,
    val requestedAeMode: Int?,
    val resultAeMode: Int?,
    val requestedPriorityMode: Int?,
    val resultPriorityMode: Int?,
    val requestedExposureNs: Long?,
    val actualExposureNs: Long?,
    val requestedIso: Int?,
    val actualIso: Int?,
    val actualFrameDurationNs: Long?,
    val exposureRatio: Double?,
    val isoRatio: Double?,
    val status: String
) {
    fun summary(): String = buildString {
        append("route=$route")
        append(";generation=$pipelineGeneration")
        append(";controlRequestEpoch=$controlRequestEpoch")
        append(";status=$status")
        append(";requestedAeMode=${requestedAeMode ?: "unavailable"}")
        append(";resultAeMode=${resultAeMode ?: "unavailable"}")
        append(";requestedPriorityMode=${requestedPriorityMode ?: "unavailable"}")
        append(";resultPriorityMode=${resultPriorityMode ?: "unavailable"}")
        append(";requestedExposureNs=${requestedExposureNs ?: "unavailable"}")
        append(";actualExposureNs=${actualExposureNs ?: "unavailable"}")
        append(";exposureRatio=${exposureRatio ?: "unavailable"}")
        append(";requestedIso=${requestedIso ?: "unavailable"}")
        append(";actualIso=${actualIso ?: "unavailable"}")
        append(";isoRatio=${isoRatio ?: "unavailable"}")
        append(";actualFrameDurationNs=${actualFrameDurationNs ?: "unavailable"}")
    }
}

object DefaultRawExposureRealizationEvaluator {
    private const val API36_SHORT_TOLERANCE_FRACTION = 0.05
    private const val API36_LONG_TOLERANCE_FRACTION = 0.05
    private const val MANUAL_TOLERANCE_FRACTION = 0.02
    private const val MIN_EXPOSURE_TOLERANCE_NS = 100_000L
    private const val MIN_ISO_TOLERANCE = 1

    fun evaluate(
        route: DefaultRawExposureRoute,
        pipelineGeneration: Int,
        controlRequestEpoch: Long,
        requestedAeMode: Int?,
        resultAeMode: Int?,
        requestedPriorityMode: Int?,
        resultPriorityMode: Int?,
        requestedExposureNs: Long?,
        actualExposureNs: Long?,
        requestedIso: Int?,
        actualIso: Int?,
        actualFrameDurationNs: Long?
    ): DefaultRawExposureRealizationTruth {
        val exposureRatio = ratio(actualExposureNs, requestedExposureNs)
        val isoRatio = ratio(actualIso, requestedIso)
        val status = when (route) {
            DefaultRawExposureRoute.AE_BOOTSTRAP -> when {
                actualExposureNs == null || actualExposureNs <= 0L || actualIso == null || actualIso <= 0 ->
                    "AE_BOOTSTRAP_INVALID_RESULT"
                else -> "AE_BOOTSTRAP_OBSERVED"
            }
            DefaultRawExposureRoute.API36_EXPOSURE_TIME_PRIORITY -> evaluateApi36(
                requestedPriorityMode,
                resultPriorityMode,
                requestedExposureNs,
                actualExposureNs
            )
            DefaultRawExposureRoute.MANUAL_FALLBACK -> evaluateManual(
                requestedAeMode,
                resultAeMode,
                requestedExposureNs,
                actualExposureNs,
                requestedIso,
                actualIso
            )
        }
        return DefaultRawExposureRealizationTruth(
            route = route,
            pipelineGeneration = pipelineGeneration,
            controlRequestEpoch = controlRequestEpoch,
            requestedAeMode = requestedAeMode,
            resultAeMode = resultAeMode,
            requestedPriorityMode = requestedPriorityMode,
            resultPriorityMode = resultPriorityMode,
            requestedExposureNs = requestedExposureNs,
            actualExposureNs = actualExposureNs,
            requestedIso = requestedIso,
            actualIso = actualIso,
            actualFrameDurationNs = actualFrameDurationNs,
            exposureRatio = exposureRatio,
            isoRatio = isoRatio,
            status = status
        )
    }

    private fun evaluateApi36(
        requestedPriorityMode: Int?,
        resultPriorityMode: Int?,
        requestedExposureNs: Long?,
        actualExposureNs: Long?
    ): String {
        if (requestedExposureNs == null || requestedExposureNs <= 0L) return "API36_INVALID_REQUEST"
        if (actualExposureNs == null || actualExposureNs <= 0L) return "API36_INVALID_RESULT"
        if (requestedPriorityMode != null && resultPriorityMode != null && requestedPriorityMode != resultPriorityMode) {
            return "API36_PRIORITY_MODE_MISMATCH"
        }
        val lower = lowerBound(requestedExposureNs, API36_SHORT_TOLERANCE_FRACTION)
        val upper = upperBound(requestedExposureNs, API36_LONG_TOLERANCE_FRACTION)
        return when {
            actualExposureNs < lower -> "API36_EXPOSURE_NOT_REALIZED_SHORT"
            actualExposureNs > upper -> "API36_EXPOSURE_NOT_REALIZED_LONG"
            else -> "API36_EXPOSURE_REALIZED"
        }
    }

    private fun evaluateManual(
        requestedAeMode: Int?,
        resultAeMode: Int?,
        requestedExposureNs: Long?,
        actualExposureNs: Long?,
        requestedIso: Int?,
        actualIso: Int?
    ): String {
        if (requestedExposureNs == null || requestedExposureNs <= 0L || requestedIso == null || requestedIso <= 0) {
            return "MANUAL_INVALID_REQUEST"
        }
        if (actualExposureNs == null || actualExposureNs <= 0L || actualIso == null || actualIso <= 0) {
            return "MANUAL_INVALID_RESULT"
        }
        if (requestedAeMode != null && resultAeMode != null && requestedAeMode != resultAeMode) {
            return "MANUAL_AE_MODE_MISMATCH"
        }
        val exposureTolerance = max(MIN_EXPOSURE_TOLERANCE_NS, (requestedExposureNs * MANUAL_TOLERANCE_FRACTION).toLong())
        if (abs(actualExposureNs - requestedExposureNs) > exposureTolerance) {
            return "MANUAL_EXPOSURE_NOT_REALIZED"
        }
        val isoTolerance = max(MIN_ISO_TOLERANCE, (requestedIso * MANUAL_TOLERANCE_FRACTION).toInt())
        if (abs(actualIso - requestedIso) > isoTolerance) {
            return "MANUAL_ISO_NOT_REALIZED"
        }
        return "MANUAL_EXPOSURE_REALIZED"
    }

    private fun lowerBound(value: Long, fraction: Double): Long {
        val tolerance = max(MIN_EXPOSURE_TOLERANCE_NS, (value * fraction).toLong())
        return (value - tolerance).coerceAtLeast(1L)
    }

    private fun upperBound(value: Long, fraction: Double): Long {
        val tolerance = max(MIN_EXPOSURE_TOLERANCE_NS, (value * fraction).toLong())
        return if (Long.MAX_VALUE - value < tolerance) Long.MAX_VALUE else value + tolerance
    }

    private fun ratio(actual: Long?, requested: Long?): Double? =
        if (actual != null && actual > 0L && requested != null && requested > 0L) actual.toDouble() / requested.toDouble() else null

    private fun ratio(actual: Int?, requested: Int?): Double? =
        if (actual != null && actual > 0 && requested != null && requested > 0) actual.toDouble() / requested.toDouble() else null
}
