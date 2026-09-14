package com.bncam.core.capture

/**
 * Capture-candidate role policy for an already-valid warm-buffer frame.
 *
 * Viewfinder publication and live metering freshness deliberately are not inputs here. Identity,
 * format, exposure-selection constraints and shutter-relative age are validated by the warm-buffer
 * owner before evidence reaches this policy. This layer only prevents a clearly transitional frame
 * from winning merely because it is a few milliseconds newer than another valid pre-shutter frame.
 *
 * The policy is deliberately non-blocking: if every available frame is transitional, the newest
 * one still wins. Frame quality therefore changes candidate preference, never shutter admission.
 */
enum class ZslCaptureCandidateState {
    USABLE,
    TRANSITIONING
}

data class ZslCaptureCandidateEvidence(
    val frameVersion: Long,
    val physicalAgeMs: Double,
    val afTransitioning: Boolean,
    val afExplicitlyUnfocused: Boolean,
    val lensMoving: Boolean,
    val aeTransitioning: Boolean,
    val awbTransitioning: Boolean
) {
    val state: ZslCaptureCandidateState
        get() = if (
            afTransitioning ||
            afExplicitlyUnfocused ||
            lensMoving ||
            aeTransitioning ||
            awbTransitioning
        ) {
            ZslCaptureCandidateState.TRANSITIONING
        } else {
            ZslCaptureCandidateState.USABLE
        }

    fun reason(): String {
        if (state == ZslCaptureCandidateState.USABLE) return "EXACT_FRAME_USABLE"
        val reasons = buildList {
            if (afTransitioning) add("AF_TRANSITIONING")
            if (afExplicitlyUnfocused) add("AF_EXPLICITLY_UNFOCUSED")
            if (lensMoving) add("LENS_MOVING")
            if (aeTransitioning) add("AE_TRANSITIONING")
            if (awbTransitioning) add("AWB_TRANSITIONING")
        }
        return reasons.joinToString("+")
    }
}

object ZslCaptureCandidateRolePolicy {
    /**
     * Prefer an exact-frame usable candidate over an exact-frame transitional candidate. Within
     * the same state preserve Near-ZSL semantics by selecting the smallest physical pre-shutter age.
     * frameVersion is only a deterministic final tie-breaker.
     */
    fun selectBest(candidates: List<ZslCaptureCandidateEvidence>): ZslCaptureCandidateEvidence? =
        candidates
            .asSequence()
            .filter { it.physicalAgeMs.isFinite() && it.physicalAgeMs >= 0.0 }
            .sortedWith(
                compareBy<ZslCaptureCandidateEvidence> {
                    if (it.state == ZslCaptureCandidateState.USABLE) 0 else 1
                }
                    .thenBy { it.physicalAgeMs }
                    .thenByDescending { it.frameVersion }
            )
            .firstOrNull()
}
