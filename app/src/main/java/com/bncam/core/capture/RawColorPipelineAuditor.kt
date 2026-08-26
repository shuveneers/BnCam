package com.bncam.core.capture

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.util.Log
import kotlin.math.abs

object RawColorPipelineAuditor {
    private const val TAG = "RawColorPipelineAuditor"

    // Testing Backdoor
    var testCfaOverride: Int? = null

    data class ColorAuditResult(
        val cfaPatternMatched: Boolean,
        val cfaPatternName: String,
        val wbGainsConsistent: Boolean,
        val ccmDeterminantValid: Boolean,
        val ccmDeterminant: Float,
        val lensShadingConsistent: Boolean,
        val diagnostics: List<String>
    )

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

        // WB_GAIN_UNDERFLOW check
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

            // 1. Green split diagnostic
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

            // 2. RGB / BGR Swap diagnostic
            val toneR = statsMap["colorStageMeansToneR"]?.toFloatOrNull() ?: 0f
            val toneG = statsMap["colorStageMeansToneG"]?.toFloatOrNull() ?: 0f
            val toneB = statsMap["colorStageMeansToneB"]?.toFloatOrNull() ?: 0f
            if (toneR > 0f || toneB > 0f) {
                diagnostics.add("Color stage tone means: R=${String.format("%.4f", toneR)} G=${String.format("%.4f", toneG)} B=${String.format("%.4f", toneB)}")
                if (toneB > 1.6f * toneR && toneB > 1.2f * toneG) {
                    diagnostics.add("RGB_BGR_SWAP: Blue channel is extremely dominant ($toneB vs R=$toneR), suggesting BGR channel layout swap")
                }
            }

            // 3. Raw/WB/CCM means info
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
            diagnostics = diagnostics
        )

        try {
            Log.i(TAG, "Color Pipeline Audit: $auditResult")
        } catch (_: Throwable) {}
        return auditResult
    }
}
