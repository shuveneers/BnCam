package com.bncam.ui.screens.capture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Final source-level guard for the UI-6 live-control phase.
 *
 * The contract intentionally spans Compose input, Camera2 request ownership and both YUV/RAW
 * render paths. A control is not considered "live" merely because its UI value moves; the active
 * preview route must consume the value before pointer-up.
 */
class ViewfinderLiveControlPhaseCompletionContractTest {
    private val cameraScreen = File("src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt").readText()
    private val manager = File("src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()
    private val focusPeakingView = File("src/main/java/com/bncam/ui/screens/capture/FocusPeakingView.kt").readText()
    private val exposureDial = File("src/main/java/com/bncam/ui/screens/capture/ExposureDialRing.kt").readText()

    @Test
    fun `generic side controls publish values from pointer movement rather than pointer up`() {
        val slider = cameraScreen.substringAfter("fun ExpandableVerticalSlider(")
            .substringBefore("// ==========================================", missingDelimiterValue = cameraScreen)
        assertTrue(slider.contains("updateFromPointer(change.position.y)"))
        assertTrue(slider.contains(")?.let(onValueChange)"))
        val dragEnd = slider.substringAfter("onDragEnd = {").substringBefore("onDragCancel = {")
        assertFalse(dragEnd.contains("onValueChange("))
    }

    @Test
    fun `focus ev and zoom side controls hit live engine paths on every callback`() {
        assertTrue(cameraScreen.countOccurrences("bnCameraManager.setFocus(newValue, activeLens.id)") >= 2)
        assertTrue(cameraScreen.countOccurrences("bnCameraManager.setExposure(value, activeLens.id)") >= 2)
        assertTrue(cameraScreen.countOccurrences("applyLiveZoom(zoom)") >= 2)
        assertTrue(cameraScreen.contains("previewViewRef[0]?.setRawDisplayZoom(safeZoom)"))
        assertTrue(cameraScreen.contains("bnCameraManager.setZoom(safeZoom)"))
    }

    @Test
    fun `iso and shutter dials publish crossed detents before drag end`() {
        val drag = exposureDial.substringAfter("onDrag = { change, dragAmount ->")
            .substringBefore("onDragEnd = {")
        assertTrue(drag.contains("onValueChange(latestValues[newIndex])"))
        assertTrue(cameraScreen.countOccurrences("bnCameraManager.setManualIsoAndShutter(") >= 5)
    }

    @Test
    fun `creative tuning has direct yuv and raw render paths`() {
        assertTrue(cameraScreen.contains("previewViewRef[0]?.setLiveCreativeTuning("))
        assertTrue(manager.contains("rawPreviewRenderer.updateLiveColorTuning("))
        assertTrue(focusPeakingView.contains("if (useRaw) 0f else liveSaturationOffset"))
        assertTrue(focusPeakingView.contains("if (useRaw) 0f else liveContrastOffset"))
    }

    @Test
    fun `white balance is routed by displayed source and sensor ownership`() {
        val wb = manager.substringAfter("private fun updateLiveWhiteBalanceDisplayCompensation(")
            .substringBefore("private fun applyLiveWhiteBalancePolicy")
        assertTrue(wb.contains("effectiveViewfinderSource == ViewfinderEffectiveSource.YUV"))
        assertTrue(wb.contains("targetViewfinderSource == ViewfinderEffectiveSource.YUV"))
        assertTrue(wb.contains("previewCaptureResult(result, identity.physicalCameraId)"))
        assertFalse(wb.contains("activePipelineIdentity.bufferFormat"))
        assertTrue(manager.contains("rawPreviewRenderer.updateWhiteBalanceGains(rawPreviewTarget)"))
        assertTrue(cameraScreen.contains("pushLiveWhiteBalanceToPreview()"))
        assertTrue(focusPeakingView.contains("linearRgb *= uLiveWhiteBalance"))
    }

    @Test
    fun `camera2 live commands are latest wins and session generation scoped`() {
        val queue = manager.substringAfter("private fun enqueuePreviewControl(")
            .substringBefore("private fun clearPendingPreviewControls")
        assertTrue(queue.contains("pendingPreviewControls[key] = command"))
        assertTrue(queue.contains("command.generation != pipelineGeneration"))
        assertTrue(queue.contains("command.sessionEpoch != activeConfiguredSessionEpoch"))
        assertTrue(manager.contains("enqueuePreviewControl(\"focus_distance\""))
        assertTrue(manager.contains("enqueuePreviewControl(\"digital_zoom\""))
        assertTrue(manager.contains("enqueuePreviewControl(\"preview_policy\""))
    }

    @Test
    fun `high frequency feedback is isolated from parent camera composition`() {
        assertTrue(cameraScreen.contains("private class SliderFeedbackController"))
        assertTrue(cameraScreen.contains("SliderFeedbackOverlay(controller = sliderFeedbackController)"))
        assertFalse(cameraScreen.contains("var sliderFeedbackText by remember"))
    }

    private fun String.countOccurrences(token: String): Int =
        windowed(token.length, 1).count { it == token }
}
