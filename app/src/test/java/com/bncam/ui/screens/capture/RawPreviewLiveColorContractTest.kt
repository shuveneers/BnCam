package com.bncam.ui.screens.capture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPreviewLiveColorContractTest {
    private val source = File("src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt").readText()
    private val manager = File("src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()

    @Test
    fun `raw preview has revisioned lightweight live color overrides`() {
        assertTrue(source.contains("fun updateLiveColorTuning("))
        assertTrue(source.contains("liveColor.saturationOffset - request.config.liveSaturationOffset"))
        assertTrue(source.contains("liveColor.contrastOffset - request.config.liveContrastOffset"))
        assertTrue(source.contains("profileSaturation = effectiveSaturation"))
        assertTrue(source.contains("profileContrast = effectiveContrast"))
    }

    @Test
    fun `manager pushes live color directly without invalidating raw calibration`() {
        val notify = manager.substringAfter("fun notifyViewfinderLiveTuningChanged()")
            .substringBefore("fun reportRawPreviewGlUploadTime")
        assertTrue(notify.contains("rawPreviewRenderer.updateLiveColorTuning("))
        assertTrue(!notify.contains("rawPreviewConfiguredGeneration = -1"))
        assertTrue(!notify.contains("rawPreviewConfigBuildInFlight = false"))
    }
}
