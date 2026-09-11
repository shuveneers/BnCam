package com.bncam.core.isp.raw10

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.os.Build
import android.util.Log
import android.util.Size
import com.bncam.core.isp.raw.RawDomainContract
import com.bncam.core.quality.CalibrationProfileBinding
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer

object DngWriter {
    private const val TAG = "DngWriter"
    @Volatile private var lastAuthorityReport: String = "DNG_AUTHORITY_NOT_EVALUATED"

    /**
     * Streams a virtual RAW16/Bayer buffer directly to an OutputStream.
     *
     * Use this when the caller already has a MediaStore output stream. It avoids the
     * additional complete DNG ByteArray allocation. The returned value is best-effort:
     * DngCreator does not expose the exact byte count, so this counts written bytes.
     */
    fun writeDngFromVirtualToStream(
        width: Int,
        height: Int,
        raw16Bytes: ByteArray,
        metadata: CaptureResult,
        characteristics: CameraCharacteristics,
        orientation: Int,
        dngMergeStats: String,
        lensHardwareDescription: String = "",
        outputStream: OutputStream,
        calibration: com.bncam.core.quality.FinalSensorCalibration? = null,
        rawDomainContract: RawDomainContract? = null
    ): Long? {
        return try {
            val binding = calibration?.base?.calibrationProfileBinding
            val expectedAuthority = binding?.provenance?.sensorAuthorityId
            val metadataTimestamp = metadata.get(CaptureResult.SENSOR_TIMESTAMP)
            val runtimeCameraId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                metadata.cameraId
            } else {
                expectedAuthority
            }
            val authorityDecision = RawDngAuthorityContract.evaluate(
                RawDngAuthorityContract.Input(
                    calibrationPresent = calibration != null && binding != null,
                    bindingSafe = binding?.safeForProfileBinding == true,
                    authorityProfileMatch = binding?.provenance?.authorityMatches == true,
                    characteristicsSourceMatches = binding?.provenance?.characteristicsSourceId == expectedAuthority,
                    captureResultSourceMatches = binding?.provenance?.captureResultSourceId == expectedAuthority,
                    runtimeCameraIdMatches = expectedAuthority != null && runtimeCameraId == expectedAuthority,
                    frameTimestampMatches = calibration?.base?.sensorTimestampNs != null &&
                        calibration.base.sensorTimestampNs == metadataTimestamp
                )
            )
            lastAuthorityReport = buildString {
                append("safe=").append(authorityDecision.safeForDng)
                append(";reason=").append(authorityDecision.reason)
                append(";authority=").append(expectedAuthority ?: "UNAVAILABLE")
                append(";profile=").append(binding?.calibrationProfileId ?: "UNAVAILABLE")
                append(";captureResultCameraId=").append(runtimeCameraId ?: "UNAVAILABLE")
                append(";timestampMatch=").append(
                    calibration?.base?.sensorTimestampNs != null &&
                        calibration.base.sensorTimestampNs == metadataTimestamp
                )
            }
            if (!authorityDecision.safeForDng) {
                Log.e(TAG, "DNG_AUTHORITY_BLOCKED:$lastAuthorityReport")
                return null
            }

            if (!isValidRaw16Payload(width, height, raw16Bytes)) {
                Log.e(TAG, "Invalid virtual RAW16 payload: ${raw16Bytes.size} bytes for ${width}x$height")
                return null
            }

            com.bncam.core.isp.raw.RawColumnStatsAuditor.auditRaw16ByteArray(
                stage = "Stage-D (Pre-DngCreator)",
                raw16Bytes = raw16Bytes,
                width = width,
                height = height
            )

            val pixSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val preCorrRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
            val actRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            Log.i(
                TAG,
                "DngCreator Geometry Audit: pixelArraySize=$pixSize preCorrectionActiveArraySize=$preCorrRect " +
                    "activeArraySize=$actRect streamBufferDimensions=${width}x$height byteBufferCapacity=${raw16Bytes.size}"
            )

            val auditOut = CountingAuditOutputStream(outputStream)
            val exifOrientation = degreesToExifOrientation(orientation)
            DngCreator(characteristics, metadata).useSafely { dngCreator ->
                dngCreator.setOrientation(exifOrientation)
                val description = buildString {
                    append("BnCam DNG Engine V2")
                    append(" | Semantic audit enabled")
                    append(" | DNG Authority=EXACT_FRAME_SENSOR_AUTHORITY")
                    append(" | SensorAuthority=").append(expectedAuthority ?: "UNAVAILABLE")
                    append(" | CalibrationFingerprint=")
                        .append(binding?.staticCalibrationFingerprint ?: "UNAVAILABLE")
                    if (calibration != null) {
                        append(" | BASE: BL=[${calibration.base.baseBlackLevels.joinToString(",")}] WL=${calibration.base.baseWhiteLevel}")
                        append(" | OVERRIDE: BL Mode=${calibration.override.blackLevelMode}")
                        append(" | FINAL C++ CONTRACT: BL=[${calibration.effectiveBlackLevels.joinToString(",")}] WL=${calibration.effectiveWhiteLevel} Scale=${calibration.blackLevelScaleFactor}")
                    } else if (lensHardwareDescription.isNotBlank()) {
                        append(" | Lens Hardware Settings: ")
                        append(lensHardwareDescription)
                    }
                    append(" | Merge Stats: ")
                    append(dngMergeStats)
                }
                dngCreator.setDescription(description)
                dngCreator.writeByteBuffer(
                    auditOut,
                    Size(width, height),
                    ByteBuffer.wrap(raw16Bytes),
                    0L
                )
            }
            val auditReport = DngSemanticAuditor.audit(
                width = width,
                height = height,
                raw16Bytes = raw16Bytes,
                bytesWritten = auditOut.bytesWritten,
                capturedHeader = auditOut.capturedBytes(),
                orientationExif = exifOrientation,
                contract = rawDomainContract,
                characteristics = characteristics,
                calibrationBinding = calibration?.base?.calibrationProfileBinding,
                discoveryEffectiveCcm = calibration?.effectiveColorMatrix?.copyOf()
            )
            if (!auditReport.passed) {
                Log.e(TAG, "DNG_CORRECTNESS_BLOCKED:${auditReport.compact()}")
                com.bncam.core.debug.DeviceTelemetryLogger.logEvent(
                    "DNG_CORRECTNESS_BLOCKED",
                    auditReport.compact()
                )
                return null
            }
            calibration?.base?.calibrationProfileBinding?.let { calibrationBinding ->
                RawCameraColorProfileRepository.installDiscoveredProfile(
                    snapshot = DngSemanticAuditor.lastCameraColorProfileSnapshot(),
                    calibrationBinding = calibrationBinding
                )
            }
            auditOut.bytesWritten
        } catch (t: Throwable) {
            Log.e(TAG, "DngCreator execution failed", t)
            null
        }
    }

    /**
     * One-time, pre-render camera-colour bootstrap used even when the visible output policy is
     * JPEG-only. The RAW16 frame is already available to BnCam's JPEG renderer, so this creates a
     * complete private DNG in memory, extracts only its DNG profile metadata and immediately drops
     * the DNG bytes. Nothing is inserted into MediaStore and no RAW/DNG URI is published.
     *
     * Keeping the complete private DNG is intentional. ProfileHueSatMap data and other profile tags
     * are TIFF-offset based and are not guaranteed to live inside an arbitrary prefix. Truncating
     * the write can therefore turn a present profile into a false NOT_PRESENT result.
     */
    internal fun discoverCameraColorProfileFromVirtualRaw16(
        width: Int,
        height: Int,
        raw16Buffer: ByteBuffer,
        metadata: CaptureResult,
        characteristics: CameraCharacteristics,
        calibrationBinding: CalibrationProfileBinding,
        discoveryEffectiveCcm: FloatArray?
    ): DngCameraColorProfileSnapshot? {
        val calibrationProfileId = calibrationBinding.calibrationProfileId
        if (width <= 0 || height <= 0 || !calibrationBinding.safeForProfileBinding ||
            calibrationProfileId.isBlank() || calibrationProfileId == "unknown" ||
            discoveryEffectiveCcm?.size != 9 || !raw16Buffer.isDirect
        ) {
            Log.i(
                TAG,
                "pre-render colour bootstrap skipped: invalid contract size=${width}x$height " +
                    "profileId=$calibrationProfileId bindingSafe=${calibrationBinding.safeForProfileBinding} " +
                    "bindingReason=${calibrationBinding.rejectionReason} direct=${raw16Buffer.isDirect} " +
                    "ccmSize=${discoveryEffectiveCcm?.size ?: 0}"
            )
            return null
        }
        val expectedBytes = width.toLong() * height.toLong() * 2L
        if (expectedBytes <= 0L || expectedBytes > Int.MAX_VALUE ||
            raw16Buffer.capacity().toLong() < expectedBytes
        ) {
            Log.w(
                TAG,
                "pre-render colour bootstrap skipped: RAW16 capacity=${raw16Buffer.capacity()} expected=$expectedBytes"
            )
            return null
        }

        return try {
            val privateDng = ByteArrayOutputStream(256 * 1024)
            DngCreator(characteristics, metadata).useSafely { dngCreator ->
                dngCreator.setDescription("BnCam internal unpublished camera colour bootstrap")
                val source = raw16Buffer.duplicate()
                source.clear()
                dngCreator.writeByteBuffer(
                    privateDng,
                    Size(width, height),
                    source,
                    0L
                )
            }
            val dngBytes = privateDng.toByteArray()
            DngSemanticAuditor.parseCameraColorProfileBytes(
                bytes = dngBytes,
                calibrationBinding = calibrationBinding,
                discoveryEffectiveCcm = discoveryEffectiveCcm.copyOf(),
                source = "OEM_DNGCREATOR_BOOTSTRAP"
            ).also { snapshot ->
                Log.i(
                    TAG,
                    "pre-render unpublished DNG parsed: id=$calibrationProfileId " +
                        "available=${snapshot.available} valid=${snapshot.valid} " +
                        "hsm=${snapshot.hueSatMap.available}/${snapshot.hueSatMap.valid} " +
                        "dims=${snapshot.hueSatMap.hueDivisions}x" +
                        "${snapshot.hueSatMap.saturationDivisions}x${snapshot.hueSatMap.valueDivisions} " +
                        "dngBytes=${dngBytes.size}"
                )
            }
        } catch (failure: Throwable) {
            Log.w(TAG, "pre-render unpublished DNG colour-profile discovery failed", failure)
            null
        }
    }

    fun lastAuditReport(): String =
        DngSemanticAuditor.lastReportString() + "; DngAuthority={" + lastAuthorityReport + "}"

    private fun isValidRaw16Payload(width: Int, height: Int, raw16Bytes: ByteArray): Boolean {
        if (width <= 0 || height <= 0) return false
        val expected = width.toLong() * height.toLong() * 2L
        return expected > 0L && expected <= Int.MAX_VALUE && raw16Bytes.size.toLong() == expected
    }

    private inline fun DngCreator.useSafely(block: (DngCreator) -> Unit) {
        try {
            block(this)
        } finally {
            try {
                close()
            } catch (t: Throwable) {
                Log.w(TAG, "DngCreator close failed", t)
            }
        }
    }

    private fun degreesToExifOrientation(orientation: Int): Int {
        return when (((orientation % 360) + 360) % 360) {
            90 -> 6
            180 -> 3
            270 -> 8
            else -> when (orientation) {
                1, 3, 6, 8 -> orientation
                else -> 1
            }
        }
    }

    private class CountingAuditOutputStream(
        private val delegate: OutputStream
    ) : OutputStream() {
        private val captureLimit = 64 * 1024 * 1024
        private val captured = ByteArrayOutputStream(16 * 1024 * 1024)
        var bytesWritten: Long = 0L
            private set

        fun capturedBytes(): ByteArray = captured.toByteArray()

        override fun write(b: Int) {
            delegate.write(b)
            if (captured.size() < captureLimit) captured.write(b)
            bytesWritten++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            val remaining = captureLimit - captured.size()
            if (remaining > 0) captured.write(b, off, minOf(len, remaining))
            bytesWritten += len.toLong()
        }

        override fun flush() = delegate.flush()
    }
}
