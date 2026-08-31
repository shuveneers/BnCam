package com.bncam.core.quality

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.ColorSpaceTransform
import com.bncam.data.settings.ProfileAwbSettings
import kotlin.math.abs
import kotlin.math.exp

data class TargetChromaticity(
    val x: Float,
    val y: Float,
    val targetKelvin: Int,
    val locusModel: String
) {
    fun toXyz(): FloatArray {
        val safeY = if (abs(y) < 0.0001f) 0.0001f else y
        val X = x / safeY
        val Y = 1.0f
        val Z = (1.0f - x - safeY) / safeY
        return floatArrayOf(X, Y, Z)
    }
}



data class ActualSensorForwardMatrixResult(
    val values: FloatArray?,
    val deviceCalibrationApplied: Boolean,
    val calibrationLabel: String
)

data class SensorColorMatrixValidation(
    val valid: Boolean,
    val score: Float,
    val reason: String,
    val determinant: Float,
    val rowSums: FloatArray,
    val neutralAxisDeviation: Float,
    val maxAbs: Float,
    val negativeEnergy: Float
)
data class ResolvedColorTransformResult(
    val targetKelvin: Int,
    val illuminantModel: String,
    val chromaticity: TargetChromaticity,
    val cameraNeutralRgb: FloatArray, // [N_R, N_G, N_B]
    val bayerWbGains: FloatArray,     // [gR, gGr, gGb, gB]
    val w3Diagonal: FloatArray,       // [gR, 1.0, gB]
    val mTotal: FloatArray,           // 3x3 sensor/reference RGB -> linear sRGB matrix before explicit WB diagonal
    val mPostCompensated: FloatArray, // 3x3 linear-sRGB matrix compensated for WB (M_total * W3^-1)
    val interpolationWeight: Float,
    val illuminant1Kelvin: Int,
    val illuminant2Kelvin: Int,
    val isValid: Boolean,
    val rejectionReason: String = "none"
)

object RawColorTransformEngine {

    // Camera2 SENSOR_FORWARD_MATRIX maps white-balanced reference sensor values to CIE XYZ
    // with a D50 white point. BnCam's native ISP expects its CCM output to be linear sRGB
    // (D65), so the forward-matrix path must explicitly adapt D50 -> D65 and then convert
    // XYZ D65 -> linear sRGB. Keeping this conversion here prevents manual/profile Kelvin
    // WB from accidentally feeding XYZ values into an RGB pipeline.
    private val BRADFORD_D50_TO_D65 = floatArrayOf(
        0.9555766f, -0.0230393f, 0.0631636f,
        -0.0282895f, 1.0099416f, 0.0210077f,
        0.0122982f, -0.0204830f, 1.3299098f
    )

    private val XYZ_D65_TO_LINEAR_SRGB = floatArrayOf(
        3.2404542f, -1.5371385f, -0.4985314f,
        -0.9692660f, 1.8760108f, 0.0415560f,
        0.0556434f, -0.2040259f, 1.0572252f
    )

    /** Returns the explicit CIE XYZ D50 -> linear-sRGB (D65) conversion used after ForwardMatrix. */
    internal fun xyzD50ToLinearSrgbMatrix(): FloatArray =
        multiply3x3(XYZ_D65_TO_LINEAR_SRGB, BRADFORD_D50_TO_D65)
            ?: identityArray()

    /**
     * Shared sensor-RGB -> linear-sRGB matrix validity contract. Row-sum/neutral-axis metrics are
     * diagnostic only: BnCam must never force a camera matrix toward neutral by mutating its rows.
     */
    fun validateSensorToLinearSrgbMatrix(values: FloatArray): SensorColorMatrixValidation {
        val emptyRows = floatArrayOf(Float.NaN, Float.NaN, Float.NaN)
        if (values.size != 9) {
            return SensorColorMatrixValidation(false, Float.MAX_VALUE, "expected_9_values", Float.NaN, emptyRows, Float.NaN, Float.NaN, Float.NaN)
        }
        if (values.any { !it.isFinite() }) {
            return SensorColorMatrixValidation(false, Float.MAX_VALUE, "non_finite_matrix_value", Float.NaN, emptyRows, Float.NaN, Float.NaN, Float.NaN)
        }
        val rowSums = FloatArray(3) { row ->
            values[row * 3] + values[row * 3 + 1] + values[row * 3 + 2]
        }
        val rowAbs = FloatArray(3) { row ->
            abs(values[row * 3]) + abs(values[row * 3 + 1]) + abs(values[row * 3 + 2])
        }
        val maxAbs = values.maxOf { abs(it) }
        // Android explicitly allows device-dependent CCM coefficient ranges. Keep only a broad
        // catastrophic-data guard; do not reject legitimate sensors because their calibrated
        // coefficients fall outside a BnCam tuning window.
        if (rowAbs.any { !it.isFinite() || it < 1.0e-5f } || !maxAbs.isFinite() || maxAbs > 64.0f) {
            return SensorColorMatrixValidation(false, Float.MAX_VALUE, "degenerate_or_catastrophic_matrix", Float.NaN, rowSums, Float.NaN, maxAbs, Float.NaN)
        }
        val det = values[0] * (values[4] * values[8] - values[5] * values[7]) -
            values[1] * (values[3] * values[8] - values[5] * values[6]) +
            values[2] * (values[3] * values[7] - values[4] * values[6])
        if (!det.isFinite() || abs(det) < 1.0e-6f) {
            return SensorColorMatrixValidation(false, Float.MAX_VALUE, "singular_matrix", det, rowSums, Float.NaN, maxAbs, Float.NaN)
        }
        val meanRowSum = rowSums.average().toFloat()
        val neutralDeviation = if (abs(meanRowSum) > 1.0e-6f) {
            rowSums.maxOf { abs(it - meanRowSum) } / abs(meanRowSum)
        } else {
            Float.POSITIVE_INFINITY
        }
        val negativeEnergy = values.filter { it < 0f }.sumOf { abs(it).toDouble() }.toFloat()
        val score = negativeEnergy * 0.08f + abs(maxAbs - 1.0f) * 0.04f
        return SensorColorMatrixValidation(
            valid = true,
            score = score,
            reason = "valid",
            determinant = det,
            rowSums = rowSums,
            neutralAxisDeviation = neutralDeviation,
            maxAbs = maxAbs,
            negativeEnergy = negativeEnergy
        )
    }

    fun resolveActualSensorForwardMatrixToLinearSrgb(
        forwardMatrix: FloatArray?,
        calibrationTransform: FloatArray?
    ): ActualSensorForwardMatrixResult {
        val forward = forwardMatrix
            ?.takeIf(::isFiniteMatrix)
            ?.copyOf()
            ?: return ActualSensorForwardMatrixResult(null, false, "forward_matrix_missing_or_invalid")
        // ForwardMatrix is defined in REFERENCE-sensor space, while the RAW payload is in the
        // ACTUAL device-sensor space. Android couples the calibration matrices to the reference
        // illuminants; without CalibrationTransform we cannot truthfully bridge those domains.
        // Reject the static fallback instead of silently assuming reference==actual.
        val calibration = calibrationTransform
            ?: return ActualSensorForwardMatrixResult(null, false, "calibration_transform_missing")
        if (!isFiniteMatrix(calibration)) {
            return ActualSensorForwardMatrixResult(null, false, "calibration_transform_invalid")
        }
        val actualSensorToReference = invert3x3(calibration)
            ?: return ActualSensorForwardMatrixResult(null, false, "calibration_transform_noninvertible")
        val referenceToXyzD50 = multiply3x3(forward, actualSensorToReference)
            ?: return ActualSensorForwardMatrixResult(null, true, "matrix_multiply_failed")
        val linearSrgb = multiply3x3(xyzD50ToLinearSrgbMatrix(), referenceToXyzD50)
            ?.takeIf { validateSensorToLinearSrgbMatrix(it).valid }
        return ActualSensorForwardMatrixResult(
            values = linearSrgb,
            deviceCalibrationApplied = true,
            calibrationLabel = if (linearSrgb == null) {
                "linear_srgb_matrix_out_of_bounds"
            } else {
                "inverse_device_calibration_applied"
            }
        )
    }

    /**
     * Resolves a profile/manual Kelvin target in the sensor's native color space.
     * Unlike the old sRGB-only profile resolver, this uses the Camera2 calibration matrices
     * and reference illuminants for the active physical sensor. The returned post matrix is
     * explicitly compensated for the WB diagonal so WB is applied exactly once.
     */
    fun computeProfileWhiteBalance(
        characteristics: CameraCharacteristics,
        settings: ProfileAwbSettings
    ): ResolvedColorTransformResult {
        val safe = settings.sanitized()
        val base = computeOptionBMatrices(
            characteristics = characteristics,
            targetKelvin = safe.kelvin,
            illuminantModel = safe.illuminantModel
        )
        if (!base.isValid) return base

        // Tint is a bounded creative green/magenta trim around the physically-derived Kelvin point.
        // Keep it deliberately narrow: tint must never be able to recreate the large green/magenta
        // casts that the sensor-aware WB model is intended to prevent.
        val greenGain = exp((-safe.tint * 0.10f).toDouble()).toFloat().coerceIn(0.90f, 1.11f)
        val gR = base.bayerWbGains[0].coerceIn(0.10f, 12.0f)
        val gB = base.bayerWbGains[3].coerceIn(0.10f, 12.0f)
        val bayer = floatArrayOf(gR, greenGain, greenGain, gB)
        val w3 = floatArrayOf(gR, greenGain, gB)
        val invW3 = diagonal3x3(floatArrayOf(1f / gR, 1f / greenGain, 1f / gB))
        val post = multiply3x3(base.mTotal, invW3) ?: base.mPostCompensated
        val valid = bayer.all { it.isFinite() && it in 0.10f..12.0f } &&
            post.size == 9 && post.all { it.isFinite() && abs(it) < 32f }

        return base.copy(
            bayerWbGains = bayer,
            w3Diagonal = w3,
            mPostCompensated = post,
            isValid = valid,
            rejectionReason = if (valid) "none" else "profile_wb_solution_out_of_bounds"
        )
    }

    fun mapExifIlluminantToKelvin(illuminantCode: Int): Int = when (illuminantCode) {
        17 -> 2856 // Standard Light A
        18 -> 4874 // Standard Light B
        19 -> 6774 // Standard Light C
        20 -> 5500 // D55
        21 -> 6504 // D65
        22 -> 7500 // D75
        23 -> 5000 // D50
        24 -> 3200 // ISO Studio Tungsten
        1 -> 5500  // Daylight
        2 -> 4200  // Fluorescent
        3 -> 2856  // Tungsten
        4 -> 5500  // Flash
        10 -> 6500 // Cloudy
        11 -> 7500 // Shade
        else -> 5500
    }

    fun calculateChromaticity(kelvin: Int, illuminantModel: String): TargetChromaticity {
        val clampedT = kelvin.coerceIn(1667, 25000)
        // Stored profile values are human-readable ("CIE Daylight",
        // "Planckian Blackbody"). Normalize separators before matching so an explicitly
        // selected model is never silently replaced by the temperature-based fallback.
        val normalizedModel = illuminantModel
            .trim()
            .uppercase()
            .replace(' ', '_')
            .replace('-', '_')
        return when (normalizedModel) {
            "CIE_DAYLIGHT" -> daylightChromaticity(clampedT)
            "PLANCKIAN_BLACKBODY" -> planckianChromaticity(clampedT)
            else -> if (clampedT < 4000) planckianChromaticity(clampedT) else daylightChromaticity(clampedT)
        }
    }

    private fun planckianChromaticity(kelvin: Int): TargetChromaticity {
        val T = kelvin.toDouble()
        val T2 = T * T
        val T3 = T2 * T

        val x = -0.2661239 * (1.0e9 / T3) - 0.2343580 * (1.0e6 / T2) + 0.8776956 * (1.0e3 / T) + 0.179910

        val y = if (kelvin <= 2222) {
            val x2 = x * x
            val x3 = x2 * x
            -1.1063814 * x3 - 1.34811020 * x2 + 2.18555832 * x - 0.20219683
        } else {
            val x2 = x * x
            val x3 = x2 * x
            -0.95494760 * x3 - 1.37418593 * x2 + 2.09137015 * x - 0.16748867
        }

        return TargetChromaticity(
            x = x.toFloat(),
            y = y.toFloat(),
            targetKelvin = kelvin,
            locusModel = "Planckian Blackbody"
        )
    }

    private fun daylightChromaticity(kelvin: Int): TargetChromaticity {
        val T = kelvin.toDouble()
        val T2 = T * T
        val T3 = T2 * T

        val x = if (kelvin <= 7000) {
            -4.6070 * (1.0e9 / T3) + 2.9678 * (1.0e6 / T2) + 0.09911 * (1.0e3 / T) + 0.244063
        } else {
            -2.0064 * (1.0e9 / T3) + 1.9018 * (1.0e6 / T2) + 0.24748 * (1.0e3 / T) + 0.237040
        }

        val y = -3.000 * x * x + 2.870 * x - 0.275

        return TargetChromaticity(
            x = x.toFloat(),
            y = y.toFloat(),
            targetKelvin = kelvin,
            locusModel = "CIE Daylight"
        )
    }

    fun computeOptionBMatrices(
        characteristics: CameraCharacteristics,
        targetKelvin: Int,
        illuminantModel: String
    ): ResolvedColorTransformResult {
        val illum1Code = characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)?.toInt() ?: 21
        val illum2Code = characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt()
        val t1 = mapExifIlluminantToKelvin(illum1Code)
        val t2 = illum2Code?.let { mapExifIlluminantToKelvin(it) } ?: t1

        val m1 = 1.0f / t1.toFloat()
        val m2 = 1.0f / t2.toFloat()
        val mTarget = 1.0f / targetKelvin.toFloat()

        val weight = if (abs(m1 - m2) < 1.0e-7f) {
            0.0f
        } else {
            ((mTarget - m1) / (m2 - m1)).coerceIn(0.0f, 1.0f)
        }

        val cm1 = characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)?.let { colorSpaceTransformToArray(it) }
        val cm2 = characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)?.let { colorSpaceTransformToArray(it) }
        val cm = interpolateMatrices(cm1, cm2, weight)

        val fm1 = characteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)?.let { colorSpaceTransformToArray(it) }
        val fm2 = characteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)?.let { colorSpaceTransformToArray(it) }
        val fm = interpolateMatrices(fm1, fm2, weight)

        // A manual Kelvin solution is only trustworthy when the HAL exposes the two matrix
        // domains required by the DNG/Camera2 color model. Identity fallback here is dangerous:
        // it would reinterpret sensor or XYZ channels as RGB and can create strong green/magenta
        // casts. Callers already fall back to Camera2 Auto when isValid=false.
        if (cm == null || fm == null || !isFiniteMatrix(cm) || !isFiniteMatrix(fm)) {
            return invalidResult(
                targetKelvin = targetKelvin,
                illuminantModel = illuminantModel,
                t1 = t1,
                t2 = t2,
                weight = weight,
                reason = "missing_or_invalid_sensor_color_forward_matrix"
            )
        }

        val cc1 = characteristics.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)?.let { colorSpaceTransformToArray(it) }
        val cc2 = characteristics.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)?.let { colorSpaceTransformToArray(it) }
        val cc = interpolateMatrices(cc1, cc2, weight)?.takeIf(::isFiniteMatrix) ?: identityArray()

        val ab = identityArray()
        val abCc = multiply3x3(ab, cc) ?: identityArray()

        val xyzToCamera = multiply3x3(abCc, cm) ?: identityArray()
        val chromaticity = calculateChromaticity(targetKelvin, illuminantModel)
        val xyzTarget = chromaticity.toXyz()

        val cameraNeutral = multiplyMatrixVector(xyzToCamera, xyzTarget)
        if (cameraNeutral.any { !it.isFinite() || it <= 1.0e-4f }) {
            return invalidResult(
                targetKelvin = targetKelvin,
                illuminantModel = illuminantModel,
                t1 = t1,
                t2 = t2,
                weight = weight,
                reason = "non_positive_or_invalid_camera_neutral"
            )
        }
        val safeNr = cameraNeutral[0]
        val safeNg = cameraNeutral[1]
        val safeNb = cameraNeutral[2]

        val rawGR = safeNg / safeNr
        val rawGB = safeNg / safeNb
        if (!rawGR.isFinite() || !rawGB.isFinite() || rawGR !in 0.10f..12.0f || rawGB !in 0.10f..12.0f) {
            return invalidResult(
                targetKelvin = targetKelvin,
                illuminantModel = illuminantModel,
                t1 = t1,
                t2 = t2,
                weight = weight,
                reason = "sensor_wb_gains_out_of_bounds"
            )
        }
        val gR = rawGR
        val gB = rawGB
        val bayerGains = floatArrayOf(gR, 1.0f, 1.0f, gB)
        val w3Diag = floatArrayOf(gR, 1.0f, gB)

        val invAbCc = invert3x3(abCc) ?: identityArray()
        val refNeutral = multiplyMatrixVector(invAbCc, floatArrayOf(safeNr, safeNg, safeNb))
        val invRefNeutral = floatArrayOf(
            if (abs(refNeutral[0]) < 0.0001f) 1f else 1f / refNeutral[0],
            if (abs(refNeutral[1]) < 0.0001f) 1f else 1f / refNeutral[1],
            if (abs(refNeutral[2]) < 0.0001f) 1f else 1f / refNeutral[2]
        )

        val D = diagonal3x3(invRefNeutral)
        val cameraToXyzD50 = multiply3x3(fm, multiply3x3(D, invAbCc) ?: identityArray())
            ?: return invalidResult(
                targetKelvin = targetKelvin,
                illuminantModel = illuminantModel,
                t1 = t1,
                t2 = t2,
                weight = weight,
                reason = "camera_to_xyz_matrix_failed"
            )
        val xyzD50ToLinearSrgb = xyzD50ToLinearSrgbMatrix()
        val mTotal = multiply3x3(xyzD50ToLinearSrgb, cameraToXyzD50)
            ?: return invalidResult(
                targetKelvin = targetKelvin,
                illuminantModel = illuminantModel,
                t1 = t1,
                t2 = t2,
                weight = weight,
                reason = "xyz_d50_to_linear_srgb_matrix_failed"
            )

        val invW3 = diagonal3x3(floatArrayOf(1.0f / gR, 1.0f, 1.0f / gB))
        val mPostCompensated = multiply3x3(mTotal, invW3)
            ?: return invalidResult(
                targetKelvin = targetKelvin,
                illuminantModel = illuminantModel,
                t1 = t1,
                t2 = t2,
                weight = weight,
                reason = "wb_matrix_compensation_failed"
            )

        val matrixValid = validateSensorToLinearSrgbMatrix(mTotal).valid &&
            validateSensorToLinearSrgbMatrix(mPostCompensated).valid
        if (!matrixValid) {
            return invalidResult(
                targetKelvin = targetKelvin,
                illuminantModel = illuminantModel,
                t1 = t1,
                t2 = t2,
                weight = weight,
                reason = "linear_srgb_matrix_out_of_bounds"
            )
        }

        return ResolvedColorTransformResult(
            targetKelvin = targetKelvin,
            illuminantModel = chromaticity.locusModel,
            chromaticity = chromaticity,
            cameraNeutralRgb = floatArrayOf(safeNr, safeNg, safeNb),
            bayerWbGains = bayerGains,
            w3Diagonal = w3Diag,
            mTotal = mTotal,
            mPostCompensated = mPostCompensated,
            interpolationWeight = weight,
            illuminant1Kelvin = t1,
            illuminant2Kelvin = t2,
            isValid = true
        )
    }

    fun verifyOptionBEquivalence(mTotal: FloatArray, mPost: FloatArray, w3: FloatArray, sampleVector: FloatArray, tolerance: Float = 1.0e-4f): Boolean {
        require(mTotal.size == 9 && mPost.size == 9 && w3.size == 3 && sampleVector.size == 3)
        val lhs = multiplyMatrixVector(mTotal, sampleVector)

        val w3P = floatArrayOf(sampleVector[0] * w3[0], sampleVector[1] * w3[1], sampleVector[2] * w3[2])
        val rhs = multiplyMatrixVector(mPost, w3P)

        for (i in 0..2) {
            if (abs(lhs[i] - rhs[i]) > tolerance) return false
        }
        return true
    }

    private fun invalidResult(
        targetKelvin: Int,
        illuminantModel: String,
        t1: Int,
        t2: Int,
        weight: Float,
        reason: String
    ): ResolvedColorTransformResult {
        val chromaticity = calculateChromaticity(targetKelvin, illuminantModel)
        return ResolvedColorTransformResult(
            targetKelvin = targetKelvin,
            illuminantModel = chromaticity.locusModel,
            chromaticity = chromaticity,
            cameraNeutralRgb = floatArrayOf(1f, 1f, 1f),
            bayerWbGains = floatArrayOf(1f, 1f, 1f, 1f),
            w3Diagonal = floatArrayOf(1f, 1f, 1f),
            mTotal = identityArray(),
            mPostCompensated = identityArray(),
            interpolationWeight = weight,
            illuminant1Kelvin = t1,
            illuminant2Kelvin = t2,
            isValid = false,
            rejectionReason = reason
        )
    }

    private fun isFiniteMatrix(m: FloatArray): Boolean =
        m.size == 9 && m.all { it.isFinite() && abs(it) < 64f }

    private fun interpolateMatrices(m1: FloatArray?, m2: FloatArray?, weight: Float): FloatArray? {
        if (m1 == null && m2 == null) return null
        if (m1 != null && m2 == null) return m1
        if (m1 == null && m2 != null) return m2
        requireNotNull(m1); requireNotNull(m2)
        if (m1.size != 9 || m2.size != 9) return null
        val out = FloatArray(9)
        for (i in 0..8) {
            out[i] = (1.0f - weight) * m1[i] + weight * m2[i]
        }
        return out
    }

    fun colorSpaceTransformToArray(transform: ColorSpaceTransform): FloatArray {
        val values = FloatArray(9)
        for (row in 0..2) {
            for (column in 0..2) {
                val rational = transform.getElement(column, row)
                val denominator = rational.denominator
                // A malformed rational must invalidate the matrix. Substituting identity here
                // would silently manufacture calibration data and can create route-dependent color.
                values[row * 3 + column] = if (denominator == 0) {
                    Float.NaN
                } else {
                    rational.numerator.toFloat() / denominator.toFloat()
                }
            }
        }
        return values
    }

    fun multiply3x3(a: FloatArray, b: FloatArray): FloatArray? {
        if (a.size != 9 || b.size != 9) return null
        val out = FloatArray(9)
        for (row in 0..2) {
            for (column in 0..2) {
                var sum = 0f
                for (k in 0..2) {
                    sum += a[row * 3 + k] * b[k * 3 + column]
                }
                out[row * 3 + column] = sum
            }
        }
        return out
    }

    fun multiplyMatrixVector(m: FloatArray, v: FloatArray): FloatArray {
        require(m.size == 9 && v.size == 3)
        return floatArrayOf(
            m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
            m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
            m[6] * v[0] + m[7] * v[1] + m[8] * v[2]
        )
    }

    fun invert3x3(m: FloatArray): FloatArray? {
        if (m.size != 9 || m.any { !it.isFinite() }) return null
        val det = m[0] * (m[4] * m[8] - m[5] * m[7]) -
                m[1] * (m[3] * m[8] - m[5] * m[6]) +
                m[2] * (m[3] * m[7] - m[4] * m[6])
        if (!det.isFinite() || abs(det) < 0.00001f) return null
        val invDet = 1f / det
        return floatArrayOf(
            (m[4] * m[8] - m[5] * m[7]) * invDet,
            (m[2] * m[7] - m[1] * m[8]) * invDet,
            (m[1] * m[5] - m[2] * m[4]) * invDet,
            (m[5] * m[6] - m[3] * m[8]) * invDet,
            (m[0] * m[8] - m[2] * m[6]) * invDet,
            (m[2] * m[3] - m[0] * m[5]) * invDet,
            (m[3] * m[7] - m[4] * m[6]) * invDet,
            (m[1] * m[6] - m[0] * m[7]) * invDet,
            (m[0] * m[4] - m[1] * m[3]) * invDet
        )
    }

    fun diagonal3x3(v: FloatArray): FloatArray {
        require(v.size == 3)
        return floatArrayOf(
            v[0], 0f, 0f,
            0f, v[1], 0f,
            0f, 0f, v[2]
        )
    }

    fun identityArray(): FloatArray = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f
    )
}
