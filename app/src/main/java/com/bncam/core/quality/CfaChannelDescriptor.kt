package com.bncam.core.quality

import android.hardware.camera2.CameraCharacteristics

enum class ColorPlane {
    RED,
    GREEN_RED,
    GREEN_BLUE,
    BLUE,
    MONO
}

data class CfaChannelDescriptor(
    val mosaicIndex: Int,       // 0..3 for Bayer, 0 for MONO
    val rowParity: Int,         // 0 (even row), 1 (odd row)
    val colParity: Int,         // 0 (even col), 1 (odd col)
    val colorPlane: ColorPlane
)

sealed class CfaState {
    object Loading : CfaState()
    data class Available(val descriptor: CfaArrangementDescriptor) : CfaState()
    data class Unsupported(val reason: String) : CfaState()
    data class Unavailable(val reason: String = "metadata missing") : CfaState()
}

sealed class CfaArrangementDescriptor {
    abstract val cfaPatternEnum: Int
    abstract val cfaName: String
    abstract val isSupportedBayer: Boolean
    abstract val isSupportedMono: Boolean

    data class Bayer(
        override val cfaPatternEnum: Int,
        override val cfaName: String,
        val channels: List<CfaChannelDescriptor> // Exactly 4 channel descriptors
    ) : CfaArrangementDescriptor() {
        override val isSupportedBayer: Boolean = true
        override val isSupportedMono: Boolean = false

        init {
            require(channels.size == 4) { "Bayer arrangement must contain exactly 4 channel descriptors, got ${channels.size}" }
        }
    }

    data class Monochrome(
        override val cfaPatternEnum: Int = CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO,
        override val cfaName: String = "MONO_NON_BAYER",
        val channel: CfaChannelDescriptor = CfaChannelDescriptor(
            mosaicIndex = 0,
            rowParity = 0,
            colParity = 0,
            colorPlane = ColorPlane.MONO
        )
    ) : CfaArrangementDescriptor() {
        override val isSupportedBayer: Boolean = false
        override val isSupportedMono: Boolean = true
    }

    data class Unsupported(
        override val cfaPatternEnum: Int,
        override val cfaName: String,
        val rejectionReason: String
    ) : CfaArrangementDescriptor() {
        override val isSupportedBayer: Boolean = false
        override val isSupportedMono: Boolean = false
    }

    companion object {
        fun from(cfaPatternEnum: Int): CfaArrangementDescriptor = when (cfaPatternEnum) {
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> Bayer(
                cfaPatternEnum = cfaPatternEnum,
                cfaName = "RGGB",
                channels = listOf(
                    CfaChannelDescriptor(0, 0, 0, ColorPlane.RED),
                    CfaChannelDescriptor(1, 0, 1, ColorPlane.GREEN_RED),
                    CfaChannelDescriptor(2, 1, 0, ColorPlane.GREEN_BLUE),
                    CfaChannelDescriptor(3, 1, 1, ColorPlane.BLUE)
                )
            )
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> Bayer(
                cfaPatternEnum = cfaPatternEnum,
                cfaName = "GRBG",
                channels = listOf(
                    CfaChannelDescriptor(0, 0, 0, ColorPlane.GREEN_RED),
                    CfaChannelDescriptor(1, 0, 1, ColorPlane.RED),
                    CfaChannelDescriptor(2, 1, 0, ColorPlane.BLUE),
                    CfaChannelDescriptor(3, 1, 1, ColorPlane.GREEN_BLUE)
                )
            )
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> Bayer(
                cfaPatternEnum = cfaPatternEnum,
                cfaName = "GBRG",
                channels = listOf(
                    CfaChannelDescriptor(0, 0, 0, ColorPlane.GREEN_BLUE),
                    CfaChannelDescriptor(1, 0, 1, ColorPlane.BLUE),
                    CfaChannelDescriptor(2, 1, 0, ColorPlane.RED),
                    CfaChannelDescriptor(3, 1, 1, ColorPlane.GREEN_RED)
                )
            )
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> Bayer(
                cfaPatternEnum = cfaPatternEnum,
                cfaName = "BGGR",
                channels = listOf(
                    CfaChannelDescriptor(0, 0, 0, ColorPlane.BLUE),
                    CfaChannelDescriptor(1, 0, 1, ColorPlane.GREEN_BLUE),
                    CfaChannelDescriptor(2, 1, 0, ColorPlane.GREEN_RED),
                    CfaChannelDescriptor(3, 1, 1, ColorPlane.RED)
                )
            )
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO -> Monochrome()
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGB -> Unsupported(
                cfaPatternEnum = cfaPatternEnum,
                cfaName = "RGB_NON_BAYER",
                rejectionReason = "Unsupported RAW arrangement: RGB non-Bayer layout is not supported by Bayer processing pipeline"
            )
            else -> Unsupported(
                cfaPatternEnum = cfaPatternEnum,
                cfaName = "UNKNOWN_OR_UNSUPPORTED_$cfaPatternEnum",
                rejectionReason = "Unsupported RAW arrangement: Unrecognized sensor color filter arrangement $cfaPatternEnum"
            )
        }
    }
}
