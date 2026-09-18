package com.bncam.ui.screens.capture

import com.bncam.core.isp.raw.RawDomainContract
import com.bncam.core.isp.raw.RawInputSource
import com.bncam.core.isp.raw.RawLevelOrder
import com.bncam.core.quality.CfaArrangementDescriptor
import com.bncam.core.quality.ColorPlane
import kotlin.math.roundToInt


internal data class RawPreviewDevelopedLevels(
    val whiteLevel: Int,
    val blackLevels: FloatArray,
    val source: String
)

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

    /**
     * Projects the developed RAW black/white authority back into the live source-buffer domain.
     * RAW_SENSOR is already in the payload code domain. RAW10 preview reads native 10-bit codes,
     * while the capture master can be black-anchored into a wider physical payload domain. This
     * inverse projection keeps preview normalization equivalent to the developed JPEG contract.
     */
    fun developedLevelsInSourceDomain(contract: RawDomainContract): RawPreviewDevelopedLevels {
        val developedMosaic = RawLevelOrder.canonicalToMosaic(
            contract.developedRawBlackLevels,
            contract.cfaPattern
        )

        if (contract.sourceFormat != RawInputSource.RAW10 ||
            contract.nativeWhiteLevel == contract.payloadWhiteLevel
        ) {
            return RawPreviewDevelopedLevels(
                whiteLevel = contract.developedRawWhiteLevel.coerceIn(1, 65535),
                blackLevels = FloatArray(4) { index ->
                    (developedMosaic.getOrNull(index) ?: 0f)
                        .coerceIn(0f, contract.developedRawWhiteLevel.coerceAtLeast(2) - 1f)
                },
                source = contract.developedRawWhiteLevelSource
            )
        }

        val whiteCandidates = FloatArray(4)
        val nativeDevelopedBlack = FloatArray(4)
        for (index in 0 until 4) {
            val nativeBlack = contract.nativeBlackLevels.getOrNull(index)?.toFloat() ?: 0f
            val payloadBlack = contract.payloadBlackLevels.getOrNull(index)?.toFloat() ?: 0f
            val developedBlack = developedMosaic.getOrNull(index) ?: payloadBlack
            val nativeRange = (contract.nativeWhiteLevel.toFloat() - nativeBlack).coerceAtLeast(1f)
            val payloadRange = (contract.payloadWhiteLevel.toFloat() - payloadBlack).coerceAtLeast(1f)
            val inverseScale = nativeRange / payloadRange
            nativeDevelopedBlack[index] = nativeBlack + (developedBlack - payloadBlack) * inverseScale
            whiteCandidates[index] = nativeBlack +
                (contract.developedRawWhiteLevel.toFloat() - payloadBlack) * inverseScale
        }

        val sourceWhite = whiteCandidates.average().toFloat()
            .takeIf { it.isFinite() }
            ?.roundToInt()
            ?.coerceIn(1, 65535)
            ?: contract.nativeWhiteLevel.coerceIn(1, 65535)
        val safeBlackCeiling = sourceWhite.coerceAtLeast(2) - 1f
        return RawPreviewDevelopedLevels(
            whiteLevel = sourceWhite,
            blackLevels = FloatArray(4) { index ->
                nativeDevelopedBlack[index]
                    .takeIf { it.isFinite() }
                    ?.coerceIn(0f, safeBlackCeiling)
                    ?: 0f
            },
            source = contract.developedRawWhiteLevelSource + " -> RAW10 native preview domain"
        )
    }
}
