package com.bncam.ui.screens.capture

/**
 * Texture coordinates for a top-left-origin RGBA buffer uploaded to OpenGL.
 * Rotation is applied at display time, so the RAW mosaic/RGB buffers are never copied just to
 * change orientation. Coordinates use the GLSurfaceView triangle-strip vertex order:
 * bottom-left, bottom-right, top-left, top-right.
 */
internal object RawPreviewTextureTransform {
    fun coordinates(
        clockwiseRotationDegrees: Int,
        mirrored: Boolean,
        digitalZoom: Float = 1f
    ): FloatArray {
        val normalized = ((clockwiseRotationDegrees % 360) + 360) % 360
        val base = when (normalized) {
            90 -> floatArrayOf(1f, 1f, 1f, 0f, 0f, 1f, 0f, 0f)
            180 -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 1f, 0f, 1f)
            270 -> floatArrayOf(0f, 0f, 0f, 1f, 1f, 0f, 1f, 1f)
            else -> floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
        }
        val oriented = if (!mirrored) {
            base
        } else {
            floatArrayOf(
                base[2], base[3], base[0], base[1],
                base[6], base[7], base[4], base[5]
            )
        }
        return applyCenteredZoom(oriented, digitalZoom)
    }

    private fun applyCenteredZoom(coordinates: FloatArray, digitalZoom: Float): FloatArray {
        val safeZoom = digitalZoom.coerceAtLeast(1f)
        if (safeZoom == 1f) return coordinates
        return FloatArray(coordinates.size) { index ->
            0.5f + (coordinates[index] - 0.5f) / safeZoom
        }
    }
}
