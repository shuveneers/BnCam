package com.bncam.core.quality

/** Color ownership for default single RAW capture and preview. An exact Camera2 solution must not
 * be replaced solely because static characterization predicts less opponent noise. */
object DefaultRawCamera2PairPolicy {
    fun resolve(
        defaultSingleRaw: Boolean,
        explicitWb: Boolean,
        systemColor: Boolean,
        appliedGains: FloatArray,
        frameGains: FloatArray?,
        frameMatrix: FloatArray?
    ): FloatArray? {
        if (!defaultSingleRaw || explicitWb || !systemColor || frameGains == null ||
            frameMatrix == null || frameGains.size != 4 ||
            frameGains.any { !it.isFinite() || it <= 0f } ||
            !appliedGains.contentEquals(frameGains)) return null
        // Keep malformed/domain-mismatch guards, but no comparative noise/desaturation gate.
        if (!RawColorTransformEngine.validateExactFrameCamera2ColorTransform(frameMatrix).valid) return null
        return frameMatrix.copyOf()
    }
}
