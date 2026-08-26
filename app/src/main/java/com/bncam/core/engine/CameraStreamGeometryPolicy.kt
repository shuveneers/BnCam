package com.bncam.core.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Geometry-only policy for Camera2 output selection.
 *
 * Full sensor FOV is represented by the pre-correction/active-array aspect ratio. A preview- or
 * vendor-recommended stream list is allowed to win only when it contains that full-FOV aspect;
 * otherwise the standard stream list is preferred over silently accepting a cropped aspect.
 */
object CameraStreamGeometryPolicy {
    data class Extent(val width: Int, val height: Int) {
        val area: Long get() = width.toLong() * height.toLong()
        val valid: Boolean get() = width > 0 && height > 0
    }

    const val DEFAULT_ASPECT_TOLERANCE = 0.03

    fun sensorAspect(sensorWidth: Int, sensorHeight: Int): Double? {
        if (sensorWidth <= 0 || sensorHeight <= 0) return null
        val longSide = max(sensorWidth, sensorHeight).toDouble()
        val shortSide = min(sensorWidth, sensorHeight).toDouble()
        return (longSide / shortSide).takeIf { it.isFinite() && it > 0.0 }
    }

    fun fullFovCandidates(
        candidates: Collection<Extent>,
        sensorWidth: Int,
        sensorHeight: Int,
        aspectTolerance: Double = DEFAULT_ASPECT_TOLERANCE
    ): List<Extent> {
        val targetAspect = sensorAspect(sensorWidth, sensorHeight) ?: return emptyList()
        val tolerance = aspectTolerance.coerceAtLeast(0.0)
        return candidates.asSequence()
            .filter { it.valid }
            .filter { extent ->
                val longSide = max(extent.width, extent.height).toDouble()
                val shortSide = min(extent.width, extent.height).toDouble()
                val streamAspect = longSide / shortSide
                abs(streamAspect - targetAspect) / targetAspect <= tolerance
            }
            .toList()
    }

    /**
     * Prefer the optimized/recommended pool only when it preserves full sensor aspect. If it does
     * not, use the standard Camera2 pool with full sensor aspect. Last-resort fallback is standard
     * before preferred so a recommendation can never hide a more complete standard stream list.
     */
    fun prioritizedFullFovPool(
        preferred: Collection<Extent>,
        standard: Collection<Extent>,
        sensorWidth: Int,
        sensorHeight: Int,
        aspectTolerance: Double = DEFAULT_ASPECT_TOLERANCE
    ): List<Extent> {
        val preferredFull = fullFovCandidates(
            preferred,
            sensorWidth,
            sensorHeight,
            aspectTolerance
        )
        if (preferredFull.isNotEmpty()) return preferredFull

        val standardFull = fullFovCandidates(
            standard,
            sensorWidth,
            sensorHeight,
            aspectTolerance
        )
        if (standardFull.isNotEmpty()) return standardFull

        val standardValid = standard.filter { it.valid }
        if (standardValid.isNotEmpty()) return standardValid
        return preferred.filter { it.valid }
    }
}
