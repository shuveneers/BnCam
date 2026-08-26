package com.bncam.data.profile

import com.bncam.core.engine.CaptureStrategy
import java.util.UUID

data class CameraProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val targetLensId: String,

    // --- Core Pipeline Settings ---
    val captureStrategy: CaptureStrategy = CaptureStrategy.SINGLE_FRAME_ZSL,
    val bufferSize: Int = 10,

    // ========================================================
    // NIEUW: PRE-MERGE PIPELINE SETTINGS
    // ========================================================

    // 1. Base Frame Engine
    val baseFrameStrategy: String = "Shutter Sync", // Opties: Shutter Sync, Max Sharpness, Max Exposure, Balanced
    val baseFrameBias: Float = 0f,                  // Slider: -5f (Pre-shutter bias) tot +5f (Post-shutter bias)

    // 2. Frame Selection & Pruning
    val requestedFrames: Int = 1,                   // 1 voor Single Frame, 3-15 voor HDR/Burst
    val rejectionStrictness: String = "Keep All",   // Opties: Keep All, Lenient, Strict
    val ignoreStaleFrames: Boolean = false,         // Buffer Pruning toggle

    // ========================================================

    // --- ISP & Hardware Settings ---
    val noiseReductionMode: Int = 0,
    val edgeEnhancementMode: Int = 0,
    val targetFps: Int = 30,

    // --- UI Toggles ---
    val enableManualExposure: Boolean = false,
    val enablePeaking: Boolean = false,

    // --- Slot Systeem ---
    var isVisibleInUi: Boolean = true,
    val isLocked: Boolean = false
)