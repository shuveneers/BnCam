package com.bncam.core.quality

import kotlin.math.max

private const val PHYSICAL_CHANNEL_COUNT = 4
private const val SENSOR_NOISE_PROFILE_VALUE_COUNT = 8
private const val NOISE_EPSILON = 1.0e-12

/**
 * Physical sensor-noise authority. Processing strength remains a separate downstream concern.
 *
 * OEM is the direct per-frame Camera2 SENSOR_NOISE_PROFILE route.
 * SYSTEM, MANUAL and PRESET are parametric A/B/C/D models resolved by BnCam.
 */
enum class NoiseModelSource {
    OEM,
    SYSTEM,
    MANUAL,
    PRESET
}

/** Canonical Bayer order used by the physical noise-model contract. */
enum class NoiseModelChannel {
    R,
    GR,
    GB,
    B
}

/**
 * Pure-Kotlin representation of the four Bayer arrangements supported by BnCam.
 * [mosaicOrder] describes Camera2 SENSOR_NOISE_PROFILE pair order in sensor mosaic order.
 */
enum class NoiseModelCfaPattern(
    val mosaicOrder: List<NoiseModelChannel>
) {
    RGGB(listOf(NoiseModelChannel.R, NoiseModelChannel.GR, NoiseModelChannel.GB, NoiseModelChannel.B)),
    GRBG(listOf(NoiseModelChannel.GR, NoiseModelChannel.R, NoiseModelChannel.B, NoiseModelChannel.GB)),
    GBRG(listOf(NoiseModelChannel.GB, NoiseModelChannel.B, NoiseModelChannel.R, NoiseModelChannel.GR)),
    BGGR(listOf(NoiseModelChannel.B, NoiseModelChannel.GB, NoiseModelChannel.GR, NoiseModelChannel.R));

    init {
        require(mosaicOrder.size == PHYSICAL_CHANNEL_COUNT)
        require(mosaicOrder.toSet().size == PHYSICAL_CHANNEL_COUNT)
    }

    /** Converts [S0,O0,S1,O1,...] from mosaic order to canonical R,Gr,Gb,B S/O arrays. */
    fun canonicalizeSensorNoiseProfile(mosaicSo: DoubleArray): CanonicalNoiseProfile {
        require(mosaicSo.size == SENSOR_NOISE_PROFILE_VALUE_COUNT) {
            "Bayer SENSOR_NOISE_PROFILE must contain exactly four S/O pairs"
        }
        require(mosaicSo.all(Double::isFinite)) {
            "SENSOR_NOISE_PROFILE values must be finite"
        }
        require(mosaicSo.all { it >= 0.0 }) {
            "SENSOR_NOISE_PROFILE S/O values must be non-negative"
        }
        require(mosaicSo.any { it > NOISE_EPSILON }) {
            "SENSOR_NOISE_PROFILE must contain physical noise information"
        }

        val s = DoubleArray(PHYSICAL_CHANNEL_COUNT)
        val o = DoubleArray(PHYSICAL_CHANNEL_COUNT)
        mosaicOrder.forEachIndexed { mosaicIndex, channel ->
            val canonicalIndex = channel.ordinal
            s[canonicalIndex] = mosaicSo[mosaicIndex * 2]
            o[canonicalIndex] = mosaicSo[mosaicIndex * 2 + 1]
        }
        return CanonicalNoiseProfile(s, o)
    }

    companion object {
        fun fromName(name: String): NoiseModelCfaPattern =
            values().firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("Unsupported Bayer CFA pattern: $name")
    }
}

/** Immutable canonical R,Gr,Gb,B S/O pair set. */
class CanonicalNoiseProfile(
    s: DoubleArray,
    o: DoubleArray
) {
    private val sValues = s.copyAndValidatePhysicalSo("S")
    private val oValues = o.copyAndValidatePhysicalSo("O")

    val s: DoubleArray get() = sValues.clone()
    val o: DoubleArray get() = oValues.clone()
}

/**
 * AGC-compatible parametric physical noise model in canonical R,Gr,Gb,B order.
 * Coefficients may be negative; only resolved S/O are clamped to zero.
 */
class ParametricNoiseModel(
    a: DoubleArray,
    b: DoubleArray,
    c: DoubleArray,
    d: DoubleArray,
    val isoStep: Double
) {
    private val aValues = a.copyAndValidateCoefficient("A")
    private val bValues = b.copyAndValidateCoefficient("B")
    private val cValues = c.copyAndValidateCoefficient("C")
    private val dValues = d.copyAndValidateCoefficient("D")

    init {
        require(isoStep.isFinite() && isoStep > 0.0) { "isoStep must be finite and > 0" }
    }

    val a: DoubleArray get() = aValues.clone()
    val b: DoubleArray get() = bValues.clone()
    val c: DoubleArray get() = cValues.clone()
    val d: DoubleArray get() = dValues.clone()

    internal fun resolveAt(noiseModelIso: Double): CanonicalNoiseProfile {
        require(noiseModelIso.isFinite() && noiseModelIso >= 0.0) {
            "noise-model ISO must be finite and >= 0"
        }

        val digitalGain = max(1.0, noiseModelIso / isoStep)
        val isoSquared = noiseModelIso * noiseModelIso
        val digitalGainSquared = digitalGain * digitalGain

        val s = DoubleArray(PHYSICAL_CHANNEL_COUNT) { channel ->
            resolvedNonNegative(aValues[channel] * noiseModelIso + bValues[channel], "S", channel)
        }
        val o = DoubleArray(PHYSICAL_CHANNEL_COUNT) { channel ->
            resolvedNonNegative(
                cValues[channel] * isoSquared + dValues[channel] * digitalGainSquared,
                "O",
                channel
            )
        }
        return CanonicalNoiseProfile(s, o)
    }
}

/** One immutable resolved physical noise model for a capture/frame. */
class ResolvedNoiseModel internal constructor(
    val source: NoiseModelSource,
    val lensId: String,
    val captureIso: Int,
    val effectiveNoiseModelIso: Double?,
    val dynamicIsoEnabled: Boolean,
    val dynamicIsoCoefficient: Double?,
    val dynamicIsoCoefficientApplied: Boolean,
    val cfaPattern: NoiseModelCfaPattern,
    val isoStep: Double?,
    val provenance: String,
    profile: CanonicalNoiseProfile
) {
    private val sValues = profile.s
    private val oValues = profile.o

    val effectiveS: DoubleArray get() = sValues.clone()
    val effectiveO: DoubleArray get() = oValues.clone()
    val canonicalOrder: String get() = CANONICAL_ORDER

    init {
        require(lensId.isNotBlank()) { "lensId cannot be blank" }
        require(captureIso > 0) { "captureIso must be > 0" }
        require(provenance.isNotBlank()) { "provenance cannot be blank" }

        if (source == NoiseModelSource.OEM) {
            require(effectiveNoiseModelIso == null) {
                "OEM uses direct per-frame S/O and has no parametric noise-model ISO"
            }
            require(!dynamicIsoEnabled) { "OEM cannot enable Dynamic ISO" }
            require(dynamicIsoCoefficient == null) { "OEM has no Dynamic ISO coefficient" }
            require(!dynamicIsoCoefficientApplied) { "OEM cannot apply Dynamic ISO" }
            require(isoStep == null) { "OEM does not use parametric isoStep" }
        } else {
            require(effectiveNoiseModelIso != null && effectiveNoiseModelIso.isFinite())
            require(dynamicIsoCoefficient != null && dynamicIsoCoefficient.isFinite())
            require(isoStep != null && isoStep.isFinite() && isoStep > 0.0)
            require(dynamicIsoCoefficientApplied == dynamicIsoEnabled) {
                "Parametric Dynamic ISO application state must match enabled state"
            }
        }
    }

    companion object {
        const val CANONICAL_ORDER = "R,Gr,Gb,B"
    }
}

/**
 * Single physical-noise-model resolver.
 *
 * OEM consumes Camera2 S/O directly.
 * SYSTEM, MANUAL and PRESET evaluate A/B/C/D exactly once. With Dynamic ISO disabled,
 * the model is evaluated at capture ISO. With Dynamic ISO enabled, the AGC transform
 * ISO_NM = trunc(50 + k * (ISO_capture - 50)) is applied first. AGC V12 converts this
 * positive intermediate to integer ISO before A/B/C/D evaluation; BnCam mirrors that exactly.
 */
object NoiseModelResolver {
    const val BASE_NOISE_MODEL_ISO = 50.0
    const val MIN_DYNAMIC_ISO_COEFFICIENT = 0.0
    const val MAX_DYNAMIC_ISO_COEFFICIENT = 2.0
    const val DYNAMIC_ISO_COEFFICIENT_STEP = 0.01
    const val DEFAULT_DYNAMIC_ISO_COEFFICIENT = 1.0
    const val DEFAULT_DYNAMIC_ISO_ENABLED = false

    sealed class Request {
        abstract val lensId: String
        abstract val captureIso: Int
        abstract val cfaPattern: NoiseModelCfaPattern
        abstract val provenance: String

        /** Direct, unmodified Camera2/HAL SENSOR_NOISE_PROFILE for this frame. */
        class Oem(
            override val lensId: String,
            override val captureIso: Int,
            override val cfaPattern: NoiseModelCfaPattern,
            sensorNoiseProfileMosaicSo: DoubleArray,
            override val provenance: String = "Camera2 SENSOR_NOISE_PROFILE"
        ) : Request() {
            private val profileValues = sensorNoiseProfileMosaicSo.clone()
            internal val sensorNoiseProfileMosaicSo: DoubleArray get() = profileValues.clone()
        }

        /**
         * Parametric request used by SYSTEM, MANUAL and PRESET.
         * SYSTEM is BnCam's per-lens system model, expected to be populated from OEM/sensor
         * calibration data by the later persistence/calibration layer; it is not one copied
         * per-frame S/O pair masquerading as a four-coefficient model.
         */
        class Parametric(
            override val lensId: String,
            override val captureIso: Int,
            override val cfaPattern: NoiseModelCfaPattern,
            val source: NoiseModelSource,
            val model: ParametricNoiseModel,
            val dynamicIsoEnabled: Boolean = DEFAULT_DYNAMIC_ISO_ENABLED,
            val dynamicIsoCoefficient: Double = DEFAULT_DYNAMIC_ISO_COEFFICIENT,
            override val provenance: String
        ) : Request() {
            init {
                require(
                    source == NoiseModelSource.SYSTEM ||
                        source == NoiseModelSource.MANUAL ||
                        source == NoiseModelSource.PRESET
                ) {
                    "Parametric request source must be SYSTEM, MANUAL or PRESET"
                }
                validateDynamicIsoCoefficient(dynamicIsoCoefficient)
            }
        }
    }

    fun resolve(request: Request): ResolvedNoiseModel {
        validateCaptureIdentity(request.lensId, request.captureIso, request.provenance)
        return when (request) {
            is Request.Oem -> resolveOem(request)
            is Request.Parametric -> resolveParametric(request)
        }
    }

    /**
     * Exact AGC V12 Dynamic ISO transform for an enabled parametric model.
     *
     * AGC converts the positive transformed ISO to an integer before evaluating A/B/C/D. Kotlin
     * Double.toInt() truncates toward zero; because this domain is non-negative, that is the same
     * operation as AGC's integer conversion. Keep the public return type Double because the
     * parametric equations are floating point, but the value itself is always an integer ISO.
     */
    fun effectiveNoiseModelIso(captureIso: Int, dynamicIsoCoefficient: Double): Double {
        require(captureIso > 0) { "captureIso must be > 0" }
        validateDynamicIsoCoefficient(dynamicIsoCoefficient)

        val transformedIso = BASE_NOISE_MODEL_ISO +
            dynamicIsoCoefficient * (captureIso.toDouble() - BASE_NOISE_MODEL_ISO)
        require(transformedIso.isFinite() && transformedIso >= 0.0) {
            "Resolved noise-model ISO must be finite and >= 0"
        }
        return transformedIso.toInt().toDouble()
    }

    /** Resolves the ISO actually used to evaluate SYSTEM/MANUAL/PRESET. */
    fun resolveParametricIso(
        captureIso: Int,
        dynamicIsoEnabled: Boolean,
        dynamicIsoCoefficient: Double
    ): Double {
        require(captureIso > 0) { "captureIso must be > 0" }
        validateDynamicIsoCoefficient(dynamicIsoCoefficient)
        return if (dynamicIsoEnabled) {
            effectiveNoiseModelIso(captureIso, dynamicIsoCoefficient)
        } else {
            captureIso.toDouble()
        }
    }

    private fun resolveOem(request: Request.Oem): ResolvedNoiseModel {
        val profile = request.cfaPattern.canonicalizeSensorNoiseProfile(
            request.sensorNoiseProfileMosaicSo
        )
        return ResolvedNoiseModel(
            source = NoiseModelSource.OEM,
            lensId = request.lensId,
            captureIso = request.captureIso,
            effectiveNoiseModelIso = null,
            dynamicIsoEnabled = false,
            dynamicIsoCoefficient = null,
            dynamicIsoCoefficientApplied = false,
            cfaPattern = request.cfaPattern,
            isoStep = null,
            provenance = request.provenance,
            profile = profile
        )
    }

    private fun resolveParametric(request: Request.Parametric): ResolvedNoiseModel {
        val effectiveIso = resolveParametricIso(
            captureIso = request.captureIso,
            dynamicIsoEnabled = request.dynamicIsoEnabled,
            dynamicIsoCoefficient = request.dynamicIsoCoefficient
        )
        val profile = request.model.resolveAt(effectiveIso)
        return ResolvedNoiseModel(
            source = request.source,
            lensId = request.lensId,
            captureIso = request.captureIso,
            effectiveNoiseModelIso = effectiveIso,
            dynamicIsoEnabled = request.dynamicIsoEnabled,
            dynamicIsoCoefficient = request.dynamicIsoCoefficient,
            dynamicIsoCoefficientApplied = request.dynamicIsoEnabled,
            cfaPattern = request.cfaPattern,
            isoStep = request.model.isoStep,
            provenance = request.provenance,
            profile = profile
        )
    }

    private fun validateCaptureIdentity(lensId: String, captureIso: Int, provenance: String) {
        require(lensId.isNotBlank()) { "lensId cannot be blank" }
        require(captureIso > 0) { "captureIso must be > 0" }
        require(provenance.isNotBlank()) { "provenance cannot be blank" }
    }

    private fun validateDynamicIsoCoefficient(dynamicIsoCoefficient: Double) {
        require(dynamicIsoCoefficient.isFinite()) { "Dynamic ISO coefficient must be finite" }
        require(dynamicIsoCoefficient in MIN_DYNAMIC_ISO_COEFFICIENT..MAX_DYNAMIC_ISO_COEFFICIENT) {
            "Dynamic ISO coefficient must be in 0.00..2.00"
        }
    }
}

private fun DoubleArray.copyAndValidateCoefficient(label: String): DoubleArray {
    require(size == PHYSICAL_CHANNEL_COUNT) { "$label must contain exactly four R,Gr,Gb,B values" }
    require(all(Double::isFinite)) { "$label coefficients must be finite" }
    return clone()
}

private fun DoubleArray.copyAndValidatePhysicalSo(label: String): DoubleArray {
    require(size == PHYSICAL_CHANNEL_COUNT) { "$label must contain exactly four R,Gr,Gb,B values" }
    require(all { it.isFinite() && it >= 0.0 }) { "$label values must be finite and non-negative" }
    return clone()
}

private fun resolvedNonNegative(value: Double, label: String, channel: Int): Double {
    require(value.isFinite()) { "Resolved $label[$channel] is not finite" }
    return max(0.0, value)
}
