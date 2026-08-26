package com.bncam.ui.screens.capture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewfinderLiveControlContractTest {
    private val cameraScreen = File("src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt")
        .readText()

    @Test
    fun `ev drag submits directly instead of waiting for value keyed launched effect`() {
        assertTrue(cameraScreen.contains("bnCameraManager.setExposure(value, activeLens.id)"))
        assertFalse(cameraScreen.contains("LaunchedEffect(manualExposureValue, evSliderActive"))
    }
    @Test
    fun `dedicated exposure dials submit manual exposure directly during drag`() {
        assertFalse(cameraScreen.contains("LaunchedEffect(\n        assignedIsoValue, assignedShutterNs"))
        assertTrue(cameraScreen.countOccurrences("bnCameraManager.setManualIsoAndShutter(") >= 5)
        assertTrue(cameraScreen.contains("val nextShutterNs = parseShutterString(label)"))
        assertTrue(cameraScreen.contains("val nextIso = parseIsoString(label)"))
    }

    @Test
    fun `creative side controls push yuv renderer directly during pointer movement`() {
        val view = File("src/main/java/com/bncam/ui/screens/capture/FocusPeakingView.kt").readText()
        assertTrue(view.contains("fun setLiveCreativeTuning("))
        assertTrue(cameraScreen.contains("fun pushLiveCreativeTuningToPreview()"))
        assertTrue(cameraScreen.contains("previewViewRef[0]?.setLiveCreativeTuning("))
        assertTrue(cameraScreen.countOccurrences("pushLiveCreativeTuningToPreview()") >= 9)
    }

    @Test
    fun `white balance side controls push resolved yuv compensation without compose roundtrip`() {
        assertTrue(cameraScreen.contains("fun pushLiveWhiteBalanceToPreview()"))
        assertTrue(cameraScreen.contains("bnCameraManager.liveWhiteBalanceDisplayCompensation.value"))
        assertTrue(cameraScreen.contains("previewViewRef[0]?.setLiveColorTuning("))
        assertTrue(cameraScreen.countOccurrences("pushLiveWhiteBalanceToPreview()") >= 5)
    }


    @Test
    fun `focus and ev hot values are read inside side control scope`() {
        assertTrue(cameraScreen.contains("val manualExposureValueState = remember { mutableFloatStateOf(0f) }"))
        assertTrue(cameraScreen.contains("val manualFocusDistanceState = remember { mutableFloatStateOf(0.5f) }"))
        assertTrue(cameraScreen.contains("focusValue = { manualFocusDistanceState.floatValue }"))
        assertTrue(cameraScreen.contains("exposureValue = { manualExposureValueState.floatValue }"))
        assertTrue(cameraScreen.contains("focusValue: () -> Float"))
        assertTrue(cameraScreen.contains("exposureValue: () -> Float"))
        assertFalse(cameraScreen.contains("focusValue = manualFocusDistance"))
        assertFalse(cameraScreen.contains("exposureValue = manualExposureValue"))
    }

    @Test
    fun `zoom value is observed only inside zoom consuming child scopes`() {
        assertTrue(cameraScreen.contains("val currentZoomLevelState = remember { mutableFloatStateOf(1f) }"))
        assertTrue(cameraScreen.contains("digitalZoom = { currentZoomLevelState.floatValue }"))
        assertTrue(cameraScreen.contains("currentZoom = { currentZoomLevelState.floatValue }"))
        assertTrue(cameraScreen.contains("zoomValue = { currentZoomLevelState.floatValue }"))
        assertTrue(cameraScreen.contains("digitalZoom: () -> Float"))
        assertTrue(cameraScreen.contains("zoomValue: () -> Float"))
        assertFalse(cameraScreen.contains("var currentZoomLevel by remember"))
    }

    @Test
    fun `zoom hot paths push raw display crop directly before camera2 catches up`() {
        assertTrue(cameraScreen.contains("fun applyLiveZoom(zoom: Float)"))
        assertTrue(cameraScreen.contains("previewViewRef[0]?.setRawDisplayZoom(safeZoom)"))
        assertTrue(cameraScreen.contains("bnCameraManager.setZoom(safeZoom)"))
        assertTrue(cameraScreen.countOccurrences("applyLiveZoom(") >= 10)
        assertTrue(cameraScreen.countOccurrences("bnCameraManager.setZoom(") == 1)
    }

    @Test
    fun `high frequency creative state is isolated from parent camera screen recomposition`() {
        assertFalse(cameraScreen.contains("val liveViewfinderTuning by ViewfinderLiveTuning.state.collectAsState()"))
        assertTrue(cameraScreen.contains("val liveTuning by ViewfinderLiveTuning.state.collectAsState()"))
        assertFalse(cameraScreen.contains("saturationValue = liveViewfinderTuning.saturationOffset"))
        assertFalse(cameraScreen.contains("contrastValue = liveViewfinderTuning.contrastOffset"))
    }


    @Test
    fun `slider feedback no longer invalidates the parent camera screen on every pointer sample`() {
        assertTrue(cameraScreen.contains("private class SliderFeedbackController"))
        assertTrue(cameraScreen.contains("SliderFeedbackOverlay(controller = sliderFeedbackController)"))
        assertTrue(cameraScreen.contains("sliderFeedbackController.show(text)"))
        assertFalse(cameraScreen.contains("var sliderFeedbackText by remember"))
        assertFalse(cameraScreen.contains("var sliderFeedbackGeneration by remember"))
    }

    private fun String.countOccurrences(token: String): Int =
        windowed(token.length, 1).count { it == token }

}
