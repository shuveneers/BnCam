package com.bncam.core.model

data class ColorSensorReading(
    val sourceId: String = "none",
    val capability: String = "UNSUPPORTED",
    val timestampNs: Long = 0L,
    val cctKelvin: Float = 0.0f,
    val r: Float = 0.0f,
    val g: Float = 0.0f,
    val b: Float = 0.0f,
    val isValid: Boolean = false
)
