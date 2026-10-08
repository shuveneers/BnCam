package com.bncam.core.capture

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sqrt

data class BnAutoSensorLimits(
    val bounds: ExposureBounds,
    val maxFrameDurationNs: Long,
    val minStreamFrameDurationNs: Long,
    val fallbackHandheldNs: Long,
    val analogLimitIso: Int
) {
    init {
        require(bounds.minIso > 0 && bounds.maxIso >= bounds.minIso)
        require(bounds.minExposureNs > 0 && bounds.maxExposureNs >= bounds.minExposureNs)
        require(maxFrameDurationNs >= bounds.minExposureNs)
        require(minStreamFrameDurationNs in 1..maxFrameDurationNs)
    }
    val physicalBounds: ExposureBounds get() = bounds.copy(
        maxExposureNs = minOf(bounds.maxExposureNs, maxFrameDurationNs)
    )
}

/** All values are black-subtracted, white-normalized sensor measurements from ONE exact pair. */
data class BnAutoObservation(
    val timestampNs: Long,
    val exposureNs: Long,
    val iso: Int,
    val p20: Double,
    val p80: Double,
    val p98: Double,
    val channelClipFractions: List<Double>,
    val sampleCount: Int,
    val spatialHighlightFraction: Double,
    val noiseSlope: Double? = null,
    val noiseOffset: Double? = null,
    val cameraMotionCeilingNs: Long? = null,
    val subjectMotionCeilingNs: Long? = null,
    val motionConfidence: Float = 0f,
    val cameraMotionConfidence: Float = motionConfidence,
    val subjectMotionConfidence: Float = motionConfidence,
    val sensorLumaSamples: DoubleArray? = null,
    val sensorGradientX: DoubleArray? = null,
    val sensorGradientY: DoubleArray? = null,
    val channelP98Maximum: Double = Double.NaN,
    val quantizationVariance: Double = 1.0 / (12 * 1023 * 1023)
) {
    fun valid(): Boolean = timestampNs > 0 && exposureNs > 0 && iso > 0 && sampleCount >= 128 &&
        listOf(p20, p80, p98, spatialHighlightFraction).all { it.isFinite() && it in 0.0..1.0 } &&
        p20 <= p80 && p80 <= p98 && channelClipFractions.size == 4 &&
        channelClipFractions.all { it.isFinite() && it in 0.0..1.0 }
}

data class BnAutoExposureDecision(
    val targetExposureNs: Long,
    val targetISO: Int,
    val frameDurationNs: Long,
    val limitingConstraint: String,
    val meteringConfidence: Float,
    val reason: String
) {
    val exposureProduct: Double get() = targetExposureNs.toDouble() * targetISO
    fun warmAllocation(limits: BnAutoSensorLimits, flicker: RawFlickerConstraint): DefaultRawExposureAllocation =
        requireNotNull(DefaultRawExposureAllocator.allocate(exposureProduct,
            minOf(targetExposureNs, limits.minStreamFrameDurationNs), limits.physicalBounds, flicker,
            preferHeldFlickerShutter = false))

    fun summary(): String = "targetExposureNs=$targetExposureNs;targetISO=$targetISO;" +
        "frameDurationNs=$frameDurationNs;limitingConstraint=$limitingConstraint;" +
        "meteringConfidence=$meteringConfidence;reason=$reason"
}

/** No HAL exposure reference or display-domain target enters this controller. */
class BnAutoExposureEngine {
    private data class State(var decision: BnAutoExposureDecision, var lastTimestamp: Long = 0L,
        var observation: BnAutoObservation? = null, var stableSinceNs: Long = 0L,
        var lastMovingNs: Long = 0L, var movingCameraCeilingNs: Long? = null,
        var movingSubjectCeilingNs: Long? = null, var stabilityAnchor: BnAutoObservation? = null,
        var stabilitySecondaryAnchor: BnAutoObservation? = null)
    private val states = linkedMapOf<String, State>()

    @Synchronized
    fun reset() = states.clear()

    @Synchronized
    fun current(key: String, limits: BnAutoSensorLimits): BnAutoExposureDecision =
        states.getOrPut(key) {
            // A physically valid independent seed, never a Camera2 AE product.
            val exposure = 10_000_000L.coerceIn(limits.physicalBounds.minExposureNs,
                limits.physicalBounds.maxExposureNs)
            State(BnAutoExposureDecision(exposure, limits.bounds.minIso,
                maxOf(exposure, limits.minStreamFrameDurationNs), "BOOTSTRAP", 0f,
                "independent_manual_sensor_seed"))
        }.decision.also {
            // Pipeline generations are independent; bound retained diagnostic history.
            while (states.size > 8) states.remove(states.keys.first())
        }

    @Synchronized
    fun observe(
        key: String,
        limits: BnAutoSensorLimits,
        observation: BnAutoObservation?,
        flicker: RawFlickerConstraint = RawFlickerConstraint(),
        evBias: Float = 0f
    ): BnAutoExposureDecision {
        val previous = current(key, limits)
        val state = states.getValue(key)
        val o = observation
        if (o == null || !o.valid() || o.timestampNs <= state.lastTimestamp) {
            state.observation = null
            state.stableSinceNs = 0L
            state.stabilityAnchor = null
            state.stabilitySecondaryAnchor = null
            val safe = requireNotNull(DefaultRawExposureAllocator.allocate(previous.exposureProduct,
                minOf(previous.targetExposureNs, limits.fallbackHandheldNs), limits.physicalBounds, flicker,
                preferHeldFlickerShutter = false))
            return previous.copy(targetExposureNs = safe.exposureTimeNs, targetISO = safe.sensitivityIso,
                frameDurationNs = maxOf(safe.frameDurationNs, limits.minStreamFrameDurationNs).coerceAtMost(limits.maxFrameDurationNs),
                meteringConfidence = 0f, limitingConstraint = "MISSING_STABILITY_EVIDENCE",
                reason = "missing_invalid_or_duplicate_sensor_observation").also { state.decision = it }
        }
        state.lastTimestamp = o.timestampNs
        val priorObservation = state.observation
        val motionEvidence = BnAutoMotionEvidence.evaluate(priorObservation, o)
        state.observation = o
        if (motionEvidence == BnAutoMotionEvidence.Status.MOVING) {
            state.lastMovingNs = o.timestampNs
            state.movingCameraCeilingNs = o.cameraMotionCeilingNs.takeIf { o.cameraMotionConfidence >= 0.18f }
            state.movingSubjectCeilingNs = o.subjectMotionCeilingNs.takeIf { o.subjectMotionConfidence >= 0.18f }
        }
        // A subject reversing direction can look stationary for a frame. Release the short
        // shutter only after half a second without significant physical scene change.
        val holdMoving = state.lastMovingNs > 0 && o.timestampNs - state.lastMovingNs <= 500_000_000L
        var detailEvidence = "unavailable"
        val adjacentStability = BnAutoMotionEvidence.detailSafeCeiling(priorObservation, o) { detailEvidence = it }
        // This interval is only a candidate baseline; it grants no exposure authority by itself.
        // The long-baseline, two-axis detail test below must independently succeed on the current
        // frame. Requiring every noisy adjacent pair to pass would discard valid temporal evidence.
        state.stableSinceNs = if (motionEvidence == BnAutoMotionEvidence.Status.STABLE) {
            state.stableSinceNs.takeIf { it > 0L } ?: o.timestampNs
        } else 0L
        if (state.stableSinceNs == 0L) {
            state.stabilityAnchor = null
            state.stabilitySecondaryAnchor = null
        } else {
            if (state.stabilityAnchor == null) state.stabilityAnchor = o
            val anchorAge = o.timestampNs - state.stabilityAnchor!!.timestampNs
            if (anchorAge >= 750_000_000L && state.stabilitySecondaryAnchor == null) state.stabilitySecondaryAnchor = o
            if (anchorAge >= 1_500_000_000L) {
                state.stabilityAnchor = state.stabilitySecondaryAnchor
                state.stabilitySecondaryAnchor = null
            }
        }
        // Longer shutter requires a sustained, noise-qualified stability interval. A mathematical
        // flow confidence alone is insufficient when the RAW texture is dominated by sensor noise.
        val stabilitySpan = if (state.stableSinceNs > 0) o.timestampNs - state.stableSinceNs else 0L
        val product = o.exposureNs.toDouble() * o.iso
        // Scene key is radiance relative to an independent sensor integration, not a gray target.
        // Dark radiance therefore keeps a lower target; a fixed median-to-gray loop is avoided.
        val sceneKey = (o.p80 * 1_000_000_000.0 / product).coerceIn(0.000001, 4.0)
        // Recorded normal/lamp scenes left >2 EV diffuse highlight headroom. The original 0.6
        // coefficient unnecessarily spent that headroom while pushing shadows toward the noise
        // floor. A 1.0 coefficient recovers 0.74 EV; the radiance dependence and dark-scene floor
        // remain, so this is not a fixed gray target or a match-to-HAL correction.
        val naturalUpperMid = sqrt(sceneKey).coerceIn(0.015, 0.65)
        var desired = product * naturalUpperMid / o.p80.coerceAtLeast(1.0 / 65536)
        val noiseS = o.noiseSlope?.takeIf { it.isFinite() && it >= 0 }
        val noiseO = o.noiseOffset?.takeIf { it.isFinite() && it >= 0 }
        if (noiseS != null && noiseO != null && o.p20 > 0) {
            // Signal required for SNR=4 under this sensor's S*x+O model. Shadow benefit
            // is bounded by scene atmosphere and relevant highlight headroom.
            val snrSignal = (16 * noiseS + sqrt(256 * noiseS * noiseS + 64 * noiseO)) / 2
            val shadowGain = (snrSignal / o.p20).coerceIn(1.0, 2.0)
            desired = maxOf(desired, minOf(product * shadowGain,
                product * naturalUpperMid * 1.2 / o.p80.coerceAtLeast(1.0 / 65536)))
        }
        desired *= 2.0.pow(evBias.takeIf { it.isFinite() }?.coerceIn(-2f, 2f)?.toDouble() ?: 0.0)
        // Protect the brightest CFA channel of diffuse scene content, including colored surfaces.
        // The upper 2% is excluded so a small lamp cannot dictate the room's brightness.
        if (o.channelP98Maximum.isFinite() && o.channelP98Maximum > 0) {
            desired = minOf(desired, product * 0.85 / o.channelP98Maximum)
        }
        val broadHighlights = o.spatialHighlightFraction >= 0.08
        val clipping = o.channelClipFractions.maxOrNull() ?: 0.0
        if (broadHighlights && o.p98 > 0) desired = minOf(desired, product * 0.90 / o.p98)
        val urgentClip = clipping >= 0.025 && broadHighlights
        if (urgentClip) desired = minOf(desired, product * 0.5)
        val bounds = limits.physicalBounds
        var longDetailEvidence = "not_proven"
        val stabilityCeiling = if (stabilitySpan >= 750_000_000L && !holdMoving)
            BnAutoMotionEvidence.detailSafeCeiling(state.stabilityAnchor, o) { longDetailEvidence = it }
                ?.let { minOf(it, stabilitySpan / 2) }
            else null
        val suppressNoisyFlow = motionEvidence == BnAutoMotionEvidence.Status.NOISE_DOMINATED ||
            (motionEvidence == BnAutoMotionEvidence.Status.STABLE && adjacentStability != null)
        val cameraCeiling = if (holdMoving) state.movingCameraCeilingNs else stabilityCeiling ?:
            if (!suppressNoisyFlow && o.cameraMotionConfidence >= 0.18f) o.cameraMotionCeilingNs else null
        val subjectCeiling = if (holdMoving) state.movingSubjectCeilingNs else stabilityCeiling ?:
            if (!suppressNoisyFlow && o.subjectMotionConfidence >= 0.18f) o.subjectMotionCeilingNs else null
        // A long legacy flow ceiling is not positive stability evidence. Without the independently
        // observed full-resolution gradient bound, only shorten the lens handheld fallback.
        val cameraSafe = if (stabilityCeiling != null) cameraCeiling ?: limits.fallbackHandheldNs
            else minOf(adjacentStability ?: cameraCeiling ?: limits.fallbackHandheldNs, limits.fallbackHandheldNs)
        val ceiling = minOf(bounds.maxExposureNs, cameraSafe,
            subjectCeiling ?: bounds.maxExposureNs).coerceAtLeast(bounds.minExposureNs)
        val errorEv = log2(desired / previous.exposureProduct)
        val correction = if (abs(errorEv) < 0.08) 0.0 else errorEv.coerceIn(
            if (urgentClip) -2.0 else -0.7, 0.5)
        var allocation = requireNotNull(DefaultRawExposureAllocator.allocate(
            previous.exposureProduct * 2.0.pow(correction), ceiling, bounds, flicker,
            previousExposureNs = null, preferHeldFlickerShutter = false))
        val conservative = requireNotNull(DefaultRawExposureAllocator.allocate(
            allocation.boundedExposureProduct, minOf(ceiling, limits.fallbackHandheldNs), bounds, flicker,
            preferHeldFlickerShutter = false))
        fun predictedSnr(candidate: DefaultRawExposureAllocation): Double? {
            if (noiseS == null || noiseO == null) return null
            // Local gain-scaled S*x+O projection, not an invented sensor read-noise calibration.
            // At equal exposure product: shot coefficient scales with gain, read variance with gain².
            val gain = candidate.sensitivityIso.toDouble() / o.iso
            val shadowSignal = (o.p20 * candidate.realizedExposureProduct / product).coerceAtLeast(1e-6)
            return shadowSignal / sqrt(noiseS * gain * shadowSignal + noiseO * gain * gain + o.quantizationVariance)
        }
        val snrShort = predictedSnr(conservative)
        val snrLong = predictedSnr(allocation)
        val snrGain = if (snrShort != null && snrLong != null && snrShort > 0) snrLong / snrShort else null
        val snrRejected = allocation.exposureTimeNs > limits.fallbackHandheldNs && (snrGain == null || snrGain < 1.10)
        if (snrRejected) allocation = conservative
        val frameDuration = maxOf(allocation.exposureTimeNs, limits.minStreamFrameDurationNs,
            allocation.frameDurationNs).coerceAtMost(limits.maxFrameDurationNs)
        val result = BnAutoExposureDecision(allocation.exposureTimeNs, allocation.sensitivityIso,
            frameDuration,
            when {
                urgentClip -> "SENSOR_CLIPPING"
                snrRejected -> "NO_MEANINGFUL_PREDICTED_SNR_GAIN"
                stabilityCeiling != null && ceiling == stabilityCeiling -> "MEASURED_DETAIL_STABILITY"
                adjacentStability != null && ceiling == adjacentStability -> "RAW_DETAIL_MOTION_BOUND"
                subjectCeiling != null && ceiling == subjectCeiling -> "SUBJECT_MOTION"
                cameraCeiling != null && ceiling == cameraCeiling -> "CAMERA_MOTION"
                cameraCeiling == null && allocation.exposureTimeNs >= ceiling -> "LENS_STABILITY_FALLBACK"
                allocation.sensitivityIso > limits.analogLimitIso -> "ABOVE_ANALOG_GAIN_LIMIT"
                else -> allocation.limitingConstraint
            }, if (noiseS != null && noiseO != null) 0.95f else 0.7f,
            "sensor_radiance_key;bounded_feedback;photon_first;noiseModel=${noiseS != null && noiseO != null};motionEvidence=$motionEvidence;" +
                "detailSafeCeilingNs=$stabilityCeiling;detailEvidence=$detailEvidence;" +
                "stabilitySpanNs=$stabilitySpan;longDetailEvidence=$longDetailEvidence;" +
                "predictedShadowSnrShort=$snrShort;predictedShadowSnrLong=$snrLong;predictedSnrGain=$snrGain")
        state.decision = result
        return result
    }
}

/** Significance test in the physical RAW domain; camera/subject flow remains the motion estimator. */
object BnAutoMotionEvidence {
    enum class Status { UNAVAILABLE, MOVING, STABLE, NOISE_DOMINATED }

    /** Noise-weighted displacement bound in full sensor pixels, never compact-grid pixels.
     * Both axes and at least eight scene regions must contain significant local gradients.
     * Flat/noisy scenes and the aperture problem deliberately cannot authorize a long shutter.
     */
    fun detailSafeCeiling(previous: BnAutoObservation?, current: BnAutoObservation,
        diagnostic: ((String) -> Unit)? = null): Long? {
        val prior = previous ?: return null
        val a = prior.sensorLumaSamples ?: return null
        val b = current.sensorLumaSamples ?: return null
        val gx = prior.sensorGradientX ?: return null
        val gy = prior.sensorGradientY ?: return null
        val s0 = prior.noiseSlope ?: return null
        val o0 = prior.noiseOffset ?: return null
        val s1 = current.noiseSlope ?: return null
        val o1 = current.noiseOffset ?: return null
        val dt = current.timestampNs - prior.timestampNs
        if (a.size != 3072 || b.size != a.size || gx.size != a.size || gy.size != a.size ||
            dt !in 5_000_000L..2_000_000_000L || listOf(s0, o0, s1, o1).any { !it.isFinite() || it < 0 }) return null
        val ratio = current.exposureNs.toDouble() * current.iso / (prior.exposureNs.toDouble() * prior.iso)
        if (!ratio.isFinite() || ratio !in 0.25..4.0) return null
        var xx = 0.0; var yy = 0.0; var xy = 0.0; var xd = 0.0; var yd = 0.0
        val regions = BooleanArray(12)
        var count = 0
        var changed = 0
        for (i in a.indices) {
            val expected = a[i] * ratio
            if (!expected.isFinite() || !b[i].isFinite() || expected !in 0.0..0.85 || b[i] !in 0.0..0.85) continue
            val variance = ((s1 * b[i] + o1 + current.quantizationVariance) +
                ratio * ratio * (s0 * a[i] + o0 + prior.quantizationVariance)) / 4
            val x = gx[i] * ratio; val y = gy[i] * ratio
            if (!x.isFinite() || !y.isFinite() || variance <= 0) continue
            // Two independent CFA-cell means, each averaging four sites, divided by 4 pixels.
            val gradientNoise = ratio * ratio * (s0 * a[i] + o0 + prior.quantizationVariance) / 32
            if (x * x + y * y < 8 * gradientNoise) continue
            val delta = b[i] - expected
            if (abs(delta) > 3 * sqrt(variance)) changed++
            xx += x * x / variance; yy += y * y / variance; xy += x * y / variance
            xd += x * delta / variance; yd += y * delta / variance
            regions[(i / 64 / 16) * 4 + (i % 64 / 16)] = true
            count++
        }
        diagnostic?.invoke("samples:$count,regions:${regions.count { it }},changed:$changed")
        if (count < 128 || regions.count { it } < 8 || changed.toDouble() / count > 0.02) return null
        val determinant = xx * yy - xy * xy
        val smallestEigenvalue = (xx + yy - sqrt((xx - yy) * (xx - yy) + 4 * xy * xy)) / 2
        if (determinant <= 0 || smallestEigenvalue < 25) return null
        val dx = (yy * xd - xy * yd) / determinant
        val dy = (xx * yd - xy * xd) / determinant
        // 99% displacement uncertainty plus measured drift, with a <1 sensor-pixel detail budget.
        val displacementUpper = sqrt(dx * dx + dy * dy) + 2.58 * sqrt((xx + yy) / determinant)
        if (!displacementUpper.isFinite() || displacementUpper <= 0) return null
        return (0.75 * dt / displacementUpper).toLong().takeIf { it > 0 }
    }

    fun evaluate(previous: BnAutoObservation?, current: BnAutoObservation): Status {
        val prior = previous ?: return Status.UNAVAILABLE
        val a = prior.sensorLumaSamples ?: return Status.UNAVAILABLE
        val b = current.sensorLumaSamples ?: return Status.UNAVAILABLE
        val s0 = prior.noiseSlope ?: return Status.UNAVAILABLE
        val o0 = prior.noiseOffset ?: return Status.UNAVAILABLE
        val s1 = current.noiseSlope ?: return Status.UNAVAILABLE
        val o1 = current.noiseOffset ?: return Status.UNAVAILABLE
        if (a.size != b.size || a.size < 128 || listOf(s0, o0, s1, o1).any { !it.isFinite() || it < 0 } ||
            current.timestampNs - prior.timestampNs !in 1..500_000_000L) return Status.UNAVAILABLE
        val ratio = (current.exposureNs.toDouble() * current.iso) / (prior.exposureNs.toDouble() * prior.iso)
        if (!ratio.isFinite() || ratio !in 0.25..4.0) return Status.UNAVAILABLE
        var changed = 0
        var count = 0
        var sum = 0.0
        var sumSq = 0.0
        var noise = 0.0
        for (i in b.indices) {
            val expected = a[i] * ratio
            val actual = b[i]
            if (!actual.isFinite() || !expected.isFinite() || actual >= 0.95 || expected >= 0.95) continue
            // Each sample is the mean of four CFA sites. Quantization is included in both frames.
            val variance = ((s1 * actual + o1) + ratio * ratio * (s0 * a[i] + o0)) / 4 + 2.0 / (1023 * 1023)
            if (abs(actual - expected) > 3 * sqrt(variance)) changed++
            sum += actual
            sumSq += actual * actual
            noise += (s1 * actual + o1) / 4
            count++
        }
        if (count < 128) return Status.UNAVAILABLE
        if (changed.toDouble() / count > 0.02) return Status.MOVING
        val variance = (sumSq / count - (sum / count).pow(2)).coerceAtLeast(0.0)
        return if (variance > 4 * noise / count && variance > 4.0 / (1023 * 1023)) Status.STABLE
            else Status.NOISE_DOMINATED
    }
}
