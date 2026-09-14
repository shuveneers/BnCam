package com.bncam.core.capture

import kotlin.math.ceil
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min

/**
 * The single physical shutter/ISO decomposition owner for automatic default-RAW exposure.
 *
 * Photometric code supplies one total exposure product. This allocator alone decides how that
 * product is represented by shutter + ISO under sensor, motion and flicker constraints. API36 may
 * subsequently leave the returned ISO as an expectation while Camera2 AE owns the realized gain;
 * manual fallback requests both values directly.
 */
data class DefaultRawExposureAllocation(
    val requestedExposureProduct: Double,
    val boundedExposureProduct: Double,
    val exposureTimeNs: Long,
    val sensitivityIso: Int,
    val frameDurationNs: Long,
    val realizedExposureProduct: Double,
    val residualProductErrorEv: Float,
    val productBoundApplied: Boolean,
    val flickerShutterHeld: Boolean,
    val limitingConstraint: String,
    val reason: String
) {
    fun summary(): String =
        "requestedExposureProduct=$requestedExposureProduct;boundedExposureProduct=$boundedExposureProduct;" +
            "allocatedExposureNs=$exposureTimeNs;allocatedIso=$sensitivityIso;" +
            "allocatedFrameDurationNs=$frameDurationNs;realizedExposureProduct=$realizedExposureProduct;" +
            "allocationResidualEv=$residualProductErrorEv;productBoundApplied=$productBoundApplied;" +
            "flickerShutterHeld=$flickerShutterHeld;allocationLimit=$limitingConstraint;" +
            "allocationReason=$reason"
}

object DefaultRawExposureAllocator {
    fun allocate(
        exposureProduct: Double,
        safeExposureCeilingNs: Long,
        bounds: ExposureBounds,
        flickerConstraint: RawFlickerConstraint = RawFlickerConstraint(),
        previousExposureNs: Long? = null,
        preferHeldFlickerShutter: Boolean = true
    ): DefaultRawExposureAllocation? {
        if (!valid(exposureProduct, safeExposureCeilingNs, bounds)) return null

        val ceiling = safeExposureCeilingNs.coerceIn(bounds.minExposureNs, bounds.maxExposureNs)
        val minimumProduct = bounds.minIso.toDouble() * bounds.minExposureNs.toDouble()
        val maximumProduct = bounds.maxIso.toDouble() * ceiling.toDouble()
        val boundedProduct = exposureProduct.coerceIn(minimumProduct, maximumProduct)
        val productBoundApplied = boundedProduct != exposureProduct

        val exposureAtMinIso = ceil(boundedProduct / bounds.minIso.toDouble()).toLong()
            .coerceIn(bounds.minExposureNs, bounds.maxExposureNs)
        val unconstrainedExposure = min(ceiling, exposureAtMinIso)
            .coerceIn(bounds.minExposureNs, ceiling)

        val heldExposure = previousExposureNs?.takeIf { previous ->
            preferHeldFlickerShutter &&
                previous in bounds.minExposureNs..ceiling &&
                flickerConstraint.frequency != RawFlickerFrequency.NONE &&
                flickerConstraint.isExposureAligned(previous) &&
                ceil(boundedProduct / previous.toDouble()).toInt() in bounds.minIso..bounds.maxIso
        }
        val exposure = heldExposure ?: flickerConstraint.constrainExposureNs(
            unconstrainedExposure,
            bounds.minExposureNs,
            ceiling
        )
        val requestedIso = ceil(boundedProduct / exposure.toDouble()).toInt()
        val iso = requestedIso.coerceIn(bounds.minIso, bounds.maxIso)
        val realizedProduct = exposure.toDouble() * iso.toDouble()
        val residualEv = if (realizedProduct > 0.0 && boundedProduct > 0.0) {
            log2(boundedProduct / realizedProduct).toFloat()
        } else {
            0f
        }
        val flickerResolved = flickerConstraint.frequency != RawFlickerFrequency.NONE
        val completePeriodFits = flickerConstraint.periodNs?.let { exposure >= it } ?: false
        val limitingConstraint = when {
            productBoundApplied && boundedProduct >= maximumProduct -> "MAX_ISO"
            productBoundApplied && boundedProduct <= minimumProduct -> "MIN_SENSOR_PRODUCT"
            flickerResolved && !completePeriodFits -> "FLICKER_UNAVOIDABLE_SHORT_EXPOSURE"
            heldExposure != null -> "FLICKER_${flickerConstraint.frequency.name}"
            flickerResolved && flickerConstraint.isExposureAligned(exposure) ->
                "FLICKER_${flickerConstraint.frequency.name}"
            exposureAtMinIso <= ceiling -> "MIN_ISO"
            exposure >= ceiling -> "MOTION_OR_STREAM_CEILING"
            else -> "NONE"
        }
        val frameDuration = flickerConstraint.stableFrameDurationNs(
            exposureNs = exposure,
            minFrameDurationNs = max(exposure, bounds.minExposureNs)
        )
        return DefaultRawExposureAllocation(
            requestedExposureProduct = exposureProduct,
            boundedExposureProduct = boundedProduct,
            exposureTimeNs = exposure,
            sensitivityIso = iso,
            frameDurationNs = frameDuration,
            realizedExposureProduct = realizedProduct,
            residualProductErrorEv = residualEv,
            productBoundApplied = productBoundApplied,
            flickerShutterHeld = heldExposure != null,
            limitingConstraint = limitingConstraint,
            reason = when {
                productBoundApplied -> "exposure_product_bounded_to_sensor_and_motion_authority"
                heldExposure != null -> "flicker_safe_shutter_preserved_iso_carries_product_change"
                flickerResolved && flickerConstraint.isExposureAligned(exposure) ->
                    "exposure_product_decomposed_on_flicker_safe_shutter"
                else -> "exposure_product_decomposed_photon_first"
            }
        )
    }

    private fun valid(product: Double, ceiling: Long, bounds: ExposureBounds): Boolean =
        product.isFinite() && product > 0.0 && ceiling > 0L &&
            bounds.minIso > 0 && bounds.maxIso >= bounds.minIso &&
            bounds.minExposureNs > 0L && bounds.maxExposureNs >= bounds.minExposureNs
}
