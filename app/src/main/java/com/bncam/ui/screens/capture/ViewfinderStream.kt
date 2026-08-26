package com.bncam.ui.screens.capture

import android.graphics.ImageFormat
import com.bncam.core.quality.DefaultIspProfile

enum class ViewfinderStream(val persistedValue: String, val displayName: String) {
    YUV("YUV", "YUV"),
    SELECTED_BUFFER("SELECTED_BUFFER", "Selected buffer");

    companion object {
        fun parse(value: String?): ViewfinderStream = when (
            value?.trim()?.replace(' ', '_')?.uppercase()
        ) {
            SELECTED_BUFFER.persistedValue -> SELECTED_BUFFER
            else -> YUV
        }
    }
}

enum class ViewfinderEffectiveSource {
    YUV,
    RAW10,
    RAW_SENSOR;

    val imageFormat: Int
        get() = when (this) {
            YUV -> ImageFormat.YUV_420_888
            RAW10 -> ImageFormat.RAW10
            RAW_SENSOR -> ImageFormat.RAW_SENSOR
        }

    companion object {
        fun fromFrameSource(value: String?): ViewfinderEffectiveSource = when (
            value?.trim()?.uppercase()
        ) {
            "RAW10" -> RAW10
            "RAW_SENSOR", "RAW" -> RAW_SENSOR
            else -> YUV
        }

        fun fromImageFormat(format: Int): ViewfinderEffectiveSource = when (format) {
            ImageFormat.RAW10 -> RAW10
            ImageFormat.RAW_SENSOR -> RAW_SENSOR
            else -> YUV
        }
    }
}

fun resolveEffectiveViewfinderSource(
    setting: ViewfinderStream,
    activeProfileSource: ViewfinderEffectiveSource,
    profileId: String = ""
): ViewfinderEffectiveSource {
    if (DefaultIspProfile.isDisabledProfileId(profileId)) {
        return ViewfinderEffectiveSource.YUV
    }
    return if (setting == ViewfinderStream.SELECTED_BUFFER) {
        activeProfileSource
    } else {
        ViewfinderEffectiveSource.YUV
    }
}
