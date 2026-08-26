package com.bncam.core.engine

/**
 * Single authoritative owner for preview autofocus writes.
 *
 * Only one owner may steer the preview lens/AF region at a time. Capture-time AF copies may still
 * snapshot the already-authoritative preview state, but must not become a second live owner.
 */
enum class FocusOwner {
    AUTO,
    TAP,
    TRACK_ACQUIRING,
    TRACK_TIMED,
    TRACK_PINNED,
    FACE_PRIORITY,
    MANUAL,
    AE_AF_LOCK
}

data class FocusOwnershipState(
    val owner: FocusOwner = FocusOwner.AUTO,
    val focusLocked: Boolean = false,
    val aeLocked: Boolean = false,
    val trackingPinned: Boolean = false,
    val reason: String = "initial",
    val generation: Long = 0L
) {
    val trackingActive: Boolean
        get() = owner == FocusOwner.TRACK_ACQUIRING ||
            owner == FocusOwner.TRACK_TIMED ||
            owner == FocusOwner.TRACK_PINNED

    val explicitUserOwner: Boolean
        get() = owner == FocusOwner.TAP || trackingActive ||
            owner == FocusOwner.MANUAL || owner == FocusOwner.AE_AF_LOCK

    val facePriorityMayOwn: Boolean
        get() = owner == FocusOwner.AUTO || owner == FocusOwner.FACE_PRIORITY
}


enum class FocusTrackingPhase {
    IDLE,
    ACQUIRING,
    TRACKING,
    REACQUIRING,
    LOST
}

data class FocusTrackingState(
    val phase: FocusTrackingPhase = FocusTrackingPhase.IDLE,
    val confidence: Float = 0f,
    val lostFrames: Int = 0,
    val pinned: Boolean = false,
    val reason: String = "idle"
) {
    val hasTarget: Boolean
        get() = phase == FocusTrackingPhase.TRACKING ||
            phase == FocusTrackingPhase.REACQUIRING || phase == FocusTrackingPhase.LOST
}

/** Immutable focus truth captured at the shutter boundary for frame-selection policy. */
data class FocusCaptureContext(
    val owner: FocusOwner = FocusOwner.AUTO,
    val trackingPhase: FocusTrackingPhase = FocusTrackingPhase.IDLE,
    val trackingConfidence: Float = 0f,
    val trackingPinned: Boolean = false
) {
    val reliableTrackedSubject: Boolean
        get() = (owner == FocusOwner.TRACK_TIMED || owner == FocusOwner.TRACK_PINNED) &&
            trackingPhase == FocusTrackingPhase.TRACKING && trackingConfidence >= 0.50f
}
