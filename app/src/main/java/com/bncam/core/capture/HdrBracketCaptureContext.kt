package com.bncam.core.capture

import com.bncam.core.buffer.FrameLease

/**
 * Exact post-shutter bracket ownership transferred from Camera2 acquisition to MultiFrameRunner.
 * Leases remain valid until the runner finishes native processing; no warm-buffer re-selection is
 * allowed after this context has been created.
 */
data class HdrCapturedFrame(
    val role: HdrBracketRole,
    val targetEvFromAnchor: Float,
    val lease: FrameLease,
    val actualExposureTimeNs: Long,
    val actualSensitivityIso: Int,
    val actualExposureScaleToAnchor: Float
)

class HdrBracketCaptureContext(
    frames: List<HdrCapturedFrame>,
    val controlMode: HdrExposureControlMode,
    val motionLimited: Boolean
) : AutoCloseable {
    val acquisitionFrames: List<HdrCapturedFrame> = frames.toList()
    val anchor: HdrCapturedFrame = acquisitionFrames.single { it.role == HdrBracketRole.ANCHOR }

    /** Native multi-frame contracts keep the anchor last. */
    val processingFrames: List<HdrCapturedFrame> =
        acquisitionFrames.filterNot { it.role == HdrBracketRole.ANCHOR } + anchor

    val exposureScalesToAnchor: FloatArray
        get() = processingFrames.map { it.actualExposureScaleToAnchor }.toFloatArray()

    init {
        require(acquisitionFrames.size == 3) { "Computational HDR requires exactly three exact bracket frames." }
        require(acquisitionFrames.map { it.role }.toSet() == HdrBracketRole.entries.toSet())
        require(acquisitionFrames.all { it.actualExposureTimeNs > 0L && it.actualSensitivityIso > 0 })
        require(acquisitionFrames.all { it.actualExposureScaleToAnchor.isFinite() && it.actualExposureScaleToAnchor > 0f })
        require(kotlin.math.abs(anchor.actualExposureScaleToAnchor - 1f) < 0.05f)
    }

    override fun close() {
        acquisitionFrames.forEach { it.lease.release() }
    }
}
