package com.bncam.core.quality

import kotlin.math.pow

object SensorNoiseCalibrationMapper {
    const val BASE_CHROMA_AUTHORITY_STOPS = 4.0
    const val MIN_CHROMA_AUTHORITY_STOPS = 0.0
    const val MAX_CHROMA_AUTHORITY_STOPS = 5.0

    const val BASE_LUMA_AUTHORITY_STOPS = 2.25
    const val MIN_LUMA_AUTHORITY_STOPS = 0.0
    const val MAX_LUMA_AUTHORITY_STOPS = 3.50

    fun clampAdjustment(value: Double): Double = value.coerceIn(-1.0, 1.0)
    fun clampBooster(value: Double): Double = value.coerceIn(0.0, 1.0)

    fun noiseModelCalibrationFactor(adjustment: Double): Double {
        val safeAdj = clampAdjustment(adjustment)
        return 2.0.pow(safeAdj * 2.0)
    }

    fun effectiveChromaAuthorityStops(adjustment: Double): Double {
        val safeAdj = clampAdjustment(adjustment)
        return if (safeAdj < 0.0) {
            BASE_CHROMA_AUTHORITY_STOPS + safeAdj * (BASE_CHROMA_AUTHORITY_STOPS - MIN_CHROMA_AUTHORITY_STOPS)
        } else {
            BASE_CHROMA_AUTHORITY_STOPS + safeAdj * (MAX_CHROMA_AUTHORITY_STOPS - BASE_CHROMA_AUTHORITY_STOPS)
        }
    }

    fun effectiveLumaAuthorityStops(adjustment: Double): Double {
        val safeAdj = clampAdjustment(adjustment)
        return if (safeAdj < 0.0) {
            BASE_LUMA_AUTHORITY_STOPS + safeAdj * (BASE_LUMA_AUTHORITY_STOPS - MIN_LUMA_AUTHORITY_STOPS)
        } else {
            BASE_LUMA_AUTHORITY_STOPS + safeAdj * (MAX_LUMA_AUTHORITY_STOPS - BASE_LUMA_AUTHORITY_STOPS)
        }
    }

    fun chromaUserScale(dynamicIsoCoeff: Double, effectiveChromaStops: Double): Double {
        val safeCoeff = clampBooster(dynamicIsoCoeff)
        return 2.0.pow(safeCoeff * effectiveChromaStops)
    }

    fun lumaUserScale(dynamicIsoCoeff: Double, effectiveLumaStops: Double): Double {
        val safeCoeff = clampBooster(dynamicIsoCoeff)
        return 2.0.pow(safeCoeff * effectiveLumaStops)
    }

    fun normalizedChromaAuthority(effectiveChromaStops: Double): Double {
        return (effectiveChromaStops / MAX_CHROMA_AUTHORITY_STOPS).coerceIn(0.0, 1.0)
    }

    fun effectiveChromaControl(dynamicIsoCoeff: Double, normalizedChromaAuth: Double): Double {
        return clampBooster(dynamicIsoCoeff) * normalizedChromaAuth.coerceIn(0.0, 1.0)
    }

    fun outerRingAuthority(mode: String, dynamicIsoCoeff: Double, effectiveChromaStops: Double): Double {
        if (mode.equals("Off", ignoreCase = true) || effectiveChromaStops <= 0.0) return 0.0
        val safeCoeff = clampBooster(dynamicIsoCoeff)
        if (safeCoeff <= 0.0) return 0.0
        val normAuth = normalizedChromaAuthority(effectiveChromaStops)
        val effControl = effectiveChromaControl(safeCoeff, normAuth)
        return smoothstep(0.35, 0.85, effControl)
    }

    private fun smoothstep(edge0: Double, edge1: Double, x: Double): Double {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }
}
