package com.bncam.ui.screens.capture

import com.bncam.core.quality.CfaArrangementDescriptor
import com.bncam.core.quality.ColorPlane

internal object RawPreviewCalibrationTransform {
    /** Converts canonical R/Gr/Gb/B calibration values back to 2×2 sensor tile order. */
    fun canonicalBlackLevelsToMosaic(canonical: FloatArray, cfaPattern: Int): FloatArray {
        if (canonical.size < 4) return canonical.copyOf(4)
        val descriptor = CfaArrangementDescriptor.from(cfaPattern)
        if (descriptor !is CfaArrangementDescriptor.Bayer) return canonical.copyOf(4)
        val mosaic = FloatArray(4)
        descriptor.channels.forEach { channel ->
            val canonicalIndex = when (channel.colorPlane) {
                ColorPlane.RED -> 0
                ColorPlane.GREEN_RED -> 1
                ColorPlane.GREEN_BLUE -> 2
                ColorPlane.BLUE -> 3
                ColorPlane.MONO -> 0
            }
            mosaic[channel.mosaicIndex] = canonical[canonicalIndex]
        }
        return mosaic
    }
}
