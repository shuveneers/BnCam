package com.bncam.core.engine

/**
 * Pure shutter-time flash routing policy.
 *
 * The policy deliberately separates "is a dedicated still transaction required?" from the
 * Camera2 implementation of preflash/main-flash. This keeps capture-mode routing deterministic
 * while the HAL remains authoritative for the final Auto-flash decision.
 */
internal enum class FlashAeState {
    UNKNOWN,
    INACTIVE,
    SEARCHING,
    CONVERGED,
    LOCKED,
    FLASH_REQUIRED,
    PRECAPTURE
}

internal enum class FlashCaptureRoute {
    ZSL,
    DEDICATED_STILL
}

internal data class FlashShutterDecision(
    val route: FlashCaptureRoute,
    val reason: String,
    val autoDecisionUncertain: Boolean = false
) {
    val requiresDedicatedStill: Boolean
        get() = route == FlashCaptureRoute.DEDICATED_STILL
}

internal object FlashCapturePolicy {
    fun decide(
        mode: String,
        manualExposureActive: Boolean,
        flashHardwareAvailable: Boolean,
        autoFlashAeModeSupported: Boolean,
        currentPipelineGeneration: Int,
        observationGeneration: Int,
        observationUsesAutoFlashAe: Boolean,
        observedAeState: FlashAeState
    ): FlashShutterDecision {
        if (manualExposureActive) {
            return FlashShutterDecision(FlashCaptureRoute.ZSL, "manual_exposure_suppresses_flash")
        }
        if (mode.equals("Off", ignoreCase = true)) {
            return FlashShutterDecision(FlashCaptureRoute.ZSL, "flash_off")
        }
        if (!flashHardwareAvailable) {
            return FlashShutterDecision(FlashCaptureRoute.ZSL, "flash_hardware_unavailable")
        }
        if (mode.equals("On", ignoreCase = true)) {
            return FlashShutterDecision(FlashCaptureRoute.DEDICATED_STILL, "flash_on")
        }
        if (!mode.equals("Auto", ignoreCase = true)) {
            return FlashShutterDecision(FlashCaptureRoute.ZSL, "unknown_flash_mode")
        }
        if (!autoFlashAeModeSupported) {
            return FlashShutterDecision(FlashCaptureRoute.ZSL, "auto_flash_ae_mode_unsupported")
        }

        val observationCurrent = observationGeneration == currentPipelineGeneration
        val observationAuthoritative = observationCurrent && observationUsesAutoFlashAe
        if (observationAuthoritative && observedAeState == FlashAeState.CONVERGED) {
            return FlashShutterDecision(FlashCaptureRoute.ZSL, "auto_flash_not_required")
        }
        if (observationAuthoritative && observedAeState == FlashAeState.FLASH_REQUIRED) {
            return FlashShutterDecision(FlashCaptureRoute.DEDICATED_STILL, "auto_flash_required")
        }

        // SEARCHING/INACTIVE/PRECAPTURE/LOCKED/UNKNOWN or a stale/non-auto observation cannot prove
        // that flash is unnecessary. Reserve a dedicated still transaction and let the shutter-time
        // precapture sequence make the final HAL decision. In bright light ON_AUTO_FLASH simply will
        // not fire the main flash.
        return FlashShutterDecision(
            route = FlashCaptureRoute.DEDICATED_STILL,
            reason = "auto_flash_revalidate_at_shutter",
            autoDecisionUncertain = true
        )
    }
}
