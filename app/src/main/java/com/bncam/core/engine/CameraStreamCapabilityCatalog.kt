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
import java.util.concurrent.Executor

/**
 * Android-shaped diagnostic projection used by the Stream Configuration screen.
 * CameraCapabilityInventory is the authoritative Camera2/HAL fact model.
 * Nothing in this projection changes camera state or selects a stream combination.
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


data class CameraStreamCapabilityCatalog(
    val inventory: CameraCapabilityInventory,
    val sessionQuerySupported: Boolean
) {
    // Read-only views for the settings UI. CameraCapabilityInventory is the
    // single source of Camera2/HAL facts; these Android-shaped objects are derived from it.
    val requestedLensId: String get() = inventory.requestedLensId
    val logicalCameraId: String? get() = inventory.logicalCameraId
    val physicalCameraId: String? get() = inventory.physicalCameraId
    val source: CameraStreamCatalogSource get() = inventory.source
    val hardwareLevel: Int? get() = inventory.hardwareLevel
    val requestCapabilities: Set<Int> get() = inventory.requestCapabilities
    val warnings: List<String> get() = inventory.warnings
    val aeFpsRanges: List<Range<Int>> = inventory.aeFpsRanges.map { Range(it.lower, it.upper) }
    val formats: List<CameraStreamFormatCapability> = inventory.formats
        .map { capability ->
            CameraStreamFormatCapability(
                formatCode = capability.formatCode,
                formatName = capability.formatName,
                sizes = capability.sizes.map { size ->
                    CameraStreamSizeCapability(
                        size = Size(size.extent.width, size.extent.height),
                        minFrameDurationNs = size.minFrameDurationNs,
                        maxFpsFromDuration = size.maxFpsFromDuration
                    )
                }
            )
        }
        .sortedWith(compareBy<CameraStreamFormatCapability> { it.formatName }.thenBy { it.formatCode })

}


/**
 * Lightweight CameraCharacteristics -> CameraCapabilityInventory conversion.
 *
 * Runtime pipeline selection and the settings diagnostics use the same fact inventory.
 * Capability discovery never enumerates synthetic stream candidates and never validates a
 * speculative session combination.
 */
internal object CameraCapabilityInventoryFactory {
    fun fromCharacteristics(
        requestedLensId: String,
        logicalCameraId: String?,
        physicalCameraId: String?,
        source: CameraStreamCatalogSource,
        characteristics: CameraCharacteristics,
        warnings: List<String> = emptyList()
    ): CameraCapabilityInventory {
        val requestCapabilities =
            characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
        val rawCapabilityAdvertised =
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW in requestCapabilities
        val fpsFacts = characteristics
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.asSequence()
            ?.filter { it.lower > 0 && it.upper >= it.lower }
            ?.map { CameraCapabilityFpsRange(it.lower, it.upper) }
            ?.distinct()
            ?.sortedWith(compareBy<CameraCapabilityFpsRange> { it.upper }.thenBy { it.lower })
            ?.toList()
            .orEmpty()

        val sensorRect =
            characteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
                ?: characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val sensorExtent = sensorRect
            ?.takeIf { it.width() > 0 && it.height() > 0 }
            ?.let { CameraCapabilityExtent(it.width(), it.height()) }

        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return CameraCapabilityInventory(
                requestedLensId = requestedLensId,
                logicalCameraId = logicalCameraId,
                physicalCameraId = physicalCameraId,
                source = source,
                hardwareLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL),
                requestCapabilities = requestCapabilities,
                aeFpsRanges = fpsFacts,
                sensorExtent = sensorExtent,
                viewfinderExtents = emptyList(),
                formats = emptyList(),
                warnings = warnings + "SCALER_STREAM_CONFIGURATION_MAP is unavailable."
            )

        val inventoryFormats = map.outputFormats
            .distinct()
            .map { format ->
                val sizes = runCatching { map.getOutputSizes(format)?.toList().orEmpty() }
                    .getOrDefault(emptyList())
                    .filter { it.width > 0 && it.height > 0 }
                    // Preserve Camera2's active format-size list order. GCam's specific RAW
                    // resolution preference addresses this list by index; downstream policies
                    // may sort/select without mutating the discovery order stored in inventory.
                    .distinctBy { it.width to it.height }
                    .map { size ->
                        val durationNs = runCatching { map.getOutputMinFrameDuration(format, size) }
                            .getOrNull()
                            ?.takeIf { it > 0L }
                        CameraCapabilitySize(
                            extent = CameraCapabilityExtent(size.width, size.height),
                            minFrameDurationNs = durationNs
                        )
                    }
                val kind = cameraCapabilityFormatKind(format)
                val defaultAvailability = CameraPhotoFormatPolicy.defaultRuntimeAvailability(kind)
                val runtimeAvailability = if (
                    !rawCapabilityAdvertised &&
                    (kind == CameraCapabilityFormatKind.RAW10 ||
                        kind == CameraCapabilityFormatKind.RAW_SENSOR)
                ) {
                    CameraCapabilityRuntimeAvailability.DISCOVERY_ONLY
                } else {
                    defaultAvailability
                }
                CameraCapabilityFormat(
                    formatCode = format,
                    formatName = cameraFormatName(format),
                    kind = kind,
                    runtimeAvailability = runtimeAvailability,
                    sizes = sizes
                )
            }
            .sortedWith(compareBy<CameraCapabilityFormat> { it.formatName }.thenBy { it.formatCode })

        val previewExtents = runCatching {
            map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        }.getOrDefault(emptyList())
            .filter { it.width > 0 && it.height > 0 }
            .distinctBy { it.width to it.height }
            .map { CameraCapabilityExtent(it.width, it.height) }

        return CameraCapabilityInventory(
            requestedLensId = requestedLensId,
            logicalCameraId = logicalCameraId,
            physicalCameraId = physicalCameraId,
            source = source,
            hardwareLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL),
            requestCapabilities = requestCapabilities,
            aeFpsRanges = fpsFacts,
            sensorExtent = sensorExtent,
            viewfinderExtents = previewExtents,
            formats = inventoryFormats,
            warnings = warnings
        )
    }
}

object CameraStreamCapabilityScanner {
    fun scan(context: Context, lensId: String): CameraStreamCapabilityCatalog {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val warnings = mutableListOf<String>()
        val publicIds = runCatching { manager.cameraIdList.toSet() }.getOrDefault(emptySet())
        val resolved = resolveCharacteristics(manager, publicIds, lensId)
            ?: return CameraStreamCapabilityCatalog(
                inventory = CameraCapabilityInventory(
                    requestedLensId = lensId,
                    logicalCameraId = null,
                    physicalCameraId = null,
                    source = CameraStreamCatalogSource.UNAVAILABLE,
                    hardwareLevel = null,
                    requestCapabilities = emptySet(),
                    aeFpsRanges = emptyList(),
                    sensorExtent = null,
                    viewfinderExtents = emptyList(),
                    formats = emptyList(),
                    warnings = listOf("CameraCharacteristics are unavailable for Lens ID $lensId.")
                ),
                sessionQuerySupported = false
            )

        if (resolved.source == CameraStreamCatalogSource.LOGICAL_PARENT_FALLBACK) {
            warnings += "Physical characteristics were unavailable; this catalog uses the logical parent as a fallback and is not sensor-exclusive."
        }

        val chars = resolved.characteristics
        val mapAvailable = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) != null
        val sessionQueryCameraId = resolved.logicalCameraId.takeIf { it in publicIds }
        val sessionQuerySupported = mapAvailable &&
            Build.VERSION.SDK_INT >= 35 &&
            sessionQueryCameraId != null &&
            CameraSessionPreflight.isSetupSupported(manager, sessionQueryCameraId)
        if (mapAvailable && Build.VERSION.SDK_INT >= 35 && !sessionQuerySupported) {
            warnings += "CameraDeviceSetup session preflight is not available for this camera route; runtime combination checks remain unavailable until session creation."
        }

        val inventory = CameraCapabilityInventoryFactory.fromCharacteristics(
            requestedLensId = lensId,
            logicalCameraId = resolved.logicalCameraId,
            physicalCameraId = resolved.physicalCameraId,
            source = resolved.source,
            characteristics = chars,
            warnings = warnings
        )
        if (!mapAvailable) {
            return CameraStreamCapabilityCatalog(
                inventory = inventory,
                sessionQuerySupported = false
            )
        }

        return CameraStreamCapabilityCatalog(
            inventory = inventory,
            sessionQuerySupported = sessionQuerySupported
        )
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


}


@android.annotation.TargetApi(35)
internal data class CameraSessionPreflightResult(
    val status: StreamCandidateValidationStatus,
    val reason: String
)

/**
 * Single Camera2/HAL session-preflight authority for runtime session diagnostics/recovery.
 * Capability discovery never invokes this validation path. It never opens a CameraDevice and
 * never mutates the active BnCam session.
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

private fun cameraCapabilityFormatKind(format: Int): CameraCapabilityFormatKind = when (format) {
    ImageFormat.RAW10 -> CameraCapabilityFormatKind.RAW10
    ImageFormat.RAW12 -> CameraCapabilityFormatKind.RAW12
    ImageFormat.RAW_SENSOR -> CameraCapabilityFormatKind.RAW_SENSOR
    ImageFormat.YUV_420_888 -> CameraCapabilityFormatKind.YUV_420_888
    else -> CameraCapabilityFormatKind.OTHER
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
