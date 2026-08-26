package com.bncam.core.isp.raw10

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

data class DngTagSnapshot(
    val presentTagIds: Set<Int>,
    val blackLevels: List<Long>,
    val whiteLevels: List<Long>,
    val orientation: Long?,
    val imageWidth: Long?,
    val imageHeight: Long?,
    val activeArea: List<Long>,
    val defaultCropOrigin: List<Long>,
    val defaultCropSize: List<Long>
)

object DngSemanticRules {
    private val requiredSingleTags = linkedMapOf(
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
        expectedBlackLevels: List<Int>?
    ): List<DngAuditItem> {
        val items = mutableListOf<DngAuditItem>()
        requiredSingleTags.forEach { (id, name) ->
            items += DngAuditItem(name, id in snapshot.presentTagIds, if (id in snapshot.presentTagIds) "present" else "missing_tag_$id")
        }
        val colorMatrixTag = listOf(50721, 50722).firstOrNull { it in snapshot.presentTagIds }
        items += DngAuditItem("ColorMatrix", colorMatrixTag != null, colorMatrixTag?.let { "present_tag_$it" } ?: "missing_tags_50721,50722")
        val illuminantTag = listOf(50778, 50779).firstOrNull { it in snapshot.presentTagIds }
        items += DngAuditItem("CalibrationIlluminant", illuminantTag != null, illuminantTag?.let { "present_tag_$it" } ?: "missing_tags_50778,50779")
        items += DngAuditItem(
            "Orientation",
            snapshot.orientation == expectedOrientation.toLong(),
            "expected=$expectedOrientation actual=${snapshot.orientation ?: "missing"}"
        )
        if (expectedWhiteLevel != null) {
            val passed = snapshot.whiteLevels.isNotEmpty() && snapshot.whiteLevels.any { it == expectedWhiteLevel.toLong() }
            items += DngAuditItem("WhiteLevel value", passed, "expected=$expectedWhiteLevel actual=${snapshot.whiteLevels.ifEmpty { listOf(-1L) }}")
        }
        if (expectedBlackLevels != null) {
            val comparable = snapshot.blackLevels.take(expectedBlackLevels.size)
            val passed = comparable.size == expectedBlackLevels.size &&
                    comparable.zip(expectedBlackLevels).all { (actual, expected) -> actual == expected.toLong() }
            items += DngAuditItem("BlackLevel value", passed, "expected=$expectedBlackLevels actual=${comparable.ifEmpty { listOf(-1L) }}")
        }
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
            (imageWidth == null || imageWidth == payloadWidth) &&
                    (imageHeight == null || imageHeight == payloadHeight),
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

        // When the ByteBuffer is the full Camera2 pixel array, the sensor active-array coordinates
        // are directly comparable to DNG raw-image coordinates. This is the portability case that
        // can explain a black inactive strip. For custom HAL stream sizes we log rather than invent
        // a coordinate transform.
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
}
