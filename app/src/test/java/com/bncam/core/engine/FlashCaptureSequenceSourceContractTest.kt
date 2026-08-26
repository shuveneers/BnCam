package com.bncam.core.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlashCaptureSequenceSourceContractTest {
    private fun appRoot(): File {
        val cwd = File(System.getProperty("user.dir"))
        return if (File(cwd, "src/main").isDirectory) cwd else File(cwd, "app")
    }

    @Test
    fun dedicatedFlashUsesStateDrivenPrecaptureForEveryWarmBufferFormat() {
        val manager = File(appRoot(), "src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()
        val flashBlock = manager.substringAfter("ROBUST ACTIVE FLASH SEQUENCE")
            .substringBefore("var hdrBracket: HdrBracketCaptureContext?")

        assertTrue(flashBlock.contains("waitForFlashPrecaptureCompletion("))
        assertTrue(manager.contains("camera3AObservationHistory"))
        assertTrue(manager.contains("findCamera3AObservation("))
        assertTrue(flashBlock.contains("FLASH_AE_PRECAPTURE_TRIGGER"))
        assertTrue(flashBlock.contains("FLASH_ASSIST_TORCH_RELEASE"))
        assertTrue(flashBlock.contains("submitRepeatingRequestWithProvenance("))
        assertFalse(flashBlock.contains("delay(250)"))
        assertFalse(manager.contains("Post-shutter flash fallback is disabled for RAW_SENSOR"))
        assertFalse(manager.contains("activeZslFormat != ImageFormat.RAW_SENSOR"))
    }

    @Test
    fun dedicatedFlashForcesSingleFrameAndProvesExactStillPair() {
        val manager = File(appRoot(), "src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()
        val single = File(appRoot(), "src/main/java/com/bncam/core/runners/SingleFrameRunner.kt").readText()

        assertTrue(manager.contains("dedicatedFlashStill -> CaptureStrategy.SINGLE_FRAME_ZSL"))
        assertTrue(manager.contains("awaitAndLeaseExactRequestFrame("))
        assertTrue(manager.contains("awaitAndLeaseExactRequestEpochFrame("))
        assertTrue(single.contains("frame.controlRequestEpoch == currentSubmittedControlRequestEpochAtShutter"))
        assertTrue(single.contains("Post-shutter still contract violated: exact Camera2 still frame is missing"))
    }
    @Test
    fun tapTorchIsOnlyCapabilityAwareFocusAssistAndNeverTheMainFlashContract() {
        val manager = File(appRoot(), "src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()
        val tapBlock = manager.substringAfter("val tapFlashControlPlan = resolveFlashControlPlan")
            .substringBefore("if (continuousTapMode || resolvedTapMode")

        assertTrue(tapBlock.contains("tapFlashControlPlan != null"))
        assertTrue(tapBlock.contains("CONTROL_AE_STATE_FLASH_REQUIRED"))
        assertTrue(tapBlock.contains("FLASH_MODE_TORCH"))
        assertTrue(tapBlock.contains("FLASH_FOCUS_ASSIST"))
        assertTrue(manager.contains("if (dedicatedFlashStill)"))
    }

    @Test
    fun flashCapabilityIsResolvedFromCamera2RequestOwnerNotPhysicalLensDescriptor() {
        val manager = File(appRoot(), "src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()

        assertTrue(manager.contains("val flashControlCameraId = synchronized(pipelineLock)"))
        assertTrue(manager.contains("activePipelineIdentity?.logicalCameraId"))
        assertTrue(manager.contains("resolveFlashShutterDecision(flashCharacteristics"))
        assertTrue(manager.contains("ownerCamera=\$flashControlCameraId"))
    }

}
