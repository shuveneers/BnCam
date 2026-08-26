package com.bncam.core.isp.raw10

import android.hardware.camera2.CameraCharacteristics
import android.util.Log
import com.bncam.core.isp.raw.RawDomainContract
import java.nio.ByteOrder

data class DngAuditItem(
    val name: String,
    val passed: Boolean,
    val detail: String
)

data class DngAuditReport(
    val passed: Boolean,
    val bytesWritten: Long,
    val items: List<DngAuditItem>
) {
    fun compact(): String {
        val status = if (passed) "PASS" else "FAIL"
        return buildString {
            append("DNG_AUDIT:")
            append("status=$status")
            append(";bytesWritten=$bytesWritten")
            items.forEach { item ->
                append(";")
                append(item.name.replace(' ', '_'))
                append("=")
                append(if (item.passed) "PASS" else "FAIL")
                append("(")
                append(item.detail.replace(';', ','))
                append(")")
            }
        }
    }
}

object DngSemanticAuditor {
    private const val TAG = "DngSemanticAuditor"

    @Volatile
    private var lastReport: DngAuditReport? = null

    fun lastReportString(): String = lastReport?.compact() ?: "DNG_AUDIT:status=NOT_RUN"

    fun audit(
        width: Int,
        height: Int,
        raw16Bytes: ByteArray,
        bytesWritten: Long,
        capturedHeader: ByteArray,
        orientationExif: Int,
        contract: RawDomainContract?,
        characteristics: CameraCharacteristics?
    ): DngAuditReport {
        val items = mutableListOf<DngAuditItem>()
        items += DngSemanticRules.validatePayload(
            width = width,
            height = height,
            raw16Bytes = raw16Bytes,
            payloadWhiteLevel = contract?.payloadWhiteLevel,
            sourceLabel = contract?.sourceFormat?.name ?: "unknown"
        )
        items += DngAuditItem("Bytes written", bytesWritten > 8L, "bytesWritten=$bytesWritten")

        val tags = TiffHeaderParser.parse(capturedHeader)
        if (tags == null) {
            items += DngAuditItem("TIFF header", false, "header_not_parseable_or_ifd_not_captured")
        } else {
            items += DngSemanticRules.validateTags(
                snapshot = DngTagSnapshot(
                    presentTagIds = tags.keys,
                    blackLevels = tags[50714]?.valuesAsLongs(16).orEmpty(),
                    whiteLevels = tags[50717]?.valuesAsLongs(4).orEmpty(),
                    orientation = tags[274]?.valuesAsLongs(1)?.firstOrNull(),
                    imageWidth = tags[256]?.valuesAsLongs(1)?.firstOrNull(),
                    imageHeight = tags[257]?.valuesAsLongs(1)?.firstOrNull(),
                    activeArea = tags[50829]?.valuesAsLongs(4).orEmpty(),
                    defaultCropOrigin = tags[50719]?.valuesAsLongs(2).orEmpty(),
                    defaultCropSize = tags[50720]?.valuesAsLongs(2).orEmpty()
                ),
                expectedOrientation = orientationExif,
                expectedWhiteLevel = contract?.payloadWhiteLevel,
                expectedBlackLevels = contract?.payloadBlackLevels
            )
            val geometrySnapshot = DngTagSnapshot(
                presentTagIds = tags.keys,
                blackLevels = tags[50714]?.valuesAsLongs(16).orEmpty(),
                whiteLevels = tags[50717]?.valuesAsLongs(4).orEmpty(),
                orientation = tags[274]?.valuesAsLongs(1)?.firstOrNull(),
                imageWidth = tags[256]?.valuesAsLongs(1)?.firstOrNull(),
                imageHeight = tags[257]?.valuesAsLongs(1)?.firstOrNull(),
                activeArea = tags[50829]?.valuesAsLongs(4).orEmpty(),
                defaultCropOrigin = tags[50719]?.valuesAsLongs(2).orEmpty(),
                defaultCropSize = tags[50720]?.valuesAsLongs(2).orEmpty()
            )
            val pixelArray = characteristics?.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val activeArray = characteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            items += DngSemanticRules.validateGeometry(
                snapshot = geometrySnapshot,
                payloadWidth = width,
                payloadHeight = height,
                cameraPixelArrayWidth = pixelArray?.width,
                cameraPixelArrayHeight = pixelArray?.height,
                cameraActiveLeft = activeArray?.left,
                cameraActiveTop = activeArray?.top,
                cameraActiveRight = activeArray?.right,
                cameraActiveBottom = activeArray?.bottom
            )
        }

        val stripOffset = tags?.get(273)?.valuesAsLongs(1)?.firstOrNull()?.toInt() ?: -1
        val stripByteCount = tags?.get(279)?.valuesAsLongs(1)?.firstOrNull()?.toInt() ?: (width * height * 2)

        val dngPayloadBytes: ByteArray = if (stripOffset > 0 && stripOffset + stripByteCount <= capturedHeader.size) {
            capturedHeader.copyOfRange(stripOffset, stripOffset + stripByteCount)
        } else {
            raw16Bytes
        }

        com.bncam.core.isp.raw.RawColumnStatsAuditor.auditRaw16ByteArray(
            stage = "Stage-E (Resulting Written DNG File Payload)",
            raw16Bytes = dngPayloadBytes,
            width = width,
            height = height
        )

        val report = DngAuditReport(
            passed = items.all { it.passed },
            bytesWritten = bytesWritten,
            items = items
        )
        lastReport = report
        if (report.passed) {
            Log.i(TAG, report.compact())
        } else {
            Log.e(TAG, report.compact())
        }
        com.bncam.core.debug.DeviceTelemetryLogger.logEvent("DNG_SEMANTIC_AUDIT", report.compact())
        return report
    }

}

private data class TiffTag(
    val id: Int,
    val type: Int,
    val count: Long,
    val valueOffset: Int,
    val inlineValueOffset: Int,
    val data: ByteArray,
    val order: ByteOrder
) {
    fun valuesAsLongs(maxValues: Int): List<Long>? {
        val size = typeSize(type) ?: return null
        val totalSize = count * size
        val base = if (totalSize <= 4L) inlineValueOffset else valueOffset
        if (base < 0 || base >= data.size) return null
        val values = mutableListOf<Long>()
        val capped = minOf(count, maxValues.toLong()).toInt()
        for (i in 0 until capped) {
            val offset = base + i * size
            if (offset + size > data.size) return null
            values += when (type) {
                1, 7 -> data[offset].toInt().and(0xFF).toLong()
                3 -> readUShort(data, offset, order).toLong()
                4 -> readUInt(data, offset, order)
                5 -> {
                    if (offset + 8 > data.size) return null
                    val num = readUInt(data, offset, order)
                    val den = readUInt(data, offset + 4, order).coerceAtLeast(1L)
                    num / den
                }
                else -> return null
            }
        }
        return values
    }
}

private object TiffHeaderParser {
    fun parse(data: ByteArray): Map<Int, TiffTag>? {
        if (data.size < 8) return null
        val order = when {
            data[0] == 'I'.code.toByte() && data[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
            data[0] == 'M'.code.toByte() && data[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
            else -> return null
        }
        if (readUShort(data, 2, order) != 42) return null
        val ifdOffset = readUInt(data, 4, order).toInt()
        if (ifdOffset < 8 || ifdOffset + 2 > data.size) return null
        val entryCount = readUShort(data, ifdOffset, order)
        val tags = linkedMapOf<Int, TiffTag>()
        var entryOffset = ifdOffset + 2
        repeat(entryCount) {
            if (entryOffset + 12 > data.size) return@repeat
            val tag = readUShort(data, entryOffset, order)
            val type = readUShort(data, entryOffset + 2, order)
            val count = readUInt(data, entryOffset + 4, order)
            val value = readUInt(data, entryOffset + 8, order).toInt()
            tags[tag] = TiffTag(
                id = tag,
                type = type,
                count = count,
                valueOffset = value,
                inlineValueOffset = entryOffset + 8,
                data = data,
                order = order
            )
            entryOffset += 12
        }
        return tags
    }
}

private fun typeSize(type: Int): Int? = when (type) {
    1, 2, 6, 7 -> 1
    3, 8 -> 2
    4, 9, 11 -> 4
    5, 10, 12 -> 8
    else -> null
}

private fun readUShort(data: ByteArray, offset: Int, order: ByteOrder): Int {
    if (offset + 2 > data.size) return 0
    val a = data[offset].toInt() and 0xFF
    val b = data[offset + 1].toInt() and 0xFF
    return if (order == ByteOrder.LITTLE_ENDIAN) a or (b shl 8) else (a shl 8) or b
}

private fun readUInt(data: ByteArray, offset: Int, order: ByteOrder): Long {
    if (offset + 4 > data.size) return 0
    val b0 = data[offset].toLong() and 0xFF
    val b1 = data[offset + 1].toLong() and 0xFF
    val b2 = data[offset + 2].toLong() and 0xFF
    val b3 = data[offset + 3].toLong() and 0xFF
    return if (order == ByteOrder.LITTLE_ENDIAN) {
        b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    } else {
        (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }
}
