package com.bncam.core.capture

import org.json.JSONObject
import java.util.Locale

data class HardwareQualificationReport(
    val deviceModel: String,
    val androidVersion: String,
    val cameraId: String,
    val physicalCameraId: String?,
    val lensId: String,
    val profileId: String,
    val format: String,
    val bufferType: String,
    val captureMode: String,
    val generationId: Int,
    val frameCount: Int,
    val readinessState: String,
    val selectedCandidateTimestamp: Long,
    val rejectedCandidatesReasons: Map<Long, String>,
    val afState: String,
    val aeState: String,
    val awbState: String,
    val lensState: String,
    val sharpnessScore: Double,
    val motionScore: Double,
    val lowClippedFraction: Double,
    val highClippedFraction: Double,
    val rawColorAuditorSummary: String,
    val dngAuditResult: String,
    val jpegChannelStats: String,
    val oisAvailable: Boolean,
    val oisRequested: String,
    val oisResult: String,
    val videoStabilizationResult: String,
    val passVerdict: Boolean
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("deviceModel", deviceModel)
        json.put("androidVersion", androidVersion)
        json.put("cameraId", cameraId)
        json.put("physicalCameraId", physicalCameraId ?: JSONObject.NULL)
        json.put("lensId", lensId)
        json.put("profileId", profileId)
        json.put("format", format)
        json.put("bufferType", bufferType)
        json.put("captureMode", captureMode)
        json.put("generationId", generationId)
        json.put("frameCount", frameCount)
        json.put("readinessState", readinessState)
        json.put("selectedCandidateTimestamp", selectedCandidateTimestamp)

        val rejectedJson = JSONObject()
        rejectedCandidatesReasons.forEach { (ts, reason) ->
            rejectedJson.put(ts.toString(), reason)
        }
        json.put("rejectedCandidatesReasons", rejectedJson)

        json.put("afState", afState)
        json.put("aeState", aeState)
        json.put("awbState", awbState)
        json.put("lensState", lensState)
        json.put("sharpnessScore", sharpnessScore)
        json.put("motionScore", motionScore)
        json.put("lowClippedFraction", lowClippedFraction)
        json.put("highClippedFraction", highClippedFraction)
        json.put("rawColorAuditorSummary", rawColorAuditorSummary)
        json.put("dngAuditResult", dngAuditResult)
        json.put("jpegChannelStats", jpegChannelStats)
        json.put("oisAvailable", oisAvailable)
        json.put("oisRequested", oisRequested)
        json.put("oisResult", oisResult)
        json.put("videoStabilizationResult", videoStabilizationResult)
        json.put("passVerdict", passVerdict)
        return json.toString(4)
    }

    fun toMarkdown(): String {
        return """
        # Hardware Qualification Report

        ## Device Info
        * **Model**: $deviceModel
        * **Android OS**: $androidVersion

        ## Pipeline Configuration
        * **Camera ID**: $cameraId (Physical: ${physicalCameraId ?: "N/A"})
        * **Lens / Profile**: $lensId / $profileId
        * **Format**: $format
        * **Buffer Type**: $bufferType
        * **Capture Mode**: $captureMode
        * **Generation ID**: $generationId

        ## Capture Statistics
        * **Frame Count**: $frameCount
        * **Readiness State**: $readinessState
        * **Selected Frame TS**: $selectedCandidateTimestamp
        * **AF / AE / AWB / Lens**: $afState / $aeState / $awbState / $lensState
        * **Sharpness**: ${String.format(Locale.US, "%.4f", sharpnessScore)}
        * **Motion**: ${String.format(Locale.US, "%.4f", motionScore)}
        * **Low/High Clip**: ${String.format(Locale.US, "%.4f", lowClippedFraction)} / ${String.format(Locale.US, "%.4f", highClippedFraction)}

        ## Optical Image Stabilization (OIS) & Video Stabilization
        * **OIS Hardware Available**: $oisAvailable
        * **OIS Requested Mode**: $oisRequested
        * **OIS Echo Result**: $oisResult
        * **Video Stabilization Echo**: $videoStabilizationResult

        ## Diagnostics Summaries
        * **Color Auditor Summary**: $rawColorAuditorSummary
        * **DNG Audit**: $dngAuditResult
        * **JPEG Channel Stats**: $jpegChannelStats

        ## Verdict
        * **Verdict**: ${if (passVerdict) "PASS" else "FAIL"}
        """.trimIndent()
    }
}
