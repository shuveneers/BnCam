package com.bncam.ui.screens.capture

internal enum class RawPreviewDropReason {
    INPUT_QUEUE_OVERFLOW,
    NO_OUTPUT_SLOT,
    STALE_GENERATION,
    RETAIN_FAILED,
    NATIVE_RENDER_FAILED,
    GPU_OUTPUT_IMPORT_FAILED,
    GL_INTEROP_FAILED,
    GL_FENCE_CREATE_FAILED,
    GL_FENCE_POLL_FAILED
}
