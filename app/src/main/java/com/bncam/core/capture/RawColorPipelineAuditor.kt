package com.bncam.core.capture

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.util.Log
import kotlin.math.abs

object RawColorPipelineAuditor {
    private const val TAG = "RawColorPipelineAuditor"

    // Testing Backdoor
    var testCfaOverride: Int? = null

    data class RawCalibrationAudit(
        val referenceIlluminant1: Int? = null,
        val referenceIlluminant2: Int? = null,
        val calibrationTemperature1Kelvin: Float? = null,
        val calibrationTemperature2Kelvin: Float? = null,
        val dualInterpolationTemperatureReady: Boolean = false,
        val colorTransform1Available: Boolean = false,
        val colorTransform2Available: Boolean = false,
        val calibrationTransform1Available: Boolean = false,
        val calibrationTransform2Available: Boolean = false,
        val forwardMatrix1Available: Boolean = false,
        val forwardMatrix2Available: Boolean = false,
        val calibrationSet1Complete: Boolean = false,
        val calibrationSet2Complete: Boolean = false,
        val dualIlluminantForwardCalibrationAvailable: Boolean = false,
        val status: String = "RAW_STATIC_CALIBRATION_UNAVAILABLE"
    )

    data class ColorAuditResult(
        val cfaPatternMatched: Boolean,
        val cfaPatternName: String,
        val wbGainsConsistent: Boolean,
        val ccmDeterminantValid: Boolean,
        val ccmDeterminant: Float,
        val lensShadingConsistent: Boolean,
        val rawCalibration: RawCalibrationAudit = RawCalibrationAudit(),
        val diagnostics: List<String>
    )

    /**
     * Calibration temperature semantics follow Adobe's DNG SDK LightSource mapping.
     * This is not scene-CCT estimation. Unknown/Other remain unresolved instead of silently
     * becoming an arbitrary daylight temperature.
     */
    private fun dngCalibrationTemperatureKelvin(value: Int?): Float? = when (value) {
        17, 3 -> 2850.0f
        24 -> 3200.0f
        23 -> 5000.0f
        20, 1, 9, 4, 18 -> 5500.0f
        21, 19, 10 -> 6500.0f
        22, 11 -> 7500.0f
        12 -> 6400.0f
        13 -> 5050.0f
        14, 2 -> 4150.0f
        15 -> 3525.0f
        16 -> 2925.0f
        else -> null
    }

    private fun referenceIlluminantLabel(value: Int?): String = when (value) {
        CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_DAYLIGHT -> "DAYLIGHT"
        CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_FLUORESCENT -> "FLUORESCENT"
        CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_TUNGSTEN -> "TUNGSTEN"
        CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_FLASH -> "FLASH"
        CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_FINE_WEATHER -> "FINE_WEATHER"
        CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_CLOUDY_WEATHER -> "CLOUDY_WEATHER"
        CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_SHADE -> "SHADE"
        CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_DAYLIGHT_FLUORESCENT -> "DAYLIGHT_FLUORESCENT"
        CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_DAY_WHITE_FLUORESCENT -> "DAY_WHITE_FLUORESCENT"
        CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_COOL_WHITE_FLUORESCENT -> "COOL_WHITE_FLUORESCENT"
        CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_WHITE_FLUORESCENT -> "WHITE_FLUORESCENT"
        CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_A -> "STANDARD_A"
        CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_B -> "STANDARD_B"
        CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_C -> "STANDARD_C"
        CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_D55 -> "D55"
        CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_D65 -> "D65"
        CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_D75 -> "D75"
        CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_D50 -> "D50"
        CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_ISO_STUDIO_TUNGSTEN -> "ISO_STUDIO_TUNGSTEN"
        null -> "UNREPORTED"
        else -> "UNKNOWN($value)"
    }

    private fun auditRawStaticCalibration(
        characteristics: CameraCharacteristics?,
        diagnostics: MutableList<String>
    ): RawCalibrationAudit {
        if (characteristics == null) {
            diagnostics.add("RAW_COLOR_CALIBRATION: characteristics unavailable")
            return RawCalibrationAudit()
        }

        val reference1 = try {
            characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)
        } catch (_: Throwable) {
            null
        }
        val reference2 = try {
            characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt()
        } catch (_: Throwable) {
            null
        }
        val color1 = try {
            characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)
        } catch (_: Throwable) {
            null
        }
        val color2 = try {
            characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)
        } catch (_: Throwable) {
            null
        }
        val calibration1 = try {
            characteristics.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)
        } catch (_: Throwable) {
            null
        }
        val calibration2 = try {
            characteristics.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)
        } catch (_: Throwable) {
            null
        }
        val forward1 = try {
            characteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)
        } catch (_: Throwable) {
            null
        }
        val forward2 = try {
            characteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)
        } catch (_: Throwable) {
            null
        }

        val calibrationTemperature1 = dngCalibrationTemperatureKelvin(reference1)
        val calibrationTemperature2 = dngCalibrationTemperatureKelvin(reference2)
        val set1Complete = reference1 != null && color1 != null && calibration1 != null && forward1 != null
        val set2Complete = reference2 != null && color2 != null && calibration2 != null && forward2 != null
        val dualReady = set1Complete && set2Complete
        val dualInterpolationTemperatureReady = dualReady &&
            calibrationTemperature1 != null && calibrationTemperature2 != null
        val status = when {
            dualReady -> "RAW_STATIC_DUAL_ILLUMINANT_FORWARD_CALIBRATION_READY"
            set1Complete -> "RAW_STATIC_SINGLE_ILLUMINANT_FORWARD_CALIBRATION_READY"
            reference1 != null || color1 != null || calibration1 != null || forward1 != null ->
                "RAW_STATIC_CALIBRATION_PARTIAL_FAIL_CLOSED"
            else -> "RAW_STATIC_CALIBRATION_UNAVAILABLE"
        }

        diagnostics.add(
            "RAW_COLOR_CALIBRATION_SET1: complete=$set1Complete " +
                "reference=${referenceIlluminantLabel(reference1)}($reference1) " +
                "calibrationTemperatureKelvin=${calibrationTemperature1 ?: "UNRESOLVED"} " +
                "colorTransform=${color1 != null} calibrationTransform=${calibration1 != null} " +
                "forwardMatrix=${forward1 != null}"
        )
        diagnostics.add(
            "RAW_COLOR_CALIBRATION_SET2: complete=$set2Complete " +
                "reference=${referenceIlluminantLabel(reference2)}($reference2) " +
                "calibrationTemperatureKelvin=${calibrationTemperature2 ?: "UNRESOLVED"} " +
                "colorTransform=${color2 != null} calibrationTransform=${calibration2 != null} " +
                "forwardMatrix=${forward2 != null}"
        )
        diagnostics.add(
            "RAW_COLOR_CALIBRATION_STATUS: $status; " +
                "dualIlluminantForwardCalibrationAvailable=$dualReady; " +
                "dualInterpolationTemperatureReady=$dualInterpolationTemperatureReady"
        )

        return RawCalibrationAudit(
            referenceIlluminant1 = reference1,
            referenceIlluminant2 = reference2,
            calibrationTemperature1Kelvin = calibrationTemperature1,
            calibrationTemperature2Kelvin = calibrationTemperature2,
            dualInterpolationTemperatureReady = dualInterpolationTemperatureReady,
            colorTransform1Available = color1 != null,
            colorTransform2Available = color2 != null,
            calibrationTransform1Available = calibration1 != null,
            calibrationTransform2Available = calibration2 != null,
            forwardMatrix1Available = forward1 != null,
            forwardMatrix2Available = forward2 != null,
            calibrationSet1Complete = set1Complete,
            calibrationSet2Complete = set2Complete,
            dualIlluminantForwardCalibrationAvailable = dualReady,
            status = status
        )
    }

    fun audit(
        characteristics: CameraCharacteristics?,
        result: CaptureResult?,
        cfaPattern: Int,
        effectiveWbGains: FloatArray,
        colorMatrix: FloatArray,
        lensShadingMap: Any?,
        jniStatsString: String? = null
    ): ColorAuditResult {
        val diagnostics = mutableListOf<String>()

        val hardwareCfa = testCfaOverride ?: try {
            characteristics?.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: -1
        } catch (_: Throwable) {
            -1
        }
        val cfaPatternMatched = hardwareCfa == cfaPattern
        val cfaName = when (cfaPattern) {
            0 -> "RGGB"
            1 -> "GRBG"
            2 -> "GBRG"
            3 -> "BGGR"
            else -> "UNKNOWN_$cfaPattern"
        }
        if (!cfaPatternMatched) {
            diagnostics.add("CFA pattern mismatch: hardware=$hardwareCfa, resolved=$cfaPattern ($cfaName)")
        } else {
            diagnostics.add("CFA pattern resolved: $cfaName")
        }

        val wbGainsConsistent = effectiveWbGains.size >= 4 && effectiveWbGains[0] > 0.1f && effectiveWbGains[3] > 0.1f
        if (!wbGainsConsistent) {
            diagnostics.add("WB gains are missing or invalid: size=${effectiveWbGains.size}")
        } else {
            diagnostics.add("WB gains: R=${effectiveWbGains[0]} Geven=${effectiveWbGains[1]} Godd=${effectiveWbGains[2]} B=${effectiveWbGains[3]}")
        }

        if (wbGainsConsistent && effectiveWbGains.take(4).any { it < 0.5f }) {
            diagnostics.add("WB_GAIN_UNDERFLOW: Detected white balance gain value underflow (< 0.5)")
        }

        var determinant = 0.0f
        var ccmDeterminantValid = false
        if (colorMatrix.size >= 9) {
            determinant = colorMatrix[0] * (colorMatrix[4] * colorMatrix[8] - colorMatrix[5] * colorMatrix[7]) -
                    colorMatrix[1] * (colorMatrix[3] * colorMatrix[8] - colorMatrix[5] * colorMatrix[6]) +
                    colorMatrix[2] * (colorMatrix[3] * colorMatrix[7] - colorMatrix[4] * colorMatrix[6])

            ccmDeterminantValid = determinant > 0.1f && determinant < 10.0f
            if (!ccmDeterminantValid) {
                diagnostics.add("CCM determinant is suspicious: $determinant")
                if (determinant < 0.0f) {
                    diagnostics.add("CCM_SIGN_ERR: Determinant is negative ($determinant), indicating wrong sign in CCM coefficients")
                }
            } else {
                diagnostics.add("CCM determinant valid: $determinant")
            }

            val isIdentity = abs(colorMatrix[0] - 1.0f) < 1e-4f && abs(colorMatrix[4] - 1.0f) < 1e-4f && abs(colorMatrix[8] - 1.0f) < 1e-4f &&
                    abs(colorMatrix[1]) < 1e-4f && abs(colorMatrix[2]) < 1e-4f && abs(colorMatrix[3]) < 1e-4f
            if (isIdentity) {
                diagnostics.add("WARNING: CCM is Identity matrix, colors may look uncalibrated")
            }
        } else {
            diagnostics.add("CCM matrix is missing or invalid size: ${colorMatrix.size}")
        }

        // Phase 2 / DELTA 0139: audit the RAW static calibration as a complete provenance set.
        // Do not infer Kelvin or synthesize a ForwardMatrix from the exact-frame Camera2 CCM.
        // The DNG HueSatMap path may only become eligible when its paired profile transform is known.
        val rawCalibration = auditRawStaticCalibration(characteristics, diagnostics)

        val lensShadingMapObj = lensShadingMap as? android.hardware.camera2.params.LensShadingMap
        val lensShadingConsistent = lensShadingMapObj == null || lensShadingMapObj.gainFactorCount > 0
        if (lensShadingMapObj != null) {
            diagnostics.add("Lens Shading Map found: rows=${lensShadingMapObj.rowCount} cols=${lensShadingMapObj.columnCount} factors=${lensShadingMapObj.gainFactorCount}")
        } else {
            diagnostics.add("Lens Shading Map not present or invalid type")
        }

        // --- PARSE JNI STATS & RUN STAGE MEANS DIAGNOSTICS ---
        if (jniStatsString != null) {
            val statsMap = mutableMapOf<String, String>()
            jniStatsString.split(";").forEach { part ->
                val pair = part.split("=")
                if (pair.size == 2) {
                    statsMap[pair[0].trim()] = pair[1].trim()
                }
            }

            val greenEvenStr = statsMap["greenSplitEvenMedian"]
            val greenOddStr = statsMap["greenSplitOddMedian"]
            val greenReason = statsMap["greenSplitReason"]
            val evenMed = greenEvenStr?.toFloatOrNull() ?: 0.0f
            val oddMed = greenOddStr?.toFloatOrNull() ?: 0.0f
            if (greenReason == "insufficient_green_samples" || evenMed <= 0.0f || oddMed <= 0.0f) {
                diagnostics.add("GREEN_PLANE_IMBALANCE_OR_MAPPING_SUSPECT: Insufficient samples or uninitialized medians (even=$evenMed, odd=$oddMed)")
            } else {
                val diff = abs(evenMed - oddMed)
                val avg = (evenMed + oddMed) * 0.5f
                val ratio = if (avg > 0f) diff / avg else 0f
                if (ratio > 0.03f) {
                    diagnostics.add("GREEN_PLANE_IMBALANCE_OR_MAPPING_SUSPECT: Even/odd green channel median ratio split too high (ratio=$ratio)")
                }
            }

            val toneR = statsMap["colorStageMeansToneR"]?.toFloatOrNull() ?: 0f
            val toneG = statsMap["colorStageMeansToneG"]?.toFloatOrNull() ?: 0f
            val toneB = statsMap["colorStageMeansToneB"]?.toFloatOrNull() ?: 0f
            if (toneR > 0f || toneB > 0f) {
                diagnostics.add("Color stage tone means: R=${String.format("%.4f", toneR)} G=${String.format("%.4f", toneG)} B=${String.format("%.4f", toneB)}")
                if (toneB > 1.6f * toneR && toneB > 1.2f * toneG) {
                    diagnostics.add("RGB_BGR_SWAP: Blue channel is extremely dominant ($toneB vs R=$toneR), suggesting BGR channel layout swap")
                }
            }

            val rawR = statsMap["colorStageMeansRawR"]?.toFloatOrNull() ?: 0f
            val rawG = statsMap["colorStageMeansRawG"]?.toFloatOrNull() ?: 0f
            val rawB = statsMap["colorStageMeansRawB"]?.toFloatOrNull() ?: 0f
            if (rawR > 0f || rawG > 0f || rawB > 0f) {
                diagnostics.add("Color stage raw means: R=${String.format("%.4f", rawR)} G=${String.format("%.4f", rawG)} B=${String.format("%.4f", rawB)}")
            }
        }

        val auditResult = ColorAuditResult(
            cfaPatternMatched = cfaPatternMatched,
            cfaPatternName = cfaName,
            wbGainsConsistent = wbGainsConsistent,
            ccmDeterminantValid = ccmDeterminantValid,
            ccmDeterminant = determinant,
            lensShadingConsistent = lensShadingConsistent,
            rawCalibration = rawCalibration,
            diagnostics = diagnostics
        )

        try {
            Log.i(TAG, "Color Pipeline Audit: $auditResult")
        } catch (_: Throwable) {}
        return auditResult
    }
}
