package com.bncam.core.isp.raw10

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

data class DngTagSnapshot(
    val presentTagIds: Set<Int>,
    val blackLevels: List<Long>,
    val whiteLevels: List<Long>,
    val orientation: Long?,
    val imageWidth: Long?,
    val imageHeight: Long?,
    val activeArea: List<Long>,
    val defaultCropOrigin: List<Long>,
    val defaultCropSize: List<Long>,
    val blackLevelValues: List<Double> = emptyList(),
    val dngVersion: List<Long> = emptyList(),
    val dngBackwardVersion: List<Long> = emptyList(),
    val bitsPerSample: Long? = null,
    val compression: Long? = null,
    val photometricInterpretation: Long? = null,
    val samplesPerPixel: Long? = null,
    val rowsPerStrip: Long? = null,
    val stripOffsets: List<Long> = emptyList(),
    val stripByteCounts: List<Long> = emptyList(),
    val cfaRepeatPatternDim: List<Long> = emptyList(),
    val cfaPattern: List<Long> = emptyList(),
    val blackLevelRepeatDim: List<Long> = emptyList(),
    val asShotNeutral: List<Double> = emptyList(),
    val noiseProfile: List<Double> = emptyList(),
    val littleEndian: Boolean? = null
)

object DngSemanticRules {
    private const val PHOTOMETRIC_CFA = 32803L
    private const val UNCOMPRESSED = 1L
    private const val RAW16_BITS = 16L
    private const val RAW_SAMPLES_PER_PIXEL = 1L
    private const val BLACK_LEVEL_ROUNDING_TOLERANCE = 0.51

    private val requiredSingleTags = linkedMapOf(
        50706 to "DNGVersion",
        50707 to "DNGBackwardVersion",
        50708 to "UniqueCameraModel",
        50714 to "BlackLevel",
        50717 to "WhiteLevel",
        33421 to "CFARepeatPatternDim",
        33422 to "CFAPattern",
        50829 to "ActiveArea",
        50719 to "DefaultCropOrigin",
        50720 to "DefaultCropSize",
        50728 to "AsShotNeutral",
        51041 to "NoiseProfile"
    )

    fun validateTags(
        snapshot: DngTagSnapshot,
        expectedOrientation: Int,
        expectedWhiteLevel: Int?,
        expectedBlackLevels: List<Int>?,
        expectedCfaPattern: Int? = null,
        cfaOriginX: Int = 0,
        cfaOriginY: Int = 0
    ): List<DngAuditItem> {
        val items = mutableListOf<DngAuditItem>()
        requiredSingleTags.forEach { (id, name) ->
            items += DngAuditItem(
                name,
                id in snapshot.presentTagIds,
                if (id in snapshot.presentTagIds) "present" else "missing_tag_$id"
            )
        }
        val colorMatrixTag = listOf(50721, 50722).firstOrNull { it in snapshot.presentTagIds }
        items += DngAuditItem(
            "ColorMatrix",
            colorMatrixTag != null,
            colorMatrixTag?.let { "present_tag_$it" } ?: "missing_tags_50721,50722"
        )
        val illuminantTag = listOf(50778, 50779).firstOrNull { it in snapshot.presentTagIds }
        items += DngAuditItem(
            "CalibrationIlluminant",
            illuminantTag != null,
            illuminantTag?.let { "present_tag_$it" } ?: "missing_tags_50778,50779"
        )

        items += validateVersion("DNGVersion value", snapshot.dngVersion)
        items += validateVersion("DNGBackwardVersion value", snapshot.dngBackwardVersion)
        if (snapshot.dngVersion.size == 4 && snapshot.dngBackwardVersion.size == 4) {
            val backwardNotNewer = compareVersion(snapshot.dngBackwardVersion, snapshot.dngVersion) <= 0
            items += DngAuditItem(
                "DNG backward compatibility",
                backwardNotNewer,
                "dng=${snapshot.dngVersion};backward=${snapshot.dngBackwardVersion}"
            )
        }

        items += DngAuditItem(
            "BitsPerSample",
            snapshot.bitsPerSample == RAW16_BITS,
            "expected=$RAW16_BITS actual=${snapshot.bitsPerSample ?: "missing"}"
        )
        items += DngAuditItem(
            "Compression",
            snapshot.compression == UNCOMPRESSED,
            "expected=UNCOMPRESSED/1 actual=${snapshot.compression ?: "missing"}"
        )
        items += DngAuditItem(
            "PhotometricInterpretation",
            snapshot.photometricInterpretation == PHOTOMETRIC_CFA,
            "expected=CFA/$PHOTOMETRIC_CFA actual=${snapshot.photometricInterpretation ?: "missing"}"
        )
        items += DngAuditItem(
            "SamplesPerPixel",
            snapshot.samplesPerPixel == RAW_SAMPLES_PER_PIXEL,
            "expected=$RAW_SAMPLES_PER_PIXEL actual=${snapshot.samplesPerPixel ?: "missing"}"
        )
        items += DngAuditItem(
            "CFARepeatPatternDim value",
            snapshot.cfaRepeatPatternDim == listOf(2L, 2L),
            "expected=[2, 2] actual=${snapshot.cfaRepeatPatternDim}"
        )
        items += DngAuditItem(
            "BlackLevelRepeatDim value",
            snapshot.blackLevelRepeatDim == listOf(2L, 2L),
            "expected=[2, 2] actual=${snapshot.blackLevelRepeatDim}"
        )

        if (expectedCfaPattern != null) {
            val expectedPattern = expectedDngCfaPattern(expectedCfaPattern, cfaOriginX, cfaOriginY)
            items += DngAuditItem(
                "CFAPattern value",
                snapshot.cfaPattern == expectedPattern,
                "expected=$expectedPattern actual=${snapshot.cfaPattern};" +
                    "androidCfa=$expectedCfaPattern;origin=$cfaOriginX,$cfaOriginY"
            )
        }

        items += DngAuditItem(
            "Orientation",
            snapshot.orientation == expectedOrientation.toLong(),
            "expected=$expectedOrientation actual=${snapshot.orientation ?: "missing"}"
        )
        if (expectedWhiteLevel != null) {
            val passed = snapshot.whiteLevels.isNotEmpty() && snapshot.whiteLevels.any { it == expectedWhiteLevel.toLong() }
            items += DngAuditItem(
                "WhiteLevel value",
                passed,
                "expected=$expectedWhiteLevel actual=${snapshot.whiteLevels.ifEmpty { listOf(-1L) }}"
            )
        }
        if (expectedBlackLevels != null) {
            val actual = if (snapshot.blackLevelValues.isNotEmpty()) {
                snapshot.blackLevelValues
            } else {
                snapshot.blackLevels.map(Long::toDouble)
            }.take(expectedBlackLevels.size)
            val passed = actual.size == expectedBlackLevels.size &&
                actual.zip(expectedBlackLevels).all { (value, expected) ->
                    value.isFinite() && abs(value - expected.toDouble()) <= BLACK_LEVEL_ROUNDING_TOLERANCE
                }
            items += DngAuditItem(
                "BlackLevel value",
                passed,
                "expected=$expectedBlackLevels actual=${actual.ifEmpty { listOf(Double.NaN) }};" +
                    "tolerance=$BLACK_LEVEL_ROUNDING_TOLERANCE"
            )
        }

        val neutralValid = snapshot.asShotNeutral.size == 3 &&
            snapshot.asShotNeutral.all { it.isFinite() && it > 0.0 }
        items += DngAuditItem(
            "AsShotNeutral value",
            neutralValid,
            "expected=3_positive_finite actual=${snapshot.asShotNeutral}"
        )
        val noiseFunctionCountValid = snapshot.noiseProfile.size == 2 || snapshot.noiseProfile.size == 6
        val noiseValid = noiseFunctionCountValid &&
            snapshot.noiseProfile.withIndex().all { (index, value) ->
                value.isFinite() && if ((index and 1) == 0) value > 0.0 else value >= 0.0
            }
        items += DngAuditItem(
            "NoiseProfile value",
            noiseValid,
            "expected=1_or_3_functions_with_positive_scale_nonnegative_offset;" +
                "actualCount=${snapshot.noiseProfile.size}"
        )
        return items
    }

    fun validateGeometry(
        snapshot: DngTagSnapshot,
        payloadWidth: Int,
        payloadHeight: Int,
        cameraPixelArrayWidth: Int?,
        cameraPixelArrayHeight: Int?,
        cameraActiveLeft: Int?,
        cameraActiveTop: Int?,
        cameraActiveRight: Int?,
        cameraActiveBottom: Int?
    ): List<DngAuditItem> {
        val items = mutableListOf<DngAuditItem>()
        val imageWidth = snapshot.imageWidth?.toInt()
        val imageHeight = snapshot.imageHeight?.toInt()
        items += DngAuditItem(
            "DNG image dimensions",
            imageWidth == payloadWidth && imageHeight == payloadHeight,
            "payload=${payloadWidth}x${payloadHeight};tag=${imageWidth ?: "missing"}x${imageHeight ?: "missing"}"
        )

        val active = snapshot.activeArea.takeIf { it.size >= 4 }
        val activeTop = active?.get(0)?.toInt()
        val activeLeft = active?.get(1)?.toInt()
        val activeBottom = active?.get(2)?.toInt()
        val activeRight = active?.get(3)?.toInt()
        val activeInsidePayload = activeTop != null && activeLeft != null && activeBottom != null && activeRight != null &&
            activeTop >= 0 && activeLeft >= 0 && activeBottom > activeTop && activeRight > activeLeft &&
            activeBottom <= payloadHeight && activeRight <= payloadWidth
        items += DngAuditItem(
            "ActiveArea bounds",
            activeInsidePayload,
            "tag=${active ?: "missing"};payload=${payloadWidth}x${payloadHeight}"
        )

        val origin = snapshot.defaultCropOrigin.takeIf { it.size >= 2 }
        val size = snapshot.defaultCropSize.takeIf { it.size >= 2 }
        val cropX = origin?.get(0)?.toInt()
        val cropY = origin?.get(1)?.toInt()
        val cropW = size?.get(0)?.toInt()
        val cropH = size?.get(1)?.toInt()
        val cropInsidePayload = cropX != null && cropY != null && cropW != null && cropH != null &&
            cropX >= 0 && cropY >= 0 && cropW > 0 && cropH > 0 &&
            cropX + cropW <= payloadWidth && cropY + cropH <= payloadHeight
        items += DngAuditItem(
            "DefaultCrop bounds",
            cropInsidePayload,
            "origin=${origin ?: "missing"};size=${size ?: "missing"};payload=${payloadWidth}x${payloadHeight}"
        )

        val fullPixelArrayComparable = cameraPixelArrayWidth == payloadWidth &&
            cameraPixelArrayHeight == payloadHeight && cameraActiveLeft != null &&
            cameraActiveTop != null && cameraActiveRight != null && cameraActiveBottom != null
        if (fullPixelArrayComparable && activeInsidePayload) {
            val excludesKnownInactiveLeft = if ((cameraActiveLeft ?: 0) > 0) {
                (activeLeft ?: 0) >= (cameraActiveLeft ?: 0) || (cropX ?: 0) >= (cameraActiveLeft ?: 0)
            } else true
            val activeDelta = listOf(
                (activeLeft ?: 0) - (cameraActiveLeft ?: 0),
                (activeTop ?: 0) - (cameraActiveTop ?: 0),
                (activeRight ?: 0) - (cameraActiveRight ?: 0),
                (activeBottom ?: 0) - (cameraActiveBottom ?: 0)
            )
            items += DngAuditItem(
                "Inactive border excluded",
                excludesKnownInactiveLeft,
                "cameraActive=[$cameraActiveTop,$cameraActiveLeft,$cameraActiveBottom,$cameraActiveRight];" +
                    "dngActive=$active;defaultOrigin=${origin ?: "missing"};activeDelta=$activeDelta"
            )
        } else {
            items += DngAuditItem(
                "DNG geometry comparability",
                true,
                "custom_or_precropped_stream;pixelArray=${cameraPixelArrayWidth ?: -1}x${cameraPixelArrayHeight ?: -1};" +
                    "payload=${payloadWidth}x${payloadHeight};active=$active;defaultOrigin=${origin ?: "missing"};defaultSize=${size ?: "missing"}"
            )
        }
        return items
    }

    fun validateUncompressedStripTopology(
        snapshot: DngTagSnapshot,
        payloadWidth: Int,
        payloadHeight: Int,
        capturedFileSize: Int
    ): List<DngAuditItem> {
        val items = mutableListOf<DngAuditItem>()
        val rowsPerStrip = snapshot.rowsPerStrip?.toInt() ?: 0
        val expectedStripCount = if (rowsPerStrip > 0 && payloadHeight > 0) {
            ceil(payloadHeight.toDouble() / rowsPerStrip.toDouble()).toInt()
        } else 0
        val countsMatch = expectedStripCount > 0 &&
            snapshot.stripOffsets.size == expectedStripCount &&
            snapshot.stripByteCounts.size == expectedStripCount
        items += DngAuditItem(
            "Strip topology",
            countsMatch,
            "rowsPerStrip=$rowsPerStrip;expectedStrips=$expectedStripCount;" +
                "offsets=${snapshot.stripOffsets.size};byteCounts=${snapshot.stripByteCounts.size}"
        )
        if (!countsMatch) return items

        var boundsSafe = true
        var byteCountsExact = snapshot.compression == UNCOMPRESSED
        var previousEnd = -1L
        snapshot.stripOffsets.indices.forEach { index ->
            val offset = snapshot.stripOffsets[index]
            val count = snapshot.stripByteCounts[index]
            val rows = minOf(rowsPerStrip, payloadHeight - index * rowsPerStrip)
            val expectedBytes = rows.toLong() * payloadWidth.toLong() * 2L
            if (offset < 0L || count <= 0L || offset + count > capturedFileSize.toLong() || offset < previousEnd) {
                boundsSafe = false
            }
            if (count != expectedBytes) byteCountsExact = false
            previousEnd = maxOf(previousEnd, offset + count)
        }
        items += DngAuditItem(
            "Strip bounds",
            boundsSafe,
            "capturedFileSize=$capturedFileSize;monotonicNonOverlapping=$boundsSafe"
        )
        items += DngAuditItem(
            "Uncompressed strip byte counts",
            byteCountsExact,
            "compression=${snapshot.compression ?: "missing"};width=$payloadWidth;height=$payloadHeight;rowsPerStrip=$rowsPerStrip"
        )
        return items
    }

    fun validateDngPayloadIdentity(
        snapshot: DngTagSnapshot,
        sourceRaw16Bytes: ByteArray,
        dngBytes: ByteArray,
        payloadWidth: Int,
        payloadHeight: Int
    ): DngAuditItem {
        if (snapshot.compression != UNCOMPRESSED || snapshot.bitsPerSample != RAW16_BITS ||
            snapshot.samplesPerPixel != RAW_SAMPLES_PER_PIXEL
        ) {
            return DngAuditItem(
                "DNG RAW payload identity",
                false,
                "unsupported_storage compression=${snapshot.compression};bits=${snapshot.bitsPerSample};samples=${snapshot.samplesPerPixel}"
            )
        }
        val rowsPerStrip = snapshot.rowsPerStrip?.toInt() ?: 0
        if (rowsPerStrip <= 0 || snapshot.stripOffsets.size != snapshot.stripByteCounts.size ||
            snapshot.stripOffsets.isEmpty()
        ) {
            return DngAuditItem("DNG RAW payload identity", false, "strip_layout_unavailable")
        }
        val expectedBytes = payloadWidth.toLong() * payloadHeight.toLong() * 2L
        if (sourceRaw16Bytes.size.toLong() != expectedBytes) {
            return DngAuditItem(
                "DNG RAW payload identity",
                false,
                "source_size_mismatch expected=$expectedBytes actual=${sourceRaw16Bytes.size}"
            )
        }
        var sourceOffset = 0
        var comparedSamples = 0L
        val littleEndian = snapshot.littleEndian != false
        for (index in snapshot.stripOffsets.indices) {
            val fileOffsetLong = snapshot.stripOffsets[index]
            val byteCountLong = snapshot.stripByteCounts[index]
            if (fileOffsetLong < 0L || byteCountLong <= 0L ||
                fileOffsetLong + byteCountLong > dngBytes.size.toLong() ||
                fileOffsetLong > Int.MAX_VALUE || byteCountLong > Int.MAX_VALUE
            ) {
                return DngAuditItem("DNG RAW payload identity", false, "strip_${index}_out_of_bounds")
            }
            val fileOffset = fileOffsetLong.toInt()
            val byteCount = byteCountLong.toInt()
            if ((byteCount and 1) != 0 || sourceOffset + byteCount > sourceRaw16Bytes.size) {
                return DngAuditItem("DNG RAW payload identity", false, "strip_${index}_size_invalid")
            }
            var local = 0
            while (local < byteCount) {
                val sourceLo = sourceRaw16Bytes[sourceOffset + local].toInt() and 0xFF
                val sourceHi = sourceRaw16Bytes[sourceOffset + local + 1].toInt() and 0xFF
                val dngA = dngBytes[fileOffset + local].toInt() and 0xFF
                val dngB = dngBytes[fileOffset + local + 1].toInt() and 0xFF
                val matches = if (littleEndian) {
                    sourceLo == dngA && sourceHi == dngB
                } else {
                    sourceLo == dngB && sourceHi == dngA
                }
                if (!matches) {
                    return DngAuditItem(
                        "DNG RAW payload identity",
                        false,
                        "first_mismatch_sourceByte=${sourceOffset + local};strip=$index;fileOffset=${fileOffset + local}"
                    )
                }
                local += 2
                comparedSamples++
            }
            sourceOffset += byteCount
        }
        val complete = sourceOffset == sourceRaw16Bytes.size
        return DngAuditItem(
            "DNG RAW payload identity",
            complete,
            "exact=$complete;bytesCompared=$sourceOffset;samplesCompared=$comparedSamples;byteOrder=${if (littleEndian) "LE" else "BE"}"
        )
    }

    fun validatePayload(
        width: Int,
        height: Int,
        raw16Bytes: ByteArray,
        payloadWhiteLevel: Int?,
        sourceLabel: String
    ): DngAuditItem {
        val expectedBytes = width.toLong() * height.toLong() * 2L
        if (width <= 0 || height <= 0 || raw16Bytes.size.toLong() != expectedBytes) {
            return DngAuditItem("Payload size", false, "expected=$expectedBytes actual=${raw16Bytes.size}")
        }
        if (payloadWhiteLevel == null) {
            return DngAuditItem("Payload contract", false, "RawDomainContract_missing")
        }
        val buffer = ByteBuffer.wrap(raw16Bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val step = maxOf(1, buffer.capacity() / 20000)
        var minValue = 65535
        var maxValue = 0
        var overWhite = 0
        var samples = 0
        var index = 0
        while (index < buffer.capacity()) {
            val value = buffer.get(index).toInt() and 0xFFFF
            minValue = minOf(minValue, value)
            maxValue = maxOf(maxValue, value)
            if (value > payloadWhiteLevel) overWhite++
            samples++
            index += step
        }
        val overWhitePct = if (samples > 0) overWhite * 100.0 / samples else 0.0
        return DngAuditItem(
            "Payload contract",
            minValue >= 0 && maxValue <= 65535 && overWhitePct <= 0.5,
            "source=$sourceLabel;payloadWhite=$payloadWhiteLevel;min=$minValue;max=$maxValue;overWhitePct=${String.format(Locale.US, "%.3f", overWhitePct)}"
        )
    }

    private fun validateVersion(name: String, version: List<Long>): DngAuditItem {
        val valid = version.size == 4 && version[0] == 1L &&
            version.all { it in 0L..255L }
        return DngAuditItem(name, valid, "actual=$version")
    }

    private fun compareVersion(a: List<Long>, b: List<Long>): Int {
        for (index in 0 until minOf(a.size, b.size, 4)) {
            if (a[index] < b[index]) return -1
            if (a[index] > b[index]) return 1
        }
        return a.size.compareTo(b.size)
    }

    private fun expectedDngCfaPattern(androidCfaPattern: Int, originX: Int, originY: Int): List<Long> {
        val base = when (androidCfaPattern) {
            0 -> arrayOf(longArrayOf(0, 1), longArrayOf(1, 2)) // RGGB
            1 -> arrayOf(longArrayOf(1, 0), longArrayOf(2, 1)) // GRBG
            2 -> arrayOf(longArrayOf(1, 2), longArrayOf(0, 1)) // GBRG
            3 -> arrayOf(longArrayOf(2, 1), longArrayOf(1, 0)) // BGGR
            else -> return emptyList()
        }
        val ox = originX and 1
        val oy = originY and 1
        return listOf(
            base[oy][ox],
            base[oy][ox xor 1],
            base[oy xor 1][ox],
            base[oy xor 1][ox xor 1]
        )
    }
}
