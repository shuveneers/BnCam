package com.bncam.core.engine

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Size
import com.bncam.data.settings.StreamConfigurationClass
import com.bncam.data.settings.StreamConfigurationMode
import com.bncam.data.settings.StreamConfigurationSettingsStore
import kotlin.math.abs

/**
 * One resolved Photo stream decision for the active Lens ID.
 *
 * Phase 0002 deliberately keeps capture format ownership with the active BnCam profile. Stream
 * Configuration may select a different *reported size* for that exact format, but may not silently
 * turn a YUV profile into RAW10/RAW_SENSOR (or vice versa). This keeps processing semantics and
 * Stream Configuration orthogonal while the session policy is migrated in controlled steps.
 */
data class ResolvedStreamPlan(
    val requestedLensId: String,
    val streamClass: StreamConfigurationClass,
    val configuredMode: StreamConfigurationMode,
    val effectiveFormatCode: Int,
    val captureSize: Size,
    val previewSize: Size?,
    val selectedCandidateId: String?,
    val halSessionValidated: Boolean,
    val fallbackToAuto: Boolean,
    val runtimeFallbackTier: StreamRuntimeFallbackTier = StreamRuntimeFallbackTier.NONE,
    val reason: String
) {
    val signature: String = buildString {
        append(streamClass.name)
        append(':').append(configuredMode.name)
        append(':').append(effectiveFormatCode)
        append(':').append(captureSize.width).append('x').append(captureSize.height)
        previewSize?.let { append(":P").append(it.width).append('x').append(it.height) }
        append(":V").append(if (halSessionValidated) '1' else '0')
        append(":F").append(if (fallbackToAuto) '1' else '0')
        append(":T").append(runtimeFallbackTier.name)
    }
}

internal data class ParsedStreamCandidateId(
    val streamClass: StreamConfigurationClass,
    val formatCode: Int,
    val captureSize: Size
)

/** Stable parser for candidate IDs persisted by the phase-0001 capability catalog. */
internal object StreamCandidateIdCodec {
    private val pattern = Regex("^(PHOTO|VIDEO):(-?\\d+):(\\d+)x(\\d+)(?::P\\d+x\\d+)?(?::F\\d+-\\d+)?$")

    fun parse(value: String?): ParsedStreamCandidateId? {
        val match = value?.trim()?.let(pattern::matchEntire) ?: return null
        val streamClass = runCatching { StreamConfigurationClass.valueOf(match.groupValues[1]) }.getOrNull()
            ?: return null
        val formatCode = match.groupValues[2].toIntOrNull() ?: return null
        val width = match.groupValues[3].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val height = match.groupValues[4].toIntOrNull()?.takeIf { it > 0 } ?: return null
        return ParsedStreamCandidateId(streamClass, formatCode, Size(width, height))
    }
}

/**
 * Runtime Stream Configuration authority.
 *
 * Auto is intentionally zero-risk: it returns the existing BnCam geometry decision unchanged.
 * Validated may override only capture resolution and only after the exact active preview + capture
 * combination is accepted by CameraDeviceSetup on API 35+. Any ambiguity falls back to Auto.
 * Manual currently preserves canonical producer geometry; the existing advanced RAW preview-format
 * override remains the only manual runtime control until its decoder/session contract is complete.
 */
object StreamConfigResolver {
    suspend fun resolvePhoto(
        context: Context,
        cameraManager: CameraManager,
        requestedLensId: String,
        logicalCameraId: String,
        physicalCameraId: String?,
        characteristics: CameraCharacteristics,
        requestedFormatCode: Int,
        autoCaptureSize: Size,
        configuredPreviewSize: Size?,
        sessionHasVendorOverrides: Boolean,
        runtimeFallback: StreamRuntimeFallbackOverride? = null
    ): ResolvedStreamPlan {
        val configured = runCatching {
            StreamConfigurationSettingsStore(context).get(requestedLensId).photo
        }.getOrElse { error ->
            return autoPlan(
                requestedLensId = requestedLensId,
                requestedFormatCode = requestedFormatCode,
                autoCaptureSize = autoCaptureSize,
                configuredPreviewSize = configuredPreviewSize,
                configuredMode = StreamConfigurationMode.AUTO,
                reason = "Stream settings unavailable (${error.javaClass.simpleName}); existing Auto geometry retained.",
                fallback = true
            )
        }

        val applicableRuntimeFallback = runtimeFallback
            ?.takeIf { it.appliesTo(configured.mode, configured.validatedCandidateId) }

        if (applicableRuntimeFallback != null &&
            applicableRuntimeFallback.tier != StreamRuntimeFallbackTier.NONE
        ) {
            if (applicableRuntimeFallback.tier == StreamRuntimeFallbackTier.AUTO_GEOMETRY) {
                return autoPlan(
                    requestedLensId = requestedLensId,
                    requestedFormatCode = requestedFormatCode,
                    autoCaptureSize = autoCaptureSize,
                    configuredPreviewSize = configuredPreviewSize,
                    configuredMode = configured.mode,
                    reason = "Runtime fallback quarantined the requested stream after ${applicableRuntimeFallback.failureReason}; existing Auto geometry is used without changing the saved setting.",
                    fallback = true,
                    runtimeFallbackTier = applicableRuntimeFallback.tier,
                    selectedCandidateId = configured.validatedCandidateId
                )
            }

            val conservative = selectConservativeFullFovSize(
                characteristics = characteristics,
                formatCode = requestedFormatCode,
                autoCaptureSize = autoCaptureSize
            )
            val conservativeChangedGeometry =
                conservative.width != autoCaptureSize.width || conservative.height != autoCaptureSize.height
            return autoPlan(
                requestedLensId = requestedLensId,
                requestedFormatCode = requestedFormatCode,
                autoCaptureSize = conservative,
                configuredPreviewSize = configuredPreviewSize,
                configuredMode = configured.mode,
                reason = if (conservativeChangedGeometry) {
                    "Runtime fallback escalated to a lower-bandwidth conservative full-FOV stream after ${applicableRuntimeFallback.failureReason}; saved settings remain unchanged."
                } else {
                    "Runtime fallback reached the conservative tier after ${applicableRuntimeFallback.failureReason}, but this Lens ID reports no smaller useful full-FOV size; Auto geometry is retained for one final clean session rebuild."
                },
                fallback = true,
                runtimeFallbackTier = applicableRuntimeFallback.tier,
                selectedCandidateId = configured.validatedCandidateId
            )
        }

        return when (configured.mode) {
            StreamConfigurationMode.AUTO -> autoPlan(
                requestedLensId = requestedLensId,
                requestedFormatCode = requestedFormatCode,
                autoCaptureSize = autoCaptureSize,
                configuredPreviewSize = configuredPreviewSize,
                configuredMode = configured.mode,
                reason = "Auto retained the existing BnCam full-FOV stream geometry.",
                fallback = false
            )

            StreamConfigurationMode.MANUAL -> autoPlan(
                requestedLensId = requestedLensId,
                requestedFormatCode = requestedFormatCode,
                autoCaptureSize = autoCaptureSize,
                configuredPreviewSize = configuredPreviewSize,
                configuredMode = configured.mode,
                reason = "Manual keeps canonical producer geometry in phase 0002; advanced RAW preview binding remains independently runtime-validated.",
                fallback = false
            )

            StreamConfigurationMode.VALIDATED -> resolveValidated(
                cameraManager = cameraManager,
                requestedLensId = requestedLensId,
                logicalCameraId = logicalCameraId,
                physicalCameraId = physicalCameraId,
                characteristics = characteristics,
                requestedFormatCode = requestedFormatCode,
                autoCaptureSize = autoCaptureSize,
                configuredPreviewSize = configuredPreviewSize,
                candidateId = configured.validatedCandidateId,
                sessionHasVendorOverrides = sessionHasVendorOverrides
            )
        }
    }

    private fun resolveValidated(
        cameraManager: CameraManager,
        requestedLensId: String,
        logicalCameraId: String,
        physicalCameraId: String?,
        characteristics: CameraCharacteristics,
        requestedFormatCode: Int,
        autoCaptureSize: Size,
        configuredPreviewSize: Size?,
        candidateId: String?,
        sessionHasVendorOverrides: Boolean
    ): ResolvedStreamPlan {
        if (sessionHasVendorOverrides) {
            return autoFallback(
                requestedLensId,
                requestedFormatCode,
                autoCaptureSize,
                configuredPreviewSize,
                candidateId,
                "Validated regular-session geometry is not applied while a vendor/session override is active."
            )
        }
        if (Build.VERSION.SDK_INT < 35) {
            return autoFallback(
                requestedLensId,
                requestedFormatCode,
                autoCaptureSize,
                configuredPreviewSize,
                candidateId,
                "Validated mode requires API 35+ CameraDeviceSetup preflight."
            )
        }
        if (configuredPreviewSize == null || configuredPreviewSize.width <= 0 || configuredPreviewSize.height <= 0) {
            return autoFallback(
                requestedLensId,
                requestedFormatCode,
                autoCaptureSize,
                configuredPreviewSize,
                candidateId,
                "Exact active preview geometry is unavailable; refusing to claim a validated session."
            )
        }

        val parsed = if (candidateId.isNullOrBlank()) {
            ParsedStreamCandidateId(
                streamClass = StreamConfigurationClass.PHOTO,
                formatCode = requestedFormatCode,
                captureSize = autoCaptureSize
            )
        } else {
            StreamCandidateIdCodec.parse(candidateId)
                ?: return autoFallback(
                    requestedLensId,
                    requestedFormatCode,
                    autoCaptureSize,
                    configuredPreviewSize,
                    candidateId,
                    "Stored candidate ID is invalid or from an incompatible schema."
                )
        }

        if (parsed.streamClass != StreamConfigurationClass.PHOTO) {
            return autoFallback(
                requestedLensId,
                requestedFormatCode,
                autoCaptureSize,
                configuredPreviewSize,
                candidateId,
                "Stored candidate belongs to ${parsed.streamClass.displayName}, not Photo."
            )
        }
        if (parsed.formatCode != requestedFormatCode) {
            return autoFallback(
                requestedLensId,
                requestedFormatCode,
                autoCaptureSize,
                configuredPreviewSize,
                candidateId,
                "Candidate format ${cameraFormatName(parsed.formatCode)} does not match the active profile buffer ${cameraFormatName(requestedFormatCode)}; profile buffer ownership is preserved."
            )
        }

        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val reported = runCatching {
            map?.getOutputSizes(requestedFormatCode)
                ?.any { it.width == parsed.captureSize.width && it.height == parsed.captureSize.height }
                ?: false
        }.getOrDefault(false)
        if (!reported) {
            return autoFallback(
                requestedLensId,
                requestedFormatCode,
                autoCaptureSize,
                configuredPreviewSize,
                candidateId,
                "Candidate size is no longer reported for this Lens ID/format."
            )
        }

        if (!preservesSensorAspect(characteristics, parsed.captureSize, requestedFormatCode)) {
            return autoFallback(
                requestedLensId,
                requestedFormatCode,
                autoCaptureSize,
                configuredPreviewSize,
                candidateId,
                "Validated Photo candidate would reduce the active sensor aspect/FOV; full-FOV policy retained."
            )
        }

        val preflight = CameraSessionPreflight.validateRegularSession(
            manager = cameraManager,
            logicalCameraId = logicalCameraId,
            physicalCameraId = physicalCameraId,
            previewSize = configuredPreviewSize,
            captureFormat = requestedFormatCode,
            captureSize = parsed.captureSize
        )
        if (preflight.status != StreamCandidateValidationStatus.SESSION_VALIDATED) {
            return autoFallback(
                requestedLensId,
                requestedFormatCode,
                autoCaptureSize,
                configuredPreviewSize,
                candidateId,
                preflight.reason
            )
        }

        return ResolvedStreamPlan(
            requestedLensId = requestedLensId,
            streamClass = StreamConfigurationClass.PHOTO,
            configuredMode = StreamConfigurationMode.VALIDATED,
            effectiveFormatCode = requestedFormatCode,
            captureSize = parsed.captureSize,
            previewSize = configuredPreviewSize,
            selectedCandidateId = candidateId,
            halSessionValidated = true,
            fallbackToAuto = false,
            runtimeFallbackTier = StreamRuntimeFallbackTier.NONE,
            reason = "Exact active preview + ${cameraFormatName(requestedFormatCode)} capture session accepted by CameraDeviceSetup."
        )
    }

    private fun preservesSensorAspect(
        characteristics: CameraCharacteristics,
        size: Size,
        formatCode: Int
    ): Boolean {
        // Still-photo producer streams stay full-FOV. Video may intentionally use 16:9 and is not
        // routed through this Photo resolver.
        if (formatCode != ImageFormat.YUV_420_888 &&
            formatCode != ImageFormat.RAW10 &&
            formatCode != ImageFormat.RAW_SENSOR
        ) return true
        val rect = characteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
            ?: characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?: return true
        val sensorLong = maxOf(rect.width(), rect.height()).toDouble()
        val sensorShort = minOf(rect.width(), rect.height()).coerceAtLeast(1).toDouble()
        val sizeLong = maxOf(size.width, size.height).toDouble()
        val sizeShort = minOf(size.width, size.height).coerceAtLeast(1).toDouble()
        val sensorAspect = sensorLong / sensorShort
        val sizeAspect = sizeLong / sizeShort
        val tolerance = if (formatCode == ImageFormat.RAW10 || formatCode == ImageFormat.RAW_SENSOR) 0.025 else 0.03
        return abs(sizeAspect - sensorAspect) / sensorAspect <= tolerance
    }

    private fun selectConservativeFullFovSize(
        characteristics: CameraCharacteristics,
        formatCode: Int,
        autoCaptureSize: Size
    ): Size {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return autoCaptureSize
        val autoArea = autoCaptureSize.width.toLong() * autoCaptureSize.height.toLong()
        if (autoArea <= 0L) return autoCaptureSize
        val minimumUsefulArea = (autoArea * 0.40).toLong()

        val candidates = runCatching { map.getOutputSizes(formatCode)?.toList().orEmpty() }
            .getOrDefault(emptyList())
            .asSequence()
            .filter { it.width > 0 && it.height > 0 }
            .filter { preservesSensorAspect(characteristics, it, formatCode) }
            .filter { size ->
                val area = size.width.toLong() * size.height.toLong()
                area in minimumUsefulArea until autoArea
            }
            .distinctBy { it.width to it.height }
            .toList()
        if (candidates.isEmpty()) return autoCaptureSize

        val autoDurationNs = runCatching {
            map.getOutputMinFrameDuration(formatCode, autoCaptureSize)
        }.getOrDefault(0L)

        val materiallySmaller = candidates.filter { size ->
            val area = size.width.toLong() * size.height.toLong()
            area * 100L <= autoArea * 85L
        }.ifEmpty { candidates }

        val fasterOrEqual = materiallySmaller.filter { size ->
            val duration = runCatching { map.getOutputMinFrameDuration(formatCode, size) }.getOrDefault(0L)
            duration <= 0L || autoDurationNs <= 0L || duration <= autoDurationNs
        }
        val pool = fasterOrEqual.ifEmpty { materiallySmaller }
        return pool.maxByOrNull { it.width.toLong() * it.height.toLong() } ?: autoCaptureSize
    }

    private fun autoFallback(
        requestedLensId: String,
        requestedFormatCode: Int,
        autoCaptureSize: Size,
        configuredPreviewSize: Size?,
        candidateId: String?,
        reason: String
    ): ResolvedStreamPlan = ResolvedStreamPlan(
        requestedLensId = requestedLensId,
        streamClass = StreamConfigurationClass.PHOTO,
        configuredMode = StreamConfigurationMode.VALIDATED,
        effectiveFormatCode = requestedFormatCode,
        captureSize = autoCaptureSize,
        previewSize = configuredPreviewSize,
        selectedCandidateId = candidateId,
        halSessionValidated = false,
        fallbackToAuto = true,
        runtimeFallbackTier = StreamRuntimeFallbackTier.NONE,
        reason = "$reason Falling back atomically to the existing Auto geometry."
    )

    private fun autoPlan(
        requestedLensId: String,
        requestedFormatCode: Int,
        autoCaptureSize: Size,
        configuredPreviewSize: Size?,
        configuredMode: StreamConfigurationMode,
        reason: String,
        fallback: Boolean,
        runtimeFallbackTier: StreamRuntimeFallbackTier = StreamRuntimeFallbackTier.NONE,
        selectedCandidateId: String? = null
    ): ResolvedStreamPlan = ResolvedStreamPlan(
        requestedLensId = requestedLensId,
        streamClass = StreamConfigurationClass.PHOTO,
        configuredMode = configuredMode,
        effectiveFormatCode = requestedFormatCode,
        captureSize = autoCaptureSize,
        previewSize = configuredPreviewSize,
        selectedCandidateId = selectedCandidateId,
        halSessionValidated = false,
        fallbackToAuto = fallback,
        runtimeFallbackTier = runtimeFallbackTier,
        reason = reason
    )
}
