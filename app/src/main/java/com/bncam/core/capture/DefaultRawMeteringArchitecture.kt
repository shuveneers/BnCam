package com.bncam.core.capture

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow

/**
 * Generation-scoped freshness contract for the scene-linear metering signal used by default RAW AE.
 * This deliberately does not own Camera2 regions; it only owns whether a BnCam exposure statistic is
 * fresh enough to be trusted, should temporarily fall back to the last-known-good value, or requires
 * a new Camera2-AE bootstrap.
 */
enum class DefaultRawMeteringFreshness {
    FRESH,
    LAST_KNOWN_GOOD,
    BOOTSTRAP_REQUIRED
}

data class DefaultRawMeteringSnapshot(
    val pipelineGeneration: Int,
    val source: String,
    val controllerLuma: Float?,
    val rawNearClipFraction: Float?,
    val sampleCount: Int,
    val measurementElapsedRealtimeNs: Long,
    val ageNs: Long,
    val freshness: DefaultRawMeteringFreshness,
    val valid: Boolean,
    val reason: String
) {
    fun summary(): String =
        "meteringSource=$source;meteringLuma=${controllerLuma ?: "unavailable"};" +
            "meteringClip=${rawNearClipFraction ?: "unavailable"};meteringSamples=$sampleCount;" +
            "meteringAgeNs=$ageNs;meteringFreshness=$freshness;meteringValid=$valid;" +
            "meteringReason=$reason"
}

class DefaultRawMeteringTracker(
    private val freshMaxAgeNs: Long = 250_000_000L,
    private val lastKnownGoodMaxAgeNs: Long = 1_000_000_000L
) {
    private var generation: Int = -1
    private var lastGood: DefaultRawMeteringSnapshot? = null

    @Synchronized
    fun reset(currentGeneration: Int) {
        generation = currentGeneration
        lastGood = null
    }

    @Synchronized
    fun observe(
        currentGeneration: Int,
        source: String,
        controllerLuma: Float?,
        rawNearClipFraction: Float?,
        sampleCount: Int,
        nowElapsedRealtimeNs: Long
    ): DefaultRawMeteringSnapshot {
        ensureGeneration(currentGeneration)
        val sanitizedLuma = controllerLuma?.takeIf { it.isFinite() && it > 0f }
        val sanitizedClip = rawNearClipFraction?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
        if (sampleCount > 0 && sanitizedLuma != null) {
            return DefaultRawMeteringSnapshot(
                pipelineGeneration = generation,
                source = source.ifBlank { "UNKNOWN" },
                controllerLuma = sanitizedLuma,
                rawNearClipFraction = sanitizedClip,
                sampleCount = sampleCount,
                measurementElapsedRealtimeNs = nowElapsedRealtimeNs,
                ageNs = 0L,
                freshness = DefaultRawMeteringFreshness.FRESH,
                valid = true,
                reason = "fresh_scene_linear_metering"
            ).also { lastGood = it }
        }
        return resolveInternal(nowElapsedRealtimeNs, source.ifBlank { "UNKNOWN" }, "invalid_or_empty_metering")
    }

    @Synchronized
    fun resolve(
        currentGeneration: Int,
        nowElapsedRealtimeNs: Long,
        source: String = "RESOLVE"
    ): DefaultRawMeteringSnapshot {
        ensureGeneration(currentGeneration)
        return resolveInternal(nowElapsedRealtimeNs, source.ifBlank { "RESOLVE" }, "metering_not_refreshed")
    }

    private fun resolveInternal(nowNs: Long, source: String, missingReason: String): DefaultRawMeteringSnapshot {
        val previous = lastGood
        if (previous != null) {
            val age = (nowNs - previous.measurementElapsedRealtimeNs).coerceAtLeast(0L)
            if (age <= freshMaxAgeNs.coerceAtLeast(0L)) {
                return previous.copy(ageNs = age, freshness = DefaultRawMeteringFreshness.FRESH)
            }
            if (age <= lastKnownGoodMaxAgeNs.coerceAtLeast(freshMaxAgeNs)) {
                return previous.copy(
                    ageNs = age,
                    freshness = DefaultRawMeteringFreshness.LAST_KNOWN_GOOD,
                    reason = "$missingReason;last_known_good_within_grace"
                )
            }
        }
        return DefaultRawMeteringSnapshot(
            pipelineGeneration = generation,
            source = source,
            controllerLuma = null,
            rawNearClipFraction = previous?.rawNearClipFraction,
            sampleCount = 0,
            measurementElapsedRealtimeNs = previous?.measurementElapsedRealtimeNs ?: 0L,
            ageNs = previous?.let { (nowNs - it.measurementElapsedRealtimeNs).coerceAtLeast(0L) } ?: Long.MAX_VALUE,
            freshness = DefaultRawMeteringFreshness.BOOTSTRAP_REQUIRED,
            valid = false,
            reason = "$missingReason;fresh_metering_unavailable"
        )
    }

    private fun ensureGeneration(currentGeneration: Int) {
        if (generation != currentGeneration) reset(currentGeneration)
    }
}

/** Pure photometric answer: how much total exposure product the current scene asks for. */
data class DefaultRawIdealExposureTarget(
    val targetLuma: Float,
    val observedLuma: Float,
    val exposureErrorEv: Float,
    val currentExposureProduct: Double,
    val idealExposureProduct: Double,
    val meteringFreshness: DefaultRawMeteringFreshness,
    val reason: String
) {
    fun summary(): String =
        "idealTargetLuma=$targetLuma;idealObservedLuma=$observedLuma;" +
            "idealErrorEv=$exposureErrorEv;currentExposureProduct=$currentExposureProduct;" +
            "idealExposureProduct=$idealExposureProduct;idealMeteringFreshness=$meteringFreshness;" +
            "idealReason=$reason"
}

/**
 * Exposure product after sensor-global bounds and highlight protection, but before shutter/ISO
 * decomposition. Delta 0212 gives the latter to the central allocator.
 */
data class DefaultRawFinalExposureTarget(
    val ideal: DefaultRawIdealExposureTarget,
    val finalExposureProduct: Double,
    val highlightProtectionEv: Float,
    val productBoundApplied: Boolean,
    val reason: String
) {
    fun summary(): String =
        ideal.summary() + ";finalExposureProduct=$finalExposureProduct;" +
            "highlightProtectionEv=$highlightProtectionEv;productBoundApplied=$productBoundApplied;" +
            "finalReason=$reason"
}

object DefaultRawExposureTargetModel {
    private const val MAX_PHOTOMETRIC_ERROR_EV = 6.0f

    fun resolve(
        targetLuma: Float,
        metering: DefaultRawMeteringSnapshot,
        currentExposureProduct: Double,
        minimumExposureProduct: Double? = null,
        maximumExposureProduct: Double? = null,
        exposureCompensationEv: Float = 0f
    ): DefaultRawFinalExposureTarget? {
        val observed = metering.controllerLuma?.takeIf { metering.valid && it.isFinite() && it > 0f }
            ?: return null
        if (!targetLuma.isFinite() || targetLuma <= 0f ||
            !currentExposureProduct.isFinite() || currentExposureProduct <= 0.0
        ) return null

        val errorEv = log2(
            (targetLuma.toDouble() / observed.toDouble()).coerceIn(
                2.0.pow(-MAX_PHOTOMETRIC_ERROR_EV.toDouble()),
                2.0.pow(MAX_PHOTOMETRIC_ERROR_EV.toDouble())
            )
        ).toFloat()
        val compensation = exposureCompensationEv.takeIf { it.isFinite() }?.coerceIn(-4f, 4f) ?: 0f
        val idealProduct = currentExposureProduct * 2.0.pow((errorEv + compensation).toDouble())

        val ideal = DefaultRawIdealExposureTarget(
            targetLuma = targetLuma,
            observedLuma = observed,
            exposureErrorEv = errorEv,
            currentExposureProduct = currentExposureProduct,
            idealExposureProduct = idealProduct.coerceAtLeast(1.0),
            meteringFreshness = metering.freshness,
            reason = if (abs(errorEv) < 0.05f) "photometric_target_already_near" else "scene_linear_luma_target"
        )

        val highlightProtectionEv = highlightPenaltyEv(metering.rawNearClipFraction)
        var finalProduct = ideal.idealExposureProduct / 2.0.pow(highlightProtectionEv.toDouble())
        var boundApplied = false
        minimumExposureProduct?.takeIf { it.isFinite() && it > 0.0 }?.let { minimum ->
            if (finalProduct < minimum) {
                finalProduct = minimum
                boundApplied = true
            }
        }
        maximumExposureProduct?.takeIf { it.isFinite() && it > 0.0 }?.let { maximum ->
            if (finalProduct > maximum) {
                finalProduct = maximum
                boundApplied = true
            }
        }
        return DefaultRawFinalExposureTarget(
            ideal = ideal,
            finalExposureProduct = finalProduct.coerceAtLeast(1.0),
            highlightProtectionEv = highlightProtectionEv,
            productBoundApplied = boundApplied,
            reason = when {
                boundApplied && highlightProtectionEv > 0f -> "highlight_protected_and_sensor_product_bounded"
                boundApplied -> "sensor_product_bounded"
                highlightProtectionEv > 0f -> "raw_highlight_protection"
                else -> "ideal_product_preserved"
            }
        )
    }

    private fun highlightPenaltyEv(rawNearClipFraction: Float?): Float {
        val clip = rawNearClipFraction?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: return 0f
        if (clip < 0.005f) return 0f
        // Highlight protection is a target constraint, not a slow feedback term. 0.5% near-clipping
        // starts a small correction; sustained 5%+ pressure can reduce the target by two stops.
        val normalized = ((clip - 0.005f) / 0.045f).coerceIn(0f, 1f)
        return 0.35f + 1.65f * normalized
    }
}
