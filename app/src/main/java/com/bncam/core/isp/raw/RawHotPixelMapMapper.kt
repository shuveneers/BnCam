package com.bncam.core.isp.raw

import android.graphics.Point
import android.hardware.camera2.CameraCharacteristics
import com.bncam.core.runtime.SensorToRawBufferTransform

data class RawSensorDefectPoint(val x: Int, val y: Int)

data class RawHotPixelMapGeometry(
    val pixelArrayWidth: Int,
    val pixelArrayHeight: Int,
    val preCorrectionLeft: Int,
    val preCorrectionTop: Int,
    val preCorrectionWidth: Int,
    val preCorrectionHeight: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val sourceCropLeft: Int,
    val sourceCropTop: Int,
    val outputWidth: Int,
    val outputHeight: Int
)

data class RawMappedHotPixelMap(
    /** Packed master-RAW16 coordinates [x0,y0,x1,y1,...]. */
    val packedXy: IntArray = IntArray(0),
    val sensorPointCount: Int = 0,
    val mappedPointCount: Int = 0,
    val rejectedOutsideSourceCount: Int = 0,
    val rejectedOutsideOutputCount: Int = 0,
    val mappingStatus: String = "NOT_REQUESTED_OR_UNAVAILABLE"
) {
    val isUsable: Boolean
        get() = mappedPointCount > 0 && packedXy.size == mappedPointCount * 2

    fun debugSummary(): String =
        "status=$mappingStatus;sensorPoints=$sensorPointCount;mappedPoints=$mappedPointCount;" +
            "rejectedOutsideSource=$rejectedOutsideSourceCount;" +
            "rejectedOutsideOutput=$rejectedOutsideOutputCount"

    companion object {
        val EMPTY = RawMappedHotPixelMap()
    }
}

/**
 * Maps Camera2 STATISTICS_HOT_PIXEL_MAP sensor coordinates into the exact cropped RAW16 master.
 *
 * Camera2 reports hot pixels in full sensor pixel-array coordinates. BnCam may receive either
 * the full pixel array or a RAW stream already cropped to the pre-correction active array. An
 * arbitrary custom-sized RAW buffer does not prove either coordinate relationship and is therefore
 * rejected rather than guessed.
 */
object RawHotPixelMapMapper {

    fun fromCamera2(
        points: Array<Point>?,
        characteristics: CameraCharacteristics,
        sourceWidth: Int,
        sourceHeight: Int,
        sourceCropLeft: Int,
        sourceCropTop: Int,
        outputWidth: Int,
        outputHeight: Int
    ): RawMappedHotPixelMap {
        if (points.isNullOrEmpty()) return RawMappedHotPixelMap.EMPTY
        val transform = SensorToRawBufferTransform.create(
            characteristics = characteristics,
            bufferWidth = sourceWidth,
            bufferHeight = sourceHeight
        )
        val geometry = RawHotPixelMapGeometry(
            pixelArrayWidth = transform.pixelArrayRect.width(),
            pixelArrayHeight = transform.pixelArrayRect.height(),
            preCorrectionLeft = transform.preCorrectionActiveRect.left,
            preCorrectionTop = transform.preCorrectionActiveRect.top,
            preCorrectionWidth = transform.preCorrectionActiveRect.width(),
            preCorrectionHeight = transform.preCorrectionActiveRect.height(),
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            sourceCropLeft = sourceCropLeft,
            sourceCropTop = sourceCropTop,
            outputWidth = outputWidth,
            outputHeight = outputHeight
        )
        return mapCoordinates(
            points = points.map { RawSensorDefectPoint(it.x, it.y) },
            geometry = geometry
        )
    }

    internal fun mapCoordinates(
        points: List<RawSensorDefectPoint>,
        geometry: RawHotPixelMapGeometry
    ): RawMappedHotPixelMap {
        if (points.isEmpty()) return RawMappedHotPixelMap.EMPTY
        if (geometry.sourceWidth <= 0 || geometry.sourceHeight <= 0 ||
            geometry.outputWidth <= 0 || geometry.outputHeight <= 0
        ) {
            return RawMappedHotPixelMap(
                sensorPointCount = points.size,
                mappingStatus = "INVALID_RAW_GEOMETRY"
            )
        }

        val fullPixelArraySource =
            geometry.sourceWidth == geometry.pixelArrayWidth &&
                geometry.sourceHeight == geometry.pixelArrayHeight
        val preCorrectionSource =
            geometry.sourceWidth == geometry.preCorrectionWidth &&
                geometry.sourceHeight == geometry.preCorrectionHeight

        if (!fullPixelArraySource && !preCorrectionSource) {
            return RawMappedHotPixelMap(
                sensorPointCount = points.size,
                mappingStatus = "UNRESOLVED_CUSTOM_RAW_BUFFER_GEOMETRY"
            )
        }

        var rejectedSource = 0
        var rejectedOutput = 0
        val linearIndices = java.util.TreeSet<Int>()
        points.forEach { point ->
            val sourceX = if (fullPixelArraySource) point.x
                else point.x - geometry.preCorrectionLeft
            val sourceY = if (fullPixelArraySource) point.y
                else point.y - geometry.preCorrectionTop

            if (sourceX !in 0 until geometry.sourceWidth ||
                sourceY !in 0 until geometry.sourceHeight
            ) {
                rejectedSource++
                return@forEach
            }

            val outputX = sourceX - geometry.sourceCropLeft
            val outputY = sourceY - geometry.sourceCropTop
            if (outputX !in 0 until geometry.outputWidth ||
                outputY !in 0 until geometry.outputHeight
            ) {
                rejectedOutput++
                return@forEach
            }
            linearIndices.add(outputY * geometry.outputWidth + outputX)
        }

        val packed = IntArray(linearIndices.size * 2)
        linearIndices.forEachIndexed { index, linear ->
            packed[index * 2] = linear % geometry.outputWidth
            packed[index * 2 + 1] = linear / geometry.outputWidth
        }
        return RawMappedHotPixelMap(
            packedXy = packed,
            sensorPointCount = points.size,
            mappedPointCount = linearIndices.size,
            rejectedOutsideSourceCount = rejectedSource,
            rejectedOutsideOutputCount = rejectedOutput,
            mappingStatus = if (linearIndices.isEmpty()) {
                "MAP_PRESENT_NO_POINTS_IN_RENDERED_RAW"
            } else if (fullPixelArraySource) {
                "MAPPED_FULL_PIXEL_ARRAY_TO_CROPPED_RAW16"
            } else {
                "MAPPED_PRECORRECTION_ARRAY_TO_CROPPED_RAW16"
            }
        )
    }
}
