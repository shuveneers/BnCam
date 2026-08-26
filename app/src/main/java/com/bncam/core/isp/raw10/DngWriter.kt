package com.bncam.core.isp.raw10

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.util.Log
import android.util.Size
import com.bncam.core.isp.raw.RawDomainContract
import java.io.OutputStream
import java.nio.ByteBuffer


object DngWriter {
    private const val TAG = "DngWriter"



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
        calibration: com.bncam.core.quality.FinalSensorCalibration? = null, // Optioneel om build errors in callers te voorkomen
        rawDomainContract: RawDomainContract? = null
    ): Long? {
        return try {
            if (!isValidRaw16Payload(width, height, raw16Bytes)) {
                Log.e(TAG, "Invalid virtual RAW16 payload: ${raw16Bytes.size} bytes for ${width}x$height")
                return null
            }

            // Stage D Non-Destructive Column Statistics Audit
            com.bncam.core.isp.raw.RawColumnStatsAuditor.auditRaw16ByteArray(
                stage = "Stage-D (Pre-DngCreator)",
                raw16Bytes = raw16Bytes,
                width = width,
                height = height
            )

            val pixSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val preCorrRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
            val actRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            Log.i(TAG, "DngCreator Geometry Audit: pixelArraySize=$pixSize preCorrectionActiveArraySize=$preCorrRect activeArraySize=$actRect streamBufferDimensions=${width}x$height byteBufferCapacity=${raw16Bytes.size}")

            val auditOut = CountingAuditOutputStream(outputStream)
            val exifOrientation = degreesToExifOrientation(orientation)
            DngCreator(characteristics, metadata).useSafely { dngCreator ->
                dngCreator.setOrientation(exifOrientation)

                // Description remains human-readable debug only. Semantic correctness is checked
                // by DngSemanticAuditor against real DNG tags and the RAW payload domain.
                val description = buildString {
                    append("BnCam DNG Engine V2")
                    append(" | Semantic audit enabled")

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
            DngSemanticAuditor.audit(
                width = width,
                height = height,
                raw16Bytes = raw16Bytes,
                bytesWritten = auditOut.bytesWritten,
                capturedHeader = auditOut.capturedBytes(),
                orientationExif = exifOrientation,
                contract = rawDomainContract,
                characteristics = characteristics
            )
            auditOut.bytesWritten
        } catch (t: Throwable) {
            Log.e(TAG, "DngCreator execution failed", t)
            null
        }
    }

    fun lastAuditReport(): String = DngSemanticAuditor.lastReportString()

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
        private val captured = java.io.ByteArrayOutputStream(16 * 1024 * 1024)
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
