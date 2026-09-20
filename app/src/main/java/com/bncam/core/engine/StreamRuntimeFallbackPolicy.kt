package com.bncam.core.engine

/**
 * Transient, non-persistent recovery tiers for one active Lens ID.
 *
 * Saved stream settings are never rewritten. AUTO_GEOMETRY only exists when an explicit Photo
 * resolution override was actually active; the final conservative tier is bounded and terminal.
 */
enum class StreamRuntimeFallbackTier {
    NONE,
    AUTO_GEOMETRY,
    CONSERVATIVE_FULL_FOV
}

data class StreamRuntimeFallbackOverride(
    val tier: StreamRuntimeFallbackTier,
    val authorityFingerprint: String,
    val settingsFingerprint: String?,
    val failureReason: String,
    val failureCount: Int
) {
    init {
        require(authorityFingerprint.isNotBlank()) { "authorityFingerprint must not be blank" }
    }

    fun appliesTo(activeAuthorityFingerprint: String): Boolean =
        authorityFingerprint == activeAuthorityFingerprint
}

/** Bounded deterministic escalation. There is no retry loop beyond the final conservative tier. */
object StreamRuntimeFallbackPolicy {
    fun nextTier(
        explicitResolutionOverrideApplied: Boolean,
        currentTier: StreamRuntimeFallbackTier
    ): StreamRuntimeFallbackTier? = when (currentTier) {
        StreamRuntimeFallbackTier.NONE -> if (explicitResolutionOverrideApplied) {
            StreamRuntimeFallbackTier.AUTO_GEOMETRY
        } else {
            StreamRuntimeFallbackTier.CONSERVATIVE_FULL_FOV
        }
        StreamRuntimeFallbackTier.AUTO_GEOMETRY -> StreamRuntimeFallbackTier.CONSERVATIVE_FULL_FOV
        StreamRuntimeFallbackTier.CONSERVATIVE_FULL_FOV -> null
    }
}
