package com.bncam.core.capture

/**
 * A flicker-safe shutter is a PHOTOGRAPHIC preference, never an unconditional lower bound.
 * Only relax the generic handheld fallback after sustained RAW evidence that changes are
 * coherent illumination (or stable texture), not subject motion. Preserve every actual
 * camera/subject motion ceiling and the real preview frame-period budget.
 *
 * An unresolved PWM frequency cannot be invented from 30-fps global luminance aliases.
 */
internal object BnAutoFlickerSafetyPolicy {
    fun ceilingNs(
        originalCeilingNs: Long,
        lightPeriodNs: Long?,
        minStreamFrameDurationNs: Long,
        maxFrameDurationNs: Long,
        sustainedNonMotionNs: Long,
        recentMotion: Boolean,
        measuredCameraCeilingNs: Long?,
        measuredSubjectCeilingNs: Long?
    ): Long {
        val lightPeriod = lightPeriodNs ?: return originalCeilingNs
        if (originalCeilingNs <= 0L || lightPeriod <= 0L || recentMotion ||
            sustainedNonMotionNs < 250_000_000L) return originalCeilingNs
        // Leave a little timing margin for imperfect HAL exposure-duration realization.
        val budgetNs = minOf(minStreamFrameDurationNs, maxFrameDurationNs) - 750_000L
        if (lightPeriod > budgetNs) return originalCeilingNs
        if (measuredCameraCeilingNs?.let { it < lightPeriod } == true ||
            measuredSubjectCeilingNs?.let { it < lightPeriod } == true) return originalCeilingNs
        return maxOf(originalCeilingNs, lightPeriod)
    }
}
