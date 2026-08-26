package com.bncam.core.capture

/**
 * Canonical contract for what pixel data the published DNG is planned to contain and what the
 * native RAW master actually produced.
 */
object DngSourceResolver {
    fun planned(
        outputPolicy: OutputPolicy,
        captureMode: CaptureMode,
        effectiveDngMasterFrameCount: Int
    ): DngSource = when {
        !outputPolicy.producesRaw -> DngSource.NOT_APPLICABLE
        captureMode == CaptureMode.SINGLE -> DngSource.ANCHOR_RAW
        effectiveDngMasterFrameCount <= 1 -> DngSource.ANCHOR_RAW
        else -> DngSource.FUSED_RAW
    }

    fun executed(
        rawMasterAvailable: Boolean,
        masterFrameCount: Int,
        nativeAnchorOnly: Boolean
    ): DngSource = when {
        !rawMasterAvailable -> DngSource.NOT_APPLICABLE
        masterFrameCount <= 1 || nativeAnchorOnly -> DngSource.ANCHOR_RAW
        else -> DngSource.FUSED_RAW
    }
}
