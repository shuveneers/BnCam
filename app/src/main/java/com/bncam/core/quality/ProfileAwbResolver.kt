package com.bncam.core.quality

import com.bncam.data.settings.ProfileAwbModels
import com.bncam.data.settings.ProfileAwbModes
import com.bncam.data.settings.ProfileAwbSettings
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

data class ResolvedProfileAwb(
    val gains: FloatArray,
    val targetNeutralXyz: FloatArray,
    val source: String,
    val profileOverrideApplied: Boolean
)

/**
 * Resolves the profile AWB target into bounded channel gains. AWB intensity is deliberately a
 * single owner for every mode: 100% reproduces the normal resolved correction, lower values reduce
 * it and values up to 150% strengthen it. Positive gains are interpolated/extrapolated in log space.
 */
object ProfileAwbResolver {
    fun resolve(baseCameraGains: FloatArray, settings: ProfileAwbSettings): ResolvedProfileAwb {
        val safe = settings.sanitized()
        val base = baseCameraGains.takeIf { it.size >= 4 && it.all { gain -> gain.isFinite() && gain > 0f } }
            ?: floatArrayOf(1f, 1f, 1f, 1f)
        val target = xyForKelvin(safe.kelvin.toDouble(), safe.illuminantModel)
        val targetXyz = xyToXyz(target.first, target.second)
        val intensity = safe.referenceIntensity.coerceIn(0f, 1.5f)

        if (safe.mode == ProfileAwbModes.SYSTEM_AUTO) {
            val gains = logBlendGains(
                from = floatArrayOf(1f, 1f, 1f, 1f),
                to = base,
                amount = intensity
            )
            return ResolvedProfileAwb(
                gains = gains,
                targetNeutralXyz = targetXyz,
                source = "Profile AWB System / Auto (CaptureResult Camera2 per-frame gains) / intensity=${safe.referenceIntensity}",
                profileOverrideApplied = kotlin.math.abs(intensity - 1f) > 0.001f
            )
        }

        val targetRgb = xyzToLinearSrgb(targetXyz)
        val green = targetRgb[1].coerceAtLeast(1.0e-4f)
        val reference = floatArrayOf(
            (green / targetRgb[0].coerceAtLeast(1.0e-4f)).coerceIn(0.25f, 4f),
            1f,
            1f,
            (green / targetRgb[2].coerceAtLeast(1.0e-4f)).coerceIn(0.25f, 4f)
        )
        val tintGreenScale = exp((-safe.tint * 0.22f).toDouble()).toFloat().coerceIn(0.75f, 1.33f)
        reference[1] = tintGreenScale
        reference[2] = tintGreenScale

        // 0% preserves Camera2 AWB; 100% applies the selected manual/reference target; >100%
        // extrapolates the same correction vector, bounded per channel.
        val gains = logBlendGains(base, reference, intensity)
        val source = when (safe.mode) {
            ProfileAwbModes.BRAND_REFERENCE ->
                "Profile AWB ${safe.brand} reference / ${safe.preset} / ${safe.kelvin}K / ${safe.illuminantModel} / tint=${safe.tint} / intensity=${safe.referenceIntensity}"
            else ->
                "Profile AWB Manual Kelvin ${safe.kelvin}K / ${safe.illuminantModel} / tint=${safe.tint} / intensity=${safe.referenceIntensity}"
        }
        return ResolvedProfileAwb(
            gains = gains,
            targetNeutralXyz = targetXyz,
            source = source,
            profileOverrideApplied = intensity > 0.001f
        )
    }

    private fun logBlendGains(from: FloatArray, to: FloatArray, amount: Float): FloatArray =
        FloatArray(4) { index ->
            val start = from.getOrElse(index) { 1f }.coerceIn(0.25f, 4f)
            val target = to.getOrElse(index) { 1f }.coerceIn(0.25f, 4f)
            exp(
                ln(start.toDouble()) + amount.toDouble() *
                    (ln(target.toDouble()) - ln(start.toDouble()))
            ).toFloat().coerceIn(0.25f, 4f)
        }

    private fun xyForKelvin(kelvin: Double, model: String): Pair<Double, Double> {
        val t = kelvin.coerceIn(2000.0, 10000.0)
        return if (model == ProfileAwbModels.CIE_DAYLIGHT && t >= 4000.0) {
            val x = if (t <= 7000.0) {
                -4.6070e9 / t.pow(3) + 2.9678e6 / t.pow(2) + 0.09911e3 / t + 0.244063
            } else {
                -2.0064e9 / t.pow(3) + 1.9018e6 / t.pow(2) + 0.24748e3 / t + 0.237040
            }
            x to (-3.0 * x * x + 2.87 * x - 0.275)
        } else {
            val x = when {
                t <= 4000.0 -> -0.2661239e9 / t.pow(3) - 0.2343580e6 / t.pow(2) + 0.8776956e3 / t + 0.179910
                else -> -3.0258469e9 / t.pow(3) + 2.1070379e6 / t.pow(2) + 0.2226347e3 / t + 0.240390
            }
            val y = when {
                t <= 2222.0 -> -1.1063814 * x.pow(3) - 1.34811020 * x.pow(2) + 2.18555832 * x - 0.20219683
                t <= 4000.0 -> -0.9549476 * x.pow(3) - 1.37418593 * x.pow(2) + 2.09137015 * x - 0.16748867
                else -> 3.0817580 * x.pow(3) - 5.87338670 * x.pow(2) + 3.75112997 * x - 0.37001483
            }
            x to y
        }
    }

    private fun xyToXyz(x: Double, y: Double): FloatArray {
        val safeY = y.coerceAtLeast(1.0e-6)
        return floatArrayOf((x / safeY).toFloat(), 1f, ((1.0 - x - y) / safeY).toFloat())
    }

    private fun xyzToLinearSrgb(xyz: FloatArray): FloatArray = floatArrayOf(
        3.2404542f * xyz[0] - 1.5371385f * xyz[1] - 0.4985314f * xyz[2],
        -0.9692660f * xyz[0] + 1.8760108f * xyz[1] + 0.0415560f * xyz[2],
        0.0556434f * xyz[0] - 0.2040259f * xyz[1] + 1.0572252f * xyz[2]
    )
}
