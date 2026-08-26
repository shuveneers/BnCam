package com.bncam.ui.screens.capture

/**
 * Builds an aspect-preserving OpenGL vertex quad that always shows the complete RAW frame.
 * Any aspect-ratio mismatch is represented as black letter/pillar boxing; the source texture is
 * never center-cropped or stretched just to fill the phone viewport.
 */
internal object RawPreviewAspectFitTransform {
    private val fullQuad = floatArrayOf(
        -1f, -1f,  1f, -1f,
        -1f,  1f,  1f,  1f
    )

    fun vertices(
        clockwiseRotationDegrees: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        viewportWidth: Int,
        viewportHeight: Int
    ): FloatArray {
        if (sourceWidth <= 0 || sourceHeight <= 0 || viewportWidth <= 0 || viewportHeight <= 0) {
            return fullQuad.copyOf()
        }
        val normalized = ((clockwiseRotationDegrees % 360) + 360) % 360
        val swapsAxes = normalized == 90 || normalized == 270
        val displayWidth = if (swapsAxes) sourceHeight.toFloat() else sourceWidth.toFloat()
        val displayHeight = if (swapsAxes) sourceWidth.toFloat() else sourceHeight.toFloat()
        val sourceAspect = displayWidth / displayHeight.coerceAtLeast(1f)
        val viewportAspect = viewportWidth.toFloat() / viewportHeight.toFloat().coerceAtLeast(1f)
        if (!sourceAspect.isFinite() || !viewportAspect.isFinite() || sourceAspect <= 0f || viewportAspect <= 0f) {
            return fullQuad.copyOf()
        }

        val xScale: Float
        val yScale: Float
        if (sourceAspect > viewportAspect) {
            xScale = 1f
            yScale = (viewportAspect / sourceAspect).coerceIn(0.01f, 1f)
        } else {
            xScale = (sourceAspect / viewportAspect).coerceIn(0.01f, 1f)
            yScale = 1f
        }
        return floatArrayOf(
            -xScale, -yScale,  xScale, -yScale,
            -xScale,  yScale,  xScale,  yScale
        )
    }
}
