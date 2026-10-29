package com.bncam.core.capture

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log2
import kotlin.math.pow

/** Plans one continuous endpoint in EV space; sensor observations still determine that endpoint.
 * The published product is only the last point on the trajectory, never a photometric measurement.
 */
internal object BnAutoExposureTrajectory {
    fun nextProduct(publishedProduct: Double, physicalProduct: Double, goalProduct: Double,
        elapsedSensorNs: Long, framePeriodNs: Long, confidence: Double, urgentClipping: Boolean): Double {
        require(listOf(publishedProduct, physicalProduct, goalProduct).all { it.isFinite() && it > 0 })
        val frame = framePeriodNs.coerceIn(8_000_000L, 200_000_000L)
        // Dropped observations cannot accumulate a large visible jump.
        val seconds = elapsedSensorNs.coerceIn(1L, frame).toDouble() / 1e9
        val error = log2(goalProduct / publishedProduct)
        val trust = confidence.coerceIn(0.2, 1.0)
        val alpha = 1 - exp(-seconds / (0.22 + 0.12 * (1 - trust)))
        val speed = if (urgentClipping && error < 0) 12.0 else 3.0
        val change = (error * alpha).coerceIn(-speed * seconds, speed * seconds)
        // A faulty/unrealized HAL cannot take the trajectory arbitrarily far from truth.
        val lead = log2(publishedProduct / physicalProduct)
        if (abs(lead) > 2.0 && lead * change > 0) return publishedProduct
        return publishedProduct * 2.0.pow(change)
    }
}
