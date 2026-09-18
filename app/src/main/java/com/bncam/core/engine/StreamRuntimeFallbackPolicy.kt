package com.bncam.core.engine

import com.bncam.data.settings.StreamConfigurationMode

/**
 * Transient, non-persistent recovery tiers for one active Lens ID.
 *
 * This state is deliberately separate from the user's saved Stream Configuration. A runtime
 * failure may quarantine a candidate for the current process/session, but it never rewrites the
 * user's preference behind their back.
 */
enum class StreamRuntimeFallbackTier {
    NONE,
    AUTO_GEOMETRY,
    CONSERVATIVE_FULL_FOV
}

data class StreamRuntimeFallbackOverride(
    val tier: StreamRuntimeFallbackTier,
    val configuredMode: StreamConfigurationMode,
    val candidateId: String?,
    val settingsFingerprint: String?,
    val failureReason: String,
    val failureCount: Int
) {
    fun appliesTo(mode: StreamConfigurationMode, activeCandidateId: String?): Boolean {
        if (mode != configuredMode) return false
        if (mode != StreamConfigurationMode.VALIDATED) return true
        return normalizeCandidate(candidateId) == normalizeCandidate(activeCandidateId)
    }

    private fun normalizeCandidate(value: String?): String =
        value?.trim()?.takeIf { it.isNotEmpty() } ?: "AUTO_CANDIDATE"
}

/** Bounded deterministic escalation. There is no retry loop beyond the final conservative tier. */
object StreamRuntimeFallbackPolicy {
    fun nextTier(
        mode: StreamConfigurationMode,
        currentTier: StreamRuntimeFallbackTier
    ): StreamRuntimeFallbackTier? = when (currentTier) {
        StreamRuntimeFallbackTier.NONE -> when (mode) {
            StreamConfigurationMode.AUTO -> StreamRuntimeFallbackTier.CONSERVATIVE_FULL_FOV
            StreamConfigurationMode.VALIDATED,
            StreamConfigurationMode.MANUAL -> StreamRuntimeFallbackTier.AUTO_GEOMETRY
        }
        StreamRuntimeFallbackTier.AUTO_GEOMETRY -> StreamRuntimeFallbackTier.CONSERVATIVE_FULL_FOV
        StreamRuntimeFallbackTier.CONSERVATIVE_FULL_FOV -> null
    }
}
