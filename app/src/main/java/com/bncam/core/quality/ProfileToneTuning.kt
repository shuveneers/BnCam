package com.bncam.core.quality

import java.util.Locale

/**
 * Profile-owned creative tone controls.
 *
 * These values are deliberately separate from Camera2 acquisition exposure. A profile Exposure
 * value changes the developed scene-linear image; it must never change shutter time or ISO.
 * All controls are normalized to -1..+1 with 0.00 as the BnCam automatic/neutral baseline.
 */
data class ProfileToneTuning(
    val exposure: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
    val contrast: Float = 0f,
    val localToneBias: Float = 0f
) {
    fun sanitized(): ProfileToneTuning = copy(
        exposure = exposure.finiteSigned(),
        highlights = highlights.finiteSigned(),
        shadows = shadows.finiteSigned(),
        whites = whites.finiteSigned(),
        blacks = blacks.finiteSigned(),
        contrast = contrast.finiteSigned(),
        localToneBias = localToneBias.finiteSigned()
    )

    /** Normalized profile Exposure maps to a bounded post-capture scene-linear ±2 EV range. */
    val exposureEv: Float get() = sanitized().exposure * 2f

    fun debugPairs(): List<Pair<String, String>> = listOf(
        "Profile Tone Exposure" to String.format(Locale.US, "%+.2f (%+.2f EV render)", exposure, exposureEv),
        "Profile Tone Highlights" to String.format(Locale.US, "%+.2f", highlights),
        "Profile Tone Shadows" to String.format(Locale.US, "%+.2f", shadows),
        "Profile Tone Whites" to String.format(Locale.US, "%+.2f", whites),
        "Profile Tone Blacks" to String.format(Locale.US, "%+.2f", blacks),
        "Profile Tone Contrast" to String.format(Locale.US, "%+.2f", contrast),
        "Profile Local Tone Bias" to String.format(Locale.US, "%+.2f", localToneBias),
        "Profile Tone Authority" to "ISP_RENDER_ONLY; never Camera2 shutter/ISO"
    )

    private fun Float.finiteSigned(): Float = if (isFinite()) coerceIn(-1f, 1f) else 0f
}
