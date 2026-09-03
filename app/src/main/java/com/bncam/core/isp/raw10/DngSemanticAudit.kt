package com.bncam.core.isp.raw10

import android.hardware.camera2.CameraCharacteristics
import android.util.Log
import com.bncam.core.isp.raw.RawDomainContract
import java.nio.ByteOrder
import java.util.ArrayDeque

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

data class DngHueSatMapProfileSnapshot(
    val available: Boolean = false,
    val valid: Boolean = false,
    val hueDivisions: Int = 0,
    val saturationDivisions: Int = 0,
    val valueDivisions: Int = 0,
    val encoding: Int = 0,
    val data1: FloatArray? = null,
    val data2: FloatArray? = null,
    val data3: FloatArray? = null,
    val status: String = "NOT_PRESENT"
) {
    fun copied(): DngHueSatMapProfileSnapshot = copy(
        data1 = data1?.copyOf(),
        data2 = data2?.copyOf(),
        data3 = data3?.copyOf()
    )
}

data class DngCameraColorProfileSnapshot(
    val available: Boolean = false,
    val valid: Boolean = false,
    val source: String = "OEM_DNGCREATOR",
    val calibrationProfileId: String = "unknown",
    val calibrationIlluminant1: Int = 0,
    val calibrationIlluminant2: Int = 0,
    val colorMatrix1: FloatArray? = null,
    val colorMatrix2: FloatArray? = null,
    val cameraCalibration1: FloatArray? = null,
    val cameraCalibration2: FloatArray? = null,
    val forwardMatrix1: FloatArray? = null,
    val forwardMatrix2: FloatArray? = null,
    val analogBalance: FloatArray = floatArrayOf(1.0f, 1.0f, 1.0f),
    val analogBalanceFromTag: Boolean = false,
    val discoveryEffectiveCcm: FloatArray? = null,
    val hueSatMap: DngHueSatMapProfileSnapshot = DngHueSatMapProfileSnapshot(),
    val status: String = "NOT_PRESENT"
) {
    fun copied(): DngCameraColorProfileSnapshot = copy(
        colorMatrix1 = colorMatrix1?.copyOf(),
        colorMatrix2 = colorMatrix2?.copyOf(),
        cameraCalibration1 = cameraCalibration1?.copyOf(),
        cameraCalibration2 = cameraCalibration2?.copyOf(),
        forwardMatrix1 = forwardMatrix1?.copyOf(),
        forwardMatrix2 = forwardMatrix2?.copyOf(),
        analogBalance = analogBalance.copyOf(),
        discoveryEffectiveCcm = discoveryEffectiveCcm?.copyOf(),
        hueSatMap = hueSatMap.copied()
    )
}

object DngSemanticAuditor {
    private const val TAG = "DngSemanticAuditor"

    @Volatile
    private var lastReport: DngAuditReport? = null

    @Volatile
    private var lastHueSatMapProfile: DngHueSatMapProfileSnapshot = DngHueSatMapProfileSnapshot()

    @Volatile
    private var lastCameraColorProfile: DngCameraColorProfileSnapshot = DngCameraColorProfileSnapshot()

    fun lastReportString(): String = lastReport?.compact() ?: "DNG_AUDIT:status=NOT_RUN"

    fun lastHueSatMapProfileSnapshot(): DngHueSatMapProfileSnapshot =
        lastHueSatMapProfile.copied()

    fun lastCameraColorProfileSnapshot(): DngCameraColorProfileSnapshot =
        lastCameraColorProfile.copied()

    fun parseCameraColorProfileBytes(
        bytes: ByteArray,
        calibrationProfileId: String,
        discoveryEffectiveCcm: FloatArray?,
        source: String
    ): DngCameraColorProfileSnapshot {
        val tags = TiffHeaderParser.parse(bytes)
            ?: return DngCameraColorProfileSnapshot(
                source = source,
                calibrationProfileId = calibrationProfileId,
                status = "TIFF_HEADER_UNAVAILABLE"
            )
        val hueSatMap = buildHueSatMapSnapshot(tags)
        return buildCameraColorProfileSnapshot(
            tags = tags,
            hueSatMap = hueSatMap,
            calibrationProfileId = calibrationProfileId,
            discoveryEffectiveCcm = discoveryEffectiveCcm,
            source = source
        )
    }

    fun audit(
        width: Int,
        height: Int,
        raw16Bytes: ByteArray,
        bytesWritten: Long,
        capturedHeader: ByteArray,
        orientationExif: Int,
        contract: RawDomainContract?,
        characteristics: CameraCharacteristics?,
        calibrationProfileId: String = "unknown",
        discoveryEffectiveCcm: FloatArray? = null
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
            lastHueSatMapProfile = DngHueSatMapProfileSnapshot(status = "TIFF_HEADER_UNAVAILABLE")
            lastCameraColorProfile = DngCameraColorProfileSnapshot(status = "TIFF_HEADER_UNAVAILABLE")
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

            val hueSatSnapshot = buildHueSatMapSnapshot(tags)
            lastHueSatMapProfile = hueSatSnapshot
            items += DngAuditItem(
                name = "HueSatMap profile",
                passed = !hueSatSnapshot.available || hueSatSnapshot.valid,
                detail = "available=${hueSatSnapshot.available},valid=${hueSatSnapshot.valid}," +
                    "dims=${hueSatSnapshot.hueDivisions}x${hueSatSnapshot.saturationDivisions}x${hueSatSnapshot.valueDivisions}," +
                    "encoding=${hueSatSnapshot.encoding},tables=" +
                    listOf(hueSatSnapshot.data1, hueSatSnapshot.data2, hueSatSnapshot.data3).count { it != null } +
                    ",status=${hueSatSnapshot.status}"
            )
            val cameraProfile = buildCameraColorProfileSnapshot(
                tags = tags,
                hueSatMap = hueSatSnapshot,
                calibrationProfileId = calibrationProfileId,
                discoveryEffectiveCcm = discoveryEffectiveCcm,
                source = "OEM_DNGCREATOR"
            )
            lastCameraColorProfile = cameraProfile
            items += DngAuditItem(
                name = "Camera color profile",
                passed = !cameraProfile.available || cameraProfile.valid,
                detail = "available=${cameraProfile.available},valid=${cameraProfile.valid}," +
                    "profileId=${cameraProfile.calibrationProfileId}," +
                    "illuminants=${cameraProfile.calibrationIlluminant1}/${cameraProfile.calibrationIlluminant2}," +
                    "colorMatrices=${listOf(cameraProfile.colorMatrix1, cameraProfile.colorMatrix2).count { it != null }}," +
                    "forwardMatrices=${listOf(cameraProfile.forwardMatrix1, cameraProfile.forwardMatrix2).count { it != null }}," +
                    "hsm=${cameraProfile.hueSatMap.available}/${cameraProfile.hueSatMap.valid}," +
                    "status=${cameraProfile.status}"
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

        val dngPayloadBytes: ByteArray = if (
            stripOffset > 0 && stripByteCount >= 0 &&
            stripOffset.toLong() + stripByteCount.toLong() <= capturedHeader.size.toLong()
        ) {
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
            if (offset < 0 || offset + size > data.size) return null
            values += when (type) {
                1, 7 -> data[offset].toInt().and(0xFF).toLong()
                3 -> readUShort(data, offset, order).toLong()
                4, 13 -> readUInt(data, offset, order)
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

    fun valuesAsFloats(maxValues: Int): FloatArray? {
        if (type != 11 || count <= 0L || count > Int.MAX_VALUE.toLong()) return null
        val size = 4
        val totalSize = count * size
        val base = if (totalSize <= 4L) inlineValueOffset else valueOffset
        if (base < 0 || base >= data.size) return null
        val capped = minOf(count, maxValues.toLong()).toInt()
        val out = FloatArray(capped)
        for (i in 0 until capped) {
            val offset = base + i * size
            if (offset < 0 || offset + size > data.size) return null
            out[i] = Float.fromBits(readUInt(data, offset, order).toInt())
            if (!out[i].isFinite()) return null
        }
        return out
    }

    fun valuesAsDoubles(maxValues: Int): DoubleArray? {
        val size = typeSize(type) ?: return null
        if (count <= 0L || count > Int.MAX_VALUE.toLong()) return null
        val totalSize = count * size
        val base = if (totalSize <= 4L) inlineValueOffset else valueOffset
        if (base < 0 || base >= data.size) return null
        val capped = minOf(count, maxValues.toLong()).toInt()
        val out = DoubleArray(capped)
        for (i in 0 until capped) {
            val offset = base + i * size
            if (offset < 0 || offset + size > data.size) return null
            val value = when (type) {
                3 -> readUShort(data, offset, order).toDouble()
                4, 13 -> readUInt(data, offset, order).toDouble()
                5 -> {
                    if (offset + 8 > data.size) return null
                    val num = readUInt(data, offset, order).toDouble()
                    val den = readUInt(data, offset + 4, order).toDouble()
                    if (den == 0.0) return null
                    num / den
                }
                9 -> readInt32(data, offset, order).toDouble()
                10 -> {
                    if (offset + 8 > data.size) return null
                    val num = readInt32(data, offset, order).toDouble()
                    val den = readInt32(data, offset + 4, order).toDouble()
                    if (den == 0.0) return null
                    num / den
                }
                11 -> Float.fromBits(readUInt(data, offset, order).toInt()).toDouble()
                12 -> Double.fromBits(readUInt64(data, offset, order))
                else -> return null
            }
            if (!value.isFinite()) return null
            out[i] = value
        }
        return out
    }
}

private fun buildHueSatMapSnapshot(tags: Map<Int, TiffTag>): DngHueSatMapProfileSnapshot {
    val dimsTag = tags[50937] ?: return DngHueSatMapProfileSnapshot(status = "NOT_PRESENT")
    val dims = dimsTag.valuesAsLongs(3)
        ?: return DngHueSatMapProfileSnapshot(available = true, status = "DIMS_UNREADABLE")
    if (dims.size !in 2..3) {
        return DngHueSatMapProfileSnapshot(available = true, status = "DIMS_COUNT_INVALID")
    }
    val h = dims[0].toInt()
    val s = dims[1].toInt()
    val v = if (dims.size >= 3) dims[2].toInt() else 1
    if (h < 1 || s < 2 || v < 1) {
        return DngHueSatMapProfileSnapshot(true, false, h, s, v, status = "DIMS_INVALID")
    }
    val entries = h.toLong() * s.toLong() * v.toLong()
    val fullFloatCountLong = entries * 3L
    val skippedSat0FloatCountLong = h.toLong() * (s - 1).toLong() * v.toLong() * 3L
    if (fullFloatCountLong <= 0L || fullFloatCountLong > Int.MAX_VALUE.toLong()) {
        return DngHueSatMapProfileSnapshot(true, false, h, s, v, status = "TABLE_SIZE_OVERFLOW")
    }
    val fullFloatCount = fullFloatCountLong.toInt()

    fun readTable(tag: Int): FloatArray? {
        val t = tags[tag] ?: return null
        if (t.type != 11) return FloatArray(0)
        if (t.count == fullFloatCountLong) {
            return t.valuesAsFloats(fullFloatCount) ?: FloatArray(0)
        }
        if (t.count == skippedSat0FloatCountLong) {
            val compact = t.valuesAsFloats(skippedSat0FloatCountLong.toInt()) ?: return FloatArray(0)
            val dense = FloatArray(fullFloatCount)
            var src = 0
            for (valueIndex in 0 until v) {
                for (hueIndex in 0 until h) {
                    var dst = ((valueIndex * h + hueIndex) * s) * 3
                    dense[dst] = 0.0f
                    dense[dst + 1] = 1.0f
                    dense[dst + 2] = 1.0f
                    dst += 3
                    repeat(s - 1) {
                        dense[dst] = compact[src]
                        dense[dst + 1] = compact[src + 1]
                        dense[dst + 2] = compact[src + 2]
                        src += 3
                        dst += 3
                    }
                }
            }
            return dense
        }
        return FloatArray(0)
    }

    val data1 = readTable(50938)
    val data2 = readTable(50939)
    val data3 = readTable(52537)
    if (data1 == null) {
        return DngHueSatMapProfileSnapshot(true, false, h, s, v, status = "DATA1_MISSING")
    }
    if (data1.size != fullFloatCount || data2?.size == 0 || data3?.size == 0) {
        return DngHueSatMapProfileSnapshot(true, false, h, s, v, status = "TABLE_TYPE_COUNT_OR_RANGE_INVALID")
    }
    if (data3 != null && data2 == null) {
        return DngHueSatMapProfileSnapshot(true, false, h, s, v, status = "DATA3_WITHOUT_DATA2")
    }
    val encoding = tags[51107]?.valuesAsLongs(1)?.firstOrNull()?.toInt() ?: 0
    if (encoding !in 0..1) {
        return DngHueSatMapProfileSnapshot(true, false, h, s, v, encoding, status = "ENCODING_UNSUPPORTED")
    }

    fun tableValid(table: FloatArray): Boolean {
        if (table.size != fullFloatCount) return false
        for (i in table.indices step 3) {
            val hueShift = table[i]
            val satScale = table[i + 1]
            val valueScale = table[i + 2]
            if (!hueShift.isFinite() || !satScale.isFinite() || !valueScale.isFinite()) return false
            if (satScale < 0.0f || valueScale < 0.0f) return false
        }
        for (valueIndex in 0 until v) {
            for (hueIndex in 0 until h) {
                val cell = ((valueIndex * h + hueIndex) * s) * 3
                if (kotlin.math.abs(table[cell + 2] - 1.0f) > 1.0e-5f) return false
            }
        }
        return true
    }
    if (!tableValid(data1) || (data2 != null && !tableValid(data2)) || (data3 != null && !tableValid(data3))) {
        return DngHueSatMapProfileSnapshot(true, false, h, s, v, encoding, status = "TABLE_DATA_INVALID")
    }
    return DngHueSatMapProfileSnapshot(
        available = true,
        valid = true,
        hueDivisions = h,
        saturationDivisions = s,
        valueDivisions = v,
        encoding = encoding,
        data1 = data1,
        data2 = data2,
        data3 = data3,
        status = if (data3 != null) "VALID_TRIPLE_TABLE" else if (data2 != null) "VALID_DUAL_TABLE" else "VALID_SINGLE_TABLE"
    )
}

private fun buildCameraColorProfileSnapshot(
    tags: Map<Int, TiffTag>,
    hueSatMap: DngHueSatMapProfileSnapshot,
    calibrationProfileId: String,
    discoveryEffectiveCcm: FloatArray?,
    source: String = "OEM_DNGCREATOR"
): DngCameraColorProfileSnapshot {
    fun matrix(tagId: Int): FloatArray? {
        val values = tags[tagId]?.valuesAsDoubles(9) ?: return null
        if (values.size != 9 || values.any { !it.isFinite() || kotlin.math.abs(it) > 64.0 }) return null
        return FloatArray(9) { values[it].toFloat() }
    }

    val illuminant1 = tags[50778]?.valuesAsLongs(1)?.firstOrNull()?.toInt() ?: 0
    val illuminant2 = tags[50779]?.valuesAsLongs(1)?.firstOrNull()?.toInt() ?: 0
    val cm1 = matrix(50721)
    val cm2 = matrix(50722)
    val cc1 = matrix(50723)
    val cc2 = matrix(50724)
    val fm1 = matrix(50964)
    val fm2 = matrix(50965)
    val analogBalanceTag = tags[50727]
    val analogBalanceValues = analogBalanceTag?.valuesAsDoubles(3)
    val analogBalanceMalformed = analogBalanceTag != null && (
        analogBalanceValues == null || analogBalanceValues.size != 3 ||
            analogBalanceValues.any { !it.isFinite() || it <= 0.0 || it > 64.0 }
        )
    val analogBalance = if (analogBalanceTag == null) {
        floatArrayOf(1.0f, 1.0f, 1.0f)
    } else if (!analogBalanceMalformed) {
        FloatArray(3) { analogBalanceValues!![it].toFloat() }
    } else {
        floatArrayOf(1.0f, 1.0f, 1.0f)
    }
    val analogBalanceFromTag = analogBalanceTag != null && !analogBalanceMalformed
    val discoveryCcm = discoveryEffectiveCcm
        ?.takeIf { it.size == 9 && it.all(Float::isFinite) }
        ?.copyOf()

    fun snapshot(status: String, valid: Boolean = false): DngCameraColorProfileSnapshot =
        DngCameraColorProfileSnapshot(
            available = cm1 != null || illuminant1 != 0 || hueSatMap.available,
            valid = valid,
            source = source,
            calibrationProfileId = calibrationProfileId,
            calibrationIlluminant1 = illuminant1,
            calibrationIlluminant2 = illuminant2,
            colorMatrix1 = cm1,
            colorMatrix2 = cm2,
            cameraCalibration1 = cc1,
            cameraCalibration2 = cc2,
            forwardMatrix1 = fm1,
            forwardMatrix2 = fm2,
            analogBalance = analogBalance,
            analogBalanceFromTag = analogBalanceFromTag,
            discoveryEffectiveCcm = discoveryCcm,
            hueSatMap = hueSatMap,
            status = status
        )

    if (hueSatMap.available && !hueSatMap.valid) return snapshot("HUESATMAP_INVALID")
    if (analogBalanceMalformed) return snapshot("ANALOG_BALANCE_MALFORMED")
    if (hueSatMap.data3 != null) {
        return snapshot("TRIPLE_ILLUMINANT_CHARACTERIZATION_NOT_EXPOSED_BY_ANDROID_CONTRACT")
    }
    if (illuminant1 == 0 || cm1 == null || fm1 == null) {
        return snapshot("PRIMARY_CHARACTERIZATION_INCOMPLETE")
    }

    val secondaryAny = illuminant2 != 0 || cm2 != null || cc2 != null || fm2 != null
    val secondaryComplete = illuminant2 != 0 && cm2 != null && fm2 != null
    if (secondaryAny && !secondaryComplete) return snapshot("SECONDARY_CHARACTERIZATION_INCOMPLETE")
    if (hueSatMap.data2 != null && !secondaryComplete) {
        return snapshot("DUAL_HUESATMAP_WITHOUT_DUAL_CHARACTERIZATION")
    }
    if (discoveryCcm == null) return snapshot("DISCOVERY_CCM_MISSING")

    val status = when {
        hueSatMap.available && hueSatMap.data2 != null -> "VALID_DUAL_HUESATMAP_CAMERA_PROFILE"
        hueSatMap.available -> "VALID_CAMERA_PROFILE_WITH_SINGLE_HUESATMAP"
        secondaryComplete -> "VALID_DUAL_MATRIX_CAMERA_PROFILE_NO_HUESATMAP"
        else -> "VALID_SINGLE_MATRIX_CAMERA_PROFILE_NO_HUESATMAP"
    }
    return snapshot(status = status, valid = true)
}

private object TiffHeaderParser {
    private const val MAX_IFDS = 64
    private const val MAX_ENTRIES_PER_IFD = 4096
    private const val MAX_SUB_IFDS_PER_IFD = 32

    fun parse(data: ByteArray): Map<Int, TiffTag>? {
        if (data.size < 8) return null
        val order = when {
            data[0] == 'I'.code.toByte() && data[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
            data[0] == 'M'.code.toByte() && data[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
            else -> return null
        }
        if (readUShort(data, 2, order) != 42) return null
        val firstIfd = readUInt(data, 4, order)
        if (firstIfd < 8L || firstIfd > Int.MAX_VALUE.toLong()) return null

        val queue = ArrayDeque<Int>()
        queue.add(firstIfd.toInt())
        val visited = HashSet<Int>()
        val tags = linkedMapOf<Int, TiffTag>()

        while (queue.isNotEmpty() && visited.size < MAX_IFDS) {
            val ifdOffset = queue.removeFirst()
            if (!visited.add(ifdOffset)) continue
            if (ifdOffset < 8 || ifdOffset.toLong() + 2L > data.size.toLong()) continue

            val entryCount = readUShort(data, ifdOffset, order)
            if (entryCount > MAX_ENTRIES_PER_IFD) continue
            val entriesStart = ifdOffset.toLong() + 2L
            val entriesBytes = entryCount.toLong() * 12L
            val nextOffsetLocation = entriesStart + entriesBytes
            if (nextOffsetLocation + 4L > data.size.toLong()) continue

            var entryOffset = entriesStart.toInt()
            val localTags = mutableListOf<TiffTag>()
            repeat(entryCount) {
                if (entryOffset.toLong() + 12L > data.size.toLong()) return@repeat
                val tag = readUShort(data, entryOffset, order)
                val type = readUShort(data, entryOffset + 2, order)
                val count = readUInt(data, entryOffset + 4, order)
                val valueLong = readUInt(data, entryOffset + 8, order)
                val value = if (valueLong <= Int.MAX_VALUE.toLong()) valueLong.toInt() else -1
                val parsed = TiffTag(
                    id = tag,
                    type = type,
                    count = count,
                    valueOffset = value,
                    inlineValueOffset = entryOffset + 8,
                    data = data,
                    order = order
                )
                localTags += parsed
                tags.putIfAbsent(tag, parsed)
                entryOffset += 12
            }

            val nextIfdLong = readUInt(data, nextOffsetLocation.toInt(), order)
            if (nextIfdLong in 8L..Int.MAX_VALUE.toLong()) {
                val next = nextIfdLong.toInt()
                if (next !in visited) queue.add(next)
            }

            localTags.firstOrNull { it.id == 330 }
                ?.valuesAsLongs(MAX_SUB_IFDS_PER_IFD)
                .orEmpty()
                .forEach { offset ->
                    if (offset in 8L..Int.MAX_VALUE.toLong()) {
                        val child = offset.toInt()
                        if (child !in visited) queue.add(child)
                    }
                }
        }

        return tags.takeIf { it.isNotEmpty() }
    }
}

private fun typeSize(type: Int): Int? = when (type) {
    1, 2, 6, 7 -> 1
    3, 8 -> 2
    4, 9, 11, 13 -> 4
    5, 10, 12 -> 8
    else -> null
}

private fun readUShort(data: ByteArray, offset: Int, order: ByteOrder): Int {
    if (offset < 0 || offset + 2 > data.size) return 0
    val a = data[offset].toInt() and 0xFF
    val b = data[offset + 1].toInt() and 0xFF
    return if (order == ByteOrder.LITTLE_ENDIAN) a or (b shl 8) else (a shl 8) or b
}

private fun readUInt(data: ByteArray, offset: Int, order: ByteOrder): Long {
    if (offset < 0 || offset + 4 > data.size) return 0
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

private fun readInt32(data: ByteArray, offset: Int, order: ByteOrder): Int =
    readUInt(data, offset, order).toInt()

private fun readUInt64(data: ByteArray, offset: Int, order: ByteOrder): Long {
    if (offset < 0 || offset + 8 > data.size) return 0L
    var out = 0L
    if (order == ByteOrder.LITTLE_ENDIAN) {
        for (i in 0 until 8) out = out or ((data[offset + i].toLong() and 0xFFL) shl (8 * i))
    } else {
        for (i in 0 until 8) out = (out shl 8) or (data[offset + i].toLong() and 0xFFL)
    }
    return out
}
