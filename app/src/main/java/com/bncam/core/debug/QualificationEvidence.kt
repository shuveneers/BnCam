package com.bncam.core.debug

import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import com.bncam.BuildConfig
import com.bncam.core.capture.CaptureRecipe
import com.bncam.core.isp.raw.Raw16RenderInput
import org.json.JSONObject
import java.security.MessageDigest

/** Opt-in measurement only. Frozen per capture; hashing never materializes a RAW byte array. */
object QualificationEvidence {
    @Volatile var scene: String? = null
    fun activeScene(): String? = scene.takeIf { BuildConfig.DEBUG }
    fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    fun raw(input: Raw16RenderInput, recipe: CaptureRecipe, focus: Any, ev: Float): String {
        val result = input.captureResult
        val request = result?.request
        val calibration = input.finalCalibration
        val checksum = input.nativeRaw16Buffer.withDirectBuffer { buffer ->
            val view = buffer.asReadOnlyBuffer().apply { clear(); limit(input.raw16ByteCount) }
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(view)
            hex(digest.digest())
        }
        fun jsonValue(value: Any?): Any = value ?: JSONObject.NULL
        return JSONObject().apply {
            put("schemaVersion", 1)
            put("gitRevision", BuildConfig.GIT_REVISION)
            put("recipe", JSONObject(recipe.toJson()))
            put("rawChecksumDomain", "CANONICAL_DENSE_RAW16_LITTLE_ENDIAN")
            put("rawSha256", checksum)
            put("width", input.width); put("height", input.height)
            put("cfa", input.cfaPattern)
            put("cropLeft", input.nativeRaw16Buffer.sourceCropLeft)
            put("cropTop", input.nativeRaw16Buffer.sourceCropTop)
            put("blackLevel", calibration?.effectiveBlackLevels?.joinToString(",") ?: JSONObject.NULL)
            put("whiteLevel", calibration?.effectiveWhiteLevel ?: JSONObject.NULL)
            put("requestedExposureNs", jsonValue(request?.get(CaptureRequest.SENSOR_EXPOSURE_TIME)))
            put("requestedIso", jsonValue(request?.get(CaptureRequest.SENSOR_SENSITIVITY)))
            put("requestedAeMode", jsonValue(request?.get(CaptureRequest.CONTROL_AE_MODE)))
            put("effectiveExposureNs", jsonValue(result?.get(CaptureResult.SENSOR_EXPOSURE_TIME)))
            put("effectiveIso", jsonValue(result?.get(CaptureResult.SENSOR_SENSITIVITY)))
            put("frameDurationNs", jsonValue(result?.get(CaptureResult.SENSOR_FRAME_DURATION)))
            put("sensorTimestampNs", jsonValue(result?.get(CaptureResult.SENSOR_TIMESTAMP)))
            put("aeState", jsonValue(result?.get(CaptureResult.CONTROL_AE_STATE)))
            put("afMode", jsonValue(result?.get(CaptureResult.CONTROL_AF_MODE)))
            put("afState", jsonValue(result?.get(CaptureResult.CONTROL_AF_STATE)))
            put("focusDistance", jsonValue(result?.get(CaptureResult.LENS_FOCUS_DISTANCE)))
            put("focusContext", focus.toString()); put("evCompensation", ev)
            put("wbGains", calibration?.effectiveWbGains?.joinToString(",") ?: JSONObject.NULL)
            put("noiseModel", calibration?.noiseSnapshot?.physicalNoiseState()?.toString() ?: JSONObject.NULL)
            put("outputRotationDegrees", input.orientationDegrees)
        }.toString()
    }
}
