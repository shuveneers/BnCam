package com.bncam.core.capture

import kotlin.math.ceil

/**
 * Timing policy for the guaranteed shutter anchor used by the continuously repeating Near-ZSL
 * producer. It never authorizes a dedicated post-shutter still request.
 */
data class NearZslAnchorAdmissionBudget(
    val preShutterPairingGraceMs: Long,
    val firstValidRepeatingFrameWaitMs: Long,
    val maximumTransportAgeMs: Double,
    val maximumDegradedPreShutterAgeMs: Double
)

data class NearZslEffectiveShutter(
    val timestampNs: Long,
    val domain: String
)

object NearZslAnchorAdmissionPolicy {
    private const val DEFAULT_FRAME_DURATION_MS = 50.0
    private const val DEFAULT_PAIR_COMPLETION_LAG_MS = 80.0

    fun resolveBudget(
        frameDurationMedianMs: Double?,
        pairCompletionLagMedianMs: Double?,
        coldStartAtUserShutter: Boolean
    ): NearZslAnchorAdmissionBudget {
        val frameDurationMs = frameDurationMedianMs
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?: DEFAULT_FRAME_DURATION_MS
        val pairLagMs = pairCompletionLagMedianMs
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?: DEFAULT_PAIR_COMPLETION_LAG_MS
        val preShutterGraceMs = if (coldStartAtUserShutter) {
            0L
        } else {
            ceil(pairLagMs + 25.0).toLong().coerceIn(70L, 180L)
        }
        val firstValidWaitMs =
            ceil(frameDurationMs + pairLagMs + 180.0).toLong().coerceIn(300L, 900L)
        val transportAgeMs =
            (frameDurationMs * 2.5 + pairLagMs + 80.0).coerceIn(180.0, 450.0)
        val degradedPreShutterAgeMs =
            (frameDurationMs * 3.0 + pairLagMs + 40.0).coerceIn(180.0, 320.0)
        return NearZslAnchorAdmissionBudget(
            preShutterPairingGraceMs = preShutterGraceMs,
            firstValidRepeatingFrameWaitMs = firstValidWaitMs,
            maximumTransportAgeMs = transportAgeMs,
            maximumDegradedPreShutterAgeMs = degradedPreShutterAgeMs
        )
    }

    fun isGenuinePreShutterAge(physicalAgeAtUserShutterMs: Double?): Boolean =
        physicalAgeAtUserShutterMs != null &&
            physicalAgeAtUserShutterMs.isFinite() &&
            physicalAgeAtUserShutterMs >= 0.0 &&
            physicalAgeAtUserShutterMs <= ShutterCandidateFreshnessPolicy.GENUINE_NEAR_ZSL_WINDOW_MS

    fun isUsableDegradedPreShutterAge(
        physicalAgeAtUserShutterMs: Double?,
        maximumAgeMs: Double
    ): Boolean =
        physicalAgeAtUserShutterMs != null &&
            physicalAgeAtUserShutterMs.isFinite() &&
            physicalAgeAtUserShutterMs >= 0.0 &&
            maximumAgeMs.isFinite() &&
            maximumAgeMs >= ShutterCandidateFreshnessPolicy.GENUINE_NEAR_ZSL_WINDOW_MS &&
            physicalAgeAtUserShutterMs <= maximumAgeMs

    fun isAdmissibleFirstValidPhysicalAge(
        physicalAgeAtUserShutterMs: Double?,
        maximumDegradedPreShutterAgeMs: Double
    ): Boolean {
        if (physicalAgeAtUserShutterMs == null || !physicalAgeAtUserShutterMs.isFinite()) {
            return false
        }
        // Negative age means the rolling exposure physically ended after the user's press. That is
        // valid only for the explicitly degraded repeating-frame fallback. Positive age means the
        // frame was already fully exposed before the press and must still obey the bounded
        // pre-shutter reliability tier; a newly completed but physically ancient pair may never
        // masquerade as a fresh fallback merely because metadata arrived late.
        return physicalAgeAtUserShutterMs < 0.0 ||
            isUsableDegradedPreShutterAge(
                physicalAgeAtUserShutterMs,
                maximumDegradedPreShutterAgeMs
            )
    }

    fun isFreshFirstValidCompletion(
        pairCompleteElapsedNs: Long,
        userShutterTimestampNs: Long,
        nowElapsedNs: Long,
        maximumTransportAgeMs: Double
    ): Boolean {
        if (pairCompleteElapsedNs <= 0L || userShutterTimestampNs <= 0L || nowElapsedNs <= 0L) {
            return false
        }
        val transportAgeMs =
            (nowElapsedNs - pairCompleteElapsedNs).coerceAtLeast(0L) / 1_000_000.0
        return pairCompleteElapsedNs >= userShutterTimestampNs &&
            transportAgeMs <= maximumTransportAgeMs
    }

    fun effectiveShutterForFirstValidRepeatingFrame(
        userShutterTimestampNs: Long,
        fullExposureEndNs: Long?,
        pairCompleteElapsedNs: Long,
        sensorTimestampComparableToElapsedRealtime: Boolean
    ): NearZslEffectiveShutter {
        return if (sensorTimestampComparableToElapsedRealtime &&
            fullExposureEndNs != null && fullExposureEndNs > 0L
        ) {
            NearZslEffectiveShutter(
                timestampNs = maxOf(userShutterTimestampNs, fullExposureEndNs),
                domain = "ELAPSED_REALTIME_FIRST_VALID_REPEATING_FRAME"
            )
        } else {
            NearZslEffectiveShutter(
                timestampNs = maxOf(userShutterTimestampNs, pairCompleteElapsedNs),
                domain = "ELAPSED_REALTIME_FIRST_VALID_REPEATING_FRAME_CLOCK_SAFE"
            )
        }
    }
}
