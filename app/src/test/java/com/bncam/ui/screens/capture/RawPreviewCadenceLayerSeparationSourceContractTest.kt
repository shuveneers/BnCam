package com.bncam.ui.screens.capture

import java.io.File
import org.junit.Assert.assertFalse
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
    fun cadenceFrameKeyIncludesProducerAndCsvPublishesProvenance() {
        assertTrue(cadence.contains("private data class FrameKey("))
        assertTrue(cadence.contains("val producerKind: RawPreviewProducerKind"))
        assertTrue(cadence.contains("FrameKey(sensorTimestampNs, producerKind)"))
        assertTrue(cadence.contains("sensorTimestampNs,producerKind,sourceArrivalNs"))
        assertTrue(cadence.contains("producerCadence="))
    }

    @Test
    fun rendererDuplicateSuppressionIsPerProducerNotGlobal() {
        assertTrue(renderer.contains("RawPreviewProducerTimestampGate()"))
        assertTrue(renderer.contains("producerTimestampGate.accept(producerKind, sensorTimestampNs)"))
        assertTrue(renderer.contains("producerTimestampGate.reset()"))
        assertFalse(renderer.contains("private val latestOfferedSensorTimestampNs"))
        assertTrue(renderer.contains("exactFrameColorPairs[request.sensorTimestampNs]"))
        assertFalse(renderer.contains("exactFrameColorPairs.remove(request.sensorTimestampNs)"))
        assertFalse(renderer.contains("exactFrameColorPairs.remove(dropped.sensorTimestampNs)"))
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
