package com.bncam.core.capture

/**
 * Resolves the clockwise pixel rotation needed to display a sensor frame upright.
 *
 * Back cameras require subtraction of display rotation from sensor orientation.
 * Front cameras use addition because their sensor coordinate system is mirrored
 * relative to the device display.
 */
object OutputRotationResolver {
    fun resolve(
        sensorOrientationDegrees: Int,
        displayRotationDegrees: Int,
        frontFacing: Boolean
    ): Int {
        val sensor = normalize(sensorOrientationDegrees)
        val display = normalize(displayRotationDegrees)
        return if (frontFacing) {
            normalize(sensor + display)
        } else {
            normalize(sensor - display)
        }
    }

    private fun normalize(degrees: Int): Int = ((degrees % 360) + 360) % 360
}
