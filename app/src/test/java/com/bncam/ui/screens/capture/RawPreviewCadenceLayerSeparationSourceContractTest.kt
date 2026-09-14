package com.bncam.ui.screens.capture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPreviewCadenceLayerSeparationSourceContractTest {
    private val cadence = File("src/main/java/com/bncam/ui/screens/capture/RawPreviewCadenceDiagnostics.kt").readText()
    private val renderer = File("src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt").readText()

    @Test
    fun reportSeparatesAllCadenceOwners() {
        listOf(
            "captureResultSensorFps=",
            "imageReaderSensorFps=",
            "imageReaderArrivalFps=",
            "rendererPublicationFps=",
            "glAcceptedFps=",
            "drawSubmitFps=",
            "actualDisplayPresentationFps="
        ).forEach { assertTrue("missing cadence owner $it", cadence.contains(it)) }
    }

    @Test
    fun rendererPublicationIsMeasuredAfterNativeCompletion() {
        assertTrue(cadence.contains("rendererPublicationNs"))
        assertTrue(cadence.contains("fun rendererPublished("))
        assertTrue(renderer.contains("RawPreviewCadenceDiagnostics.rendererPublished("))
    }

    @Test
    fun gpuFallbackShareAndStagePercentilesRemainVisible() {
        assertTrue(cadence.contains("gpuResidentPublicationPercent="))
        assertTrue(cadence.contains("cpuFallbackPublishedFrames="))
        assertTrue(cadence.contains("rawInputPackingMs="))
        assertTrue(cadence.contains("rawGpuKernelMs="))
        assertTrue(cadence.contains("rawGpuSyncOverheadMs="))
        assertTrue(cadence.contains("rawGpuHostReadbackMs="))
        assertTrue(cadence.contains("sensorToDisplayPresentMs="))
        assertTrue(cadence.contains("p95"))
        assertTrue(cadence.contains("p99"))
    }
}
