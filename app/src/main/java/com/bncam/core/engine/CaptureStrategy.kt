package com.bncam.core.engine

enum class CaptureStrategy(val label: String, val description: String) {
    SINGLE_FRAME_ZSL("Single frame (Near ZSL)", "Fast capture with minimal processing."),
    MULTI_FRAME_ZSL("Multi frame (Near ZSL)", "Burst capture for noise reduction."),
    HDR_ENHANCED("HDR Enhanced", "Deliberate scene-aware RAW burst with temporal stacking.")
}
