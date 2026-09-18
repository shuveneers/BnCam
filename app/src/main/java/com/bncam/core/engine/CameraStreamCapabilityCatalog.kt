package com.bncam.core.engine

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.util.Range
import android.util.Size
import com.bncam.data.settings.StreamConfigurationClass
import java.util.concurrent.Executor
import kotlin.math.abs

/**
 * Immutable Camera2/HAL facts used by the Stream Configuration UI and, in the next integration
 * phase, by the runtime StreamConfigResolver. Nothing in this file changes camera state.
 */
data class CameraStreamSizeCapability(
    val size: Size,
    val minFrameDurationNs: Long?,
    val maxFpsFromDuration: Int?
)

data class CameraStreamFormatCapability(
    val formatCode: Int,
    val formatName: String,
    val sizes: List<CameraStreamSizeCapability>
)

enum class StreamCandidateValidationStatus {
    SESSION_VALIDATED,
    CAMERA2_REPORTED,
    REJECTED
}

data class CameraStreamCandidate(
    val id: String,
    val streamClass: StreamConfigurationClass,
    val captureFormatCode: Int,
    val captureFormatName: String,
    val captureSize: Size,
    val previewSize: Size?,
    val fpsRange: Range<Int>?,
    val validationStatus: StreamCandidateValidationStatus,
    val validationReason: String
)

enum class CameraStreamCatalogSource {
    DIRECT_CAMERA,
    PHYSICAL_CAMERA,
    LOGICAL_PARENT_FALLBACK,
    UNAVAILABLE
}

data class CameraStreamCapabilityCatalog(
    val requestedLensId: String,
    val logicalCameraId: String?,
    val physicalCameraId: String?,
    val source: CameraStreamCatalogSource,
    val hardwareLevel: Int?,
    val requestCapabilities: Set<Int>,
    val aeFpsRanges: List<Range<Int>>,
    val formats: List<CameraStreamFormatCapability>,
    val candidates: List<CameraStreamCandidate>,
    val sessionQuerySupported: Boolean,
    val warnings: List<String>
) {
    fun candidatesFor(streamClass: StreamConfigurationClass): List<CameraStreamCandidate> =
        candidates.filter { it.streamClass == streamClass && it.validationStatus != StreamCandidateValidationStatus.REJECTED }

    fun validatedCandidatesFor(streamClass: StreamConfigurationClass): List<CameraStreamCandidate> =
        candidates.filter {
            it.streamClass == streamClass &&
                it.validationStatus == StreamCandidateValidationStatus.SESSION_VALIDATED
        }
}

object CameraStreamCapabilityScanner {
    fun scan(context: Context, lensId: String): CameraStreamCapabilityCatalog {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val warnings = mutableListOf<String>()
        val publicIds = runCatching { manager.cameraIdList.toSet() }.getOrDefault(emptySet())
        val resolved = resolveCharacteristics(manager, publicIds, lensId)
            ?: return CameraStreamCapabilityCatalog(
                requestedLensId = lensId,
                logicalCameraId = null,
                physicalCameraId = null,
                source = CameraStreamCatalogSource.UNAVAILABLE,
                hardwareLevel = null,
                requestCapabilities = emptySet(),
                aeFpsRanges = emptyList(),
                formats = emptyList(),
                candidates = emptyList(),
                sessionQuerySupported = false,
                warnings = listOf("CameraCharacteristics are unavailable for Lens ID $lensId.")
            )

        if (resolved.source == CameraStreamCatalogSource.LOGICAL_PARENT_FALLBACK) {
            warnings += "Physical characteristics were unavailable; this catalog uses the logical parent as a fallback and is not sensor-exclusive."
        }

        val chars = resolved.characteristics
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (map == null) {
            return CameraStreamCapabilityCatalog(
                requestedLensId = lensId,
                logicalCameraId = resolved.logicalCameraId,
                physicalCameraId = resolved.physicalCameraId,
                source = resolved.source,
                hardwareLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL),
                requestCapabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty(),
                aeFpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty(),
                formats = emptyList(),
                candidates = emptyList(),
                sessionQuerySupported = false,
                warnings = warnings + "SCALER_STREAM_CONFIGURATION_MAP is unavailable."
            )
        }

        val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.filter { it.lower > 0 && it.upper >= it.lower }
            ?.sortedWith(compareBy<Range<Int>> { it.upper }.thenBy { it.lower })
            .orEmpty()

        val formats = map.outputFormats.distinct().map { format ->
            val sizes = runCatching { map.getOutputSizes(format)?.toList().orEmpty() }
                .getOrDefault(emptyList())
                .filter { it.width > 0 && it.height > 0 }
                .distinctBy { it.width to it.height }
                .sortedByDescending { it.width.toLong() * it.height.toLong() }
                .map { size ->
                    val durationNs = runCatching { map.getOutputMinFrameDuration(format, size) }
                        .getOrNull()
                        ?.takeIf { it > 0L }
                    CameraStreamSizeCapability(
                        size = size,
                        minFrameDurationNs = durationNs,
                        maxFpsFromDuration = durationNs?.let {
                            (1_000_000_000.0 / it.toDouble()).toInt().coerceAtLeast(1)
                        }
                    )
                }
            CameraStreamFormatCapability(
                formatCode = format,
                formatName = cameraFormatName(format),
                sizes = sizes
            )
        }.sortedWith(compareBy<CameraStreamFormatCapability> { it.formatName }.thenBy { it.formatCode })

        val previewSizes = runCatching {
            map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        }.getOrDefault(emptyList())
            .filter { it.width > 0 && it.height > 0 }
            .distinctBy { it.width to it.height }

        val sessionQueryCameraId = resolved.logicalCameraId.takeIf { it in publicIds }
        val sessionQuerySupported = Build.VERSION.SDK_INT >= 35 &&
            sessionQueryCameraId != null &&
            CameraSessionPreflight.isSetupSupported(manager, sessionQueryCameraId)
        if (Build.VERSION.SDK_INT >= 35 && !sessionQuerySupported) {
            warnings += "CameraDeviceSetup session preflight is not available for this camera route; candidates remain Camera2-reported until runtime validation."
        }

        val sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
            ?: chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val scannedPhotoCandidates = buildPhotoCandidates(
            manager = manager,
            logicalCameraId = sessionQueryCameraId,
            formats = formats,
            previewSizes = previewSizes,
            fpsRanges = fpsRanges,
            sessionQuerySupported = sessionQuerySupported,
            physicalCameraId = resolved.physicalCameraId,
            sensorWidth = sensorRect?.width() ?: 0,
            sensorHeight = sensorRect?.height() ?: 0
        )
        val photoCandidates = if (resolved.source == CameraStreamCatalogSource.LOGICAL_PARENT_FALLBACK) {
            // A parent StreamConfigurationMap is not sensor-exclusive truth. Only keep candidates
            // whose physical-ID-bound complete session was explicitly accepted by the HAL.
            scannedPhotoCandidates.filter {
                it.validationStatus == StreamCandidateValidationStatus.SESSION_VALIDATED
            }
        } else {
            scannedPhotoCandidates
        }
        val videoCandidates = if (resolved.source == CameraStreamCatalogSource.LOGICAL_PARENT_FALLBACK) {
            // Video preflight is not implemented yet, so do not present parent YUV sizes as if
            // they were reported by this hidden physical sensor.
            emptyList()
        } else {
            buildVideoCandidates(
                formats = formats,
                fpsRanges = fpsRanges
            )
        }

        return CameraStreamCapabilityCatalog(
            requestedLensId = lensId,
            logicalCameraId = resolved.logicalCameraId,
            physicalCameraId = resolved.physicalCameraId,
            source = resolved.source,
            hardwareLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL),
            requestCapabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty(),
            aeFpsRanges = fpsRanges,
            formats = formats,
            candidates = photoCandidates + videoCandidates,
            sessionQuerySupported = sessionQuerySupported,
            warnings = warnings
        )
    }

    private fun buildPhotoCandidates(
        manager: CameraManager,
        logicalCameraId: String?,
        formats: List<CameraStreamFormatCapability>,
        previewSizes: List<Size>,
        fpsRanges: List<Range<Int>>,
        sessionQuerySupported: Boolean,
        physicalCameraId: String?,
        sensorWidth: Int,
        sensorHeight: Int
    ): List<CameraStreamCandidate> {
        val preferredFormats = listOf(ImageFormat.RAW_SENSOR, ImageFormat.RAW10, ImageFormat.YUV_420_888)
        val candidates = mutableListOf<CameraStreamCandidate>()
        preferredFormats.forEach { formatCode ->
            val capability = formats.firstOrNull { it.formatCode == formatCode } ?: return@forEach
            val allSizes = capability.sizes.map { it.size }
            val fullFovExtents = CameraStreamGeometryPolicy.fullFovCandidates(
                candidates = allSizes.map { CameraStreamGeometryPolicy.Extent(it.width, it.height) },
                sensorWidth = sensorWidth,
                sensorHeight = sensorHeight,
                aspectTolerance = if (formatCode == ImageFormat.RAW10 || formatCode == ImageFormat.RAW_SENSOR) {
                    0.025
                } else {
                    CameraStreamGeometryPolicy.DEFAULT_ASPECT_TOLERANCE
                }
            )
            val fullFovKeys = fullFovExtents.mapTo(hashSetOf()) { it.width to it.height }
            val photoSizes = allSizes.filter { (it.width to it.height) in fullFovKeys }.ifEmpty { allSizes }
            val captureSizes = representativeSizes(photoSizes, maxCount = 3)
            captureSizes.forEach { captureSize ->
                val previewSize = selectPreviewSize(previewSizes, captureSize)
                val captureCapability = capability.sizes.firstOrNull { it.size == captureSize }
                // The capture stream remains the conservative cadence authority here. Preview
                // SurfaceTexture duration is not consistently reported by every HAL. Runtime FPS
                // policy will still intersect the resolved range with the actual preview contract.
                val sustainable = captureCapability?.maxFpsFromDuration
                val fpsRange = chooseFpsRange(fpsRanges, sustainable)
                val preflight = preflightRegularSession(
                    manager = manager,
                    logicalCameraId = logicalCameraId,
                    sessionQuerySupported = sessionQuerySupported,
                    previewSize = previewSize,
                    captureFormat = formatCode,
                    captureSize = captureSize,
                    physicalCameraId = physicalCameraId
                )
                val id = buildCandidateId(
                    StreamConfigurationClass.PHOTO,
                    formatCode,
                    captureSize,
                    previewSize,
                    fpsRange
                )
                candidates += CameraStreamCandidate(
                    id = id,
                    streamClass = StreamConfigurationClass.PHOTO,
                    captureFormatCode = formatCode,
                    captureFormatName = capability.formatName,
                    captureSize = captureSize,
                    previewSize = previewSize,
                    fpsRange = fpsRange,
                    validationStatus = preflight.first,
                    validationReason = preflight.second
                )
            }
        }
        return candidates
    }

    private fun buildVideoCandidates(
        formats: List<CameraStreamFormatCapability>,
        fpsRanges: List<Range<Int>>
    ): List<CameraStreamCandidate> {
        val capability = formats.firstOrNull { it.formatCode == ImageFormat.YUV_420_888 } ?: return emptyList()
        return representativeSizes(
            capability.sizes.map { it.size }.filter { it.width <= 3840 && it.height <= 2160 },
            maxCount = 4
        ).map { size ->
            val sizeCapability = capability.sizes.firstOrNull { it.size == size }
            val fpsRange = chooseFpsRange(fpsRanges, sizeCapability?.maxFpsFromDuration)
            CameraStreamCandidate(
                id = buildCandidateId(StreamConfigurationClass.VIDEO, capability.formatCode, size, null, fpsRange),
                streamClass = StreamConfigurationClass.VIDEO,
                captureFormatCode = capability.formatCode,
                captureFormatName = capability.formatName,
                captureSize = size,
                previewSize = null,
                fpsRange = fpsRange,
                validationStatus = StreamCandidateValidationStatus.CAMERA2_REPORTED,
                validationReason = "Camera2 reports this YUV size. Video encoder/MediaCodec session validation is deferred to the video runtime."
            )
        }
    }

    private fun representativeSizes(sizes: List<Size>, maxCount: Int): List<Size> {
        if (sizes.isEmpty()) return emptyList()
        val ordered = sizes.distinctBy { it.width to it.height }
            .sortedByDescending { it.width.toLong() * it.height.toLong() }
        if (ordered.size <= maxCount) return ordered
        val indexes = when (maxCount) {
            1 -> listOf(0)
            2 -> listOf(0, ordered.lastIndex)
            3 -> listOf(0, ordered.lastIndex / 2, ordered.lastIndex)
            else -> List(maxCount) { index ->
                ((ordered.lastIndex.toDouble() * index) / (maxCount - 1).coerceAtLeast(1)).toInt()
            }
        }
        return indexes.distinct().map { ordered[it] }
    }

    private fun selectPreviewSize(previewSizes: List<Size>, captureSize: Size): Size? {
        if (previewSizes.isEmpty()) return null
        val targetAspect = normalizedAspect(captureSize)
        val bounded = previewSizes.filter { size ->
            size.width.toLong() * size.height.toLong() <= 1920L * 1080L
        }.ifEmpty { previewSizes }
        return bounded.minWithOrNull(
            compareBy<Size> { size -> abs(normalizedAspect(size) - targetAspect) }
                .thenByDescending { size -> size.width.toLong() * size.height.toLong() }
        )
    }

    private fun normalizedAspect(size: Size): Double {
        val longSide = maxOf(size.width, size.height).toDouble()
        val shortSide = minOf(size.width, size.height).coerceAtLeast(1).toDouble()
        return longSide / shortSide
    }

    private fun chooseFpsRange(
        ranges: List<Range<Int>>,
        sustainableUpperFps: Int?
    ): Range<Int>? {
        if (ranges.isEmpty()) return null
        val compatible = sustainableUpperFps?.let { maxFps -> ranges.filter { it.upper <= maxFps } }.orEmpty()
        val pool = compatible.ifEmpty { ranges }
        return pool.sortedWith(
            compareByDescending<Range<Int>> { it.upper }
                .thenByDescending { it.lower <= 30 }
                .thenBy { it.lower }
        ).firstOrNull()
    }

    private fun preflightRegularSession(
        manager: CameraManager,
        logicalCameraId: String?,
        sessionQuerySupported: Boolean,
        previewSize: Size?,
        captureFormat: Int,
        captureSize: Size,
        physicalCameraId: String?
    ): Pair<StreamCandidateValidationStatus, String> {
        if (!sessionQuerySupported || logicalCameraId == null || Build.VERSION.SDK_INT < 35) {
            return StreamCandidateValidationStatus.CAMERA2_REPORTED to
                "Reported by StreamConfigurationMap; CameraDeviceSetup preflight is unavailable."
        }
        if (previewSize == null) {
            return StreamCandidateValidationStatus.CAMERA2_REPORTED to
                "Capture stream is reported, but no SurfaceTexture preview size was available for full-session preflight."
        }

        val result = CameraSessionPreflight.validateRegularSession(
            manager = manager,
            logicalCameraId = logicalCameraId,
            previewSize = previewSize,
            captureFormat = captureFormat,
            captureSize = captureSize,
            physicalCameraId = physicalCameraId
        )
        return result.status to result.reason
    }

    private data class ResolvedCharacteristics(
        val logicalCameraId: String,
        val physicalCameraId: String?,
        val characteristics: CameraCharacteristics,
        val source: CameraStreamCatalogSource
    )

    private fun resolveCharacteristics(
        manager: CameraManager,
        publicIds: Set<String>,
        lensId: String
    ): ResolvedCharacteristics? {
        val directCharacteristics = runCatching { manager.getCameraCharacteristics(lensId) }.getOrNull()
        if (directCharacteristics != null) {
            if (lensId in publicIds) {
                return ResolvedCharacteristics(
                    logicalCameraId = lensId,
                    physicalCameraId = null,
                    characteristics = directCharacteristics,
                    source = CameraStreamCatalogSource.DIRECT_CAMERA
                )
            }
            val parent = findLogicalParent(manager, publicIds, lensId)
            if (parent != null) {
                return ResolvedCharacteristics(
                    logicalCameraId = parent,
                    physicalCameraId = lensId,
                    characteristics = directCharacteristics,
                    source = CameraStreamCatalogSource.PHYSICAL_CAMERA
                )
            }
        }

        val parent = findLogicalParent(manager, publicIds, lensId) ?: return null
        val parentCharacteristics = runCatching { manager.getCameraCharacteristics(parent) }.getOrNull() ?: return null
        return ResolvedCharacteristics(
            logicalCameraId = parent,
            physicalCameraId = lensId,
            characteristics = parentCharacteristics,
            source = CameraStreamCatalogSource.LOGICAL_PARENT_FALLBACK
        )
    }

    private fun findLogicalParent(
        manager: CameraManager,
        publicIds: Set<String>,
        physicalCameraId: String
    ): String? = publicIds.sorted().firstOrNull { candidate ->
        runCatching {
            manager.getCameraCharacteristics(candidate).physicalCameraIds.contains(physicalCameraId)
        }.getOrDefault(false)
    }

    private fun buildCandidateId(
        streamClass: StreamConfigurationClass,
        formatCode: Int,
        captureSize: Size,
        previewSize: Size?,
        fpsRange: Range<Int>?
    ): String = buildString {
        append(streamClass.name)
        append(':').append(formatCode)
        append(':').append(captureSize.width).append('x').append(captureSize.height)
        previewSize?.let { append(":P").append(it.width).append('x').append(it.height) }
        fpsRange?.let { append(":F").append(it.lower).append('-').append(it.upper) }
    }
}


@android.annotation.TargetApi(35)
internal data class CameraSessionPreflightResult(
    val status: StreamCandidateValidationStatus,
    val reason: String
)

/**
 * Single Camera2/HAL session-preflight authority shared by the catalog and runtime resolver.
 * It never opens a CameraDevice and never mutates the active BnCam session.
 */
@android.annotation.TargetApi(35)
internal object CameraSessionPreflight {
    private val directExecutor = Executor { runnable -> runnable.run() }

    private fun noOpStateCallback(): CameraCaptureSession.StateCallback =
        object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) = Unit
            override fun onConfigureFailed(session: CameraCaptureSession) = Unit
        }

    fun isSetupSupported(manager: CameraManager, cameraId: String): Boolean = runCatching {
        manager.isCameraDeviceSetupSupported(cameraId)
    }.getOrDefault(false)

    fun validateRegularSession(
        manager: CameraManager,
        logicalCameraId: String,
        previewSize: Size,
        captureFormat: Int,
        captureSize: Size,
        physicalCameraId: String?
    ): CameraSessionPreflightResult {
        if (!isSetupSupported(manager, logicalCameraId)) {
            return CameraSessionPreflightResult(
                StreamCandidateValidationStatus.CAMERA2_REPORTED,
                "CameraDeviceSetup is not supported for logical camera $logicalCameraId."
            )
        }

        return runCatching {
            val setup = manager.getCameraDeviceSetup(logicalCameraId)
            val previewOutput = OutputConfiguration(previewSize, SurfaceTexture::class.java)
            val captureOutput = OutputConfiguration(captureFormat, captureSize)
            if (physicalCameraId != null) {
                previewOutput.setPhysicalCameraId(physicalCameraId)
                captureOutput.setPhysicalCameraId(physicalCameraId)
            }
            val config = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(previewOutput, captureOutput),
                directExecutor,
                noOpStateCallback()
            )
            if (setup.isSessionConfigurationSupported(config)) {
                CameraSessionPreflightResult(
                    StreamCandidateValidationStatus.SESSION_VALIDATED,
                    "CameraDeviceSetup accepted preview + capture as one regular session."
                )
            } else {
                CameraSessionPreflightResult(
                    StreamCandidateValidationStatus.REJECTED,
                    "CameraDeviceSetup rejected the preview + capture combination."
                )
            }
        }.getOrElse { error ->
            CameraSessionPreflightResult(
                StreamCandidateValidationStatus.CAMERA2_REPORTED,
                "Session preflight failed (${error.javaClass.simpleName})."
            )
        }
    }

    /**
     * Diagnoses a runtime configure failure without touching the live CameraDevice. The exact
     * preview output, exact canonical capture output, and their pair are queried independently.
     * This prevents a preview-only incompatibility from being misdiagnosed as a reason to keep
     * shrinking the capture stream.
     */
    fun diagnoseRegularPhotoCombination(
        manager: CameraManager,
        logicalCameraId: String,
        previewSize: Size,
        captureFormat: Int,
        captureSize: Size,
        physicalCameraId: String?
    ): SessionOutputFailureDiagnosis {
        if (!isSetupSupported(manager, logicalCameraId)) {
            return SessionOutputFailureDiagnosis(
                kind = SessionOutputFailureKind.PREFLIGHT_UNAVAILABLE,
                reason = "CameraDeviceSetup is unavailable for logical camera $logicalCameraId."
            )
        }

        return runCatching {
            val setup = manager.getCameraDeviceSetup(logicalCameraId)

            fun previewOutput(): OutputConfiguration =
                OutputConfiguration(previewSize, SurfaceTexture::class.java).also { output ->
                    if (physicalCameraId != null) output.setPhysicalCameraId(physicalCameraId)
                }

            fun captureOutput(): OutputConfiguration =
                OutputConfiguration(captureFormat, captureSize).also { output ->
                    if (physicalCameraId != null) output.setPhysicalCameraId(physicalCameraId)
                }

            fun supported(outputs: List<OutputConfiguration>): Boolean {
                val config = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    directExecutor,
                    noOpStateCallback()
                )
                return setup.isSessionConfigurationSupported(config)
            }

            val previewSupported = supported(listOf(previewOutput()))
            val captureSupported = supported(listOf(captureOutput()))
            val combinedSupported = supported(listOf(previewOutput(), captureOutput()))
            val kind = SessionOutputCombinationRecoveryPolicy.classifyRegularSessionPreflight(
                previewSupported = previewSupported,
                captureSupported = captureSupported,
                combinedSupported = combinedSupported
            )
            SessionOutputFailureDiagnosis(
                kind = kind,
                reason = buildString {
                    append("CameraDeviceSetup diagnosis preview=").append(previewSupported)
                    append(" capture=").append(captureSupported)
                    append(" combined=").append(combinedSupported)
                    append(" for ").append(previewSize.width).append('x').append(previewSize.height)
                    append(" + ").append(cameraFormatName(captureFormat))
                    append(' ').append(captureSize.width).append('x').append(captureSize.height).append('.')
                },
                previewIndividuallySupported = previewSupported,
                captureIndividuallySupported = captureSupported,
                combinedSupported = combinedSupported
            )
        }.getOrElse { error ->
            SessionOutputFailureDiagnosis(
                kind = SessionOutputFailureKind.PREFLIGHT_UNAVAILABLE,
                reason = "CameraDeviceSetup diagnosis failed (${error.javaClass.simpleName})."
            )
        }
    }

}

fun cameraFormatName(format: Int): String = when (format) {
    ImageFormat.YUV_420_888 -> "YUV_420_888"
    ImageFormat.RAW10 -> "RAW10"
    ImageFormat.RAW12 -> "RAW12"
    ImageFormat.RAW_SENSOR -> "RAW_SENSOR"
    ImageFormat.RAW_PRIVATE -> "RAW_PRIVATE"
    ImageFormat.JPEG -> "JPEG"
    ImageFormat.DEPTH16 -> "DEPTH16"
    ImageFormat.DEPTH_POINT_CLOUD -> "DEPTH_POINT_CLOUD"
    ImageFormat.PRIVATE -> "PRIVATE"
    else -> "Vendor / format $format"
}

fun cameraHardwareLevelName(level: Int?): String = when (level) {
    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
    null -> "Unavailable"
    else -> level.toString()
}
