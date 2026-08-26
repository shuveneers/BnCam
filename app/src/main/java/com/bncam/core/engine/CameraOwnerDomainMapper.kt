package com.bncam.core.engine

import android.graphics.Rect
import android.hardware.camera2.params.MeteringRectangle
import kotlin.math.ceil
import kotlin.math.floor

/** Maps a selected physical sensor rectangle into the opened logical owner's current crop. */
object CameraOwnerDomainMapper {
    data class Bounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    fun mapCoordinates(
        physicalRegion: Bounds,
        physicalBounds: Bounds,
        logicalOwnerBounds: Bounds
    ): Bounds? {
        if (physicalBounds.width <= 0 || physicalBounds.height <= 0 ||
            logicalOwnerBounds.width <= 0 || logicalOwnerBounds.height <= 0
        ) return null
        val source = Bounds(
            left = physicalRegion.left.coerceAtLeast(physicalBounds.left),
            top = physicalRegion.top.coerceAtLeast(physicalBounds.top),
            right = physicalRegion.right.coerceAtMost(physicalBounds.right),
            bottom = physicalRegion.bottom.coerceAtMost(physicalBounds.bottom)
        )
        if (source.right <= source.left || source.bottom <= source.top) return null

        fun mapX(value: Int, roundUp: Boolean): Int {
            val normalized = (value - physicalBounds.left).toDouble() / physicalBounds.width.toDouble()
            val mapped = logicalOwnerBounds.left + normalized * logicalOwnerBounds.width
            return if (roundUp) ceil(mapped).toInt() else floor(mapped).toInt()
        }

        fun mapY(value: Int, roundUp: Boolean): Int {
            val normalized = (value - physicalBounds.top).toDouble() / physicalBounds.height.toDouble()
            val mapped = logicalOwnerBounds.top + normalized * logicalOwnerBounds.height
            return if (roundUp) ceil(mapped).toInt() else floor(mapped).toInt()
        }

        val left = mapX(source.left, roundUp = false)
            .coerceIn(logicalOwnerBounds.left, logicalOwnerBounds.right - 1)
        val top = mapY(source.top, roundUp = false)
            .coerceIn(logicalOwnerBounds.top, logicalOwnerBounds.bottom - 1)
        val right = mapX(source.right, roundUp = true)
            .coerceIn(left + 1, logicalOwnerBounds.right)
        val bottom = mapY(source.bottom, roundUp = true)
            .coerceIn(top + 1, logicalOwnerBounds.bottom)
        return Bounds(left, top, right, bottom)
    }

    fun mapMeteringRectangle(
        physicalRegion: MeteringRectangle,
        physicalBounds: Rect,
        logicalOwnerBounds: Rect
    ): MeteringRectangle? {
        fun Rect.toBounds() = Bounds(left, top, right, bottom)
        val mapped = mapCoordinates(
            physicalRegion = physicalRegion.rect.toBounds(),
            physicalBounds = physicalBounds.toBounds(),
            logicalOwnerBounds = logicalOwnerBounds.toBounds()
        ) ?: return null
        return MeteringRectangle(
            Rect(mapped.left, mapped.top, mapped.right, mapped.bottom),
            physicalRegion.meteringWeight
        )
    }
}
