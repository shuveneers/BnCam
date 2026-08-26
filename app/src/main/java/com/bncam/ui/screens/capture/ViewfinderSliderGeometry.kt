package com.bncam.ui.screens.capture

/** Pure geometry for the viewfinder side sliders. Kept independent from Compose for regression tests. */
internal object ViewfinderSliderGeometry {
    fun trackOffsetForValue(
        value: Float,
        rangeStart: Float,
        rangeEnd: Float,
        trackHeightPx: Float
    ): Float {
        if (trackHeightPx <= 0f || rangeEnd <= rangeStart) return 0f
        val fraction = ((value - rangeStart) / (rangeEnd - rangeStart)).coerceIn(0f, 1f)
        return (1f - fraction) * trackHeightPx
    }

    fun pointerToTrackOffset(
        pointerY: Float,
        trackTopPx: Float,
        trackHeightPx: Float,
        resetExtensionPx: Float
    ): Float = (pointerY - trackTopPx).coerceIn(0f, trackHeightPx + resetExtensionPx)

    fun valueForTrackOffset(
        trackOffsetPx: Float,
        rangeStart: Float,
        rangeEnd: Float,
        trackHeightPx: Float
    ): Float? {
        if (trackHeightPx <= 0f || rangeEnd <= rangeStart || trackOffsetPx > trackHeightPx) return null
        val visualFraction = 1f - (trackOffsetPx.coerceIn(0f, trackHeightPx) / trackHeightPx)
        return rangeStart + visualFraction * (rangeEnd - rangeStart)
    }
}
