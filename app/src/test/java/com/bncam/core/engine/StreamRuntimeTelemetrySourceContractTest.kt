package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamRuntimeTelemetrySourceContractTest {
    private val appDir = File("app")

    private fun source(path: String): String = File(appDir, path).readText()

    @Test
    fun `camera runtime publishes one coherent stream telemetry authority`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        assertTrue(manager.contains("StreamRuntimeTelemetry.resolvedConfiguration("))
        assertTrue(manager.contains("StreamRuntimeTelemetry.sessionConfigured("))
        assertTrue(manager.contains("StreamRuntimeTelemetry.sessionConfigureFailed("))
        assertTrue(manager.contains("StreamRuntimeTelemetry.sessionCreationFailed("))
        assertTrue(manager.contains("StreamRuntimeTelemetry.sessionClosed("))
        assertTrue(manager.contains("StreamRuntimeTelemetry.requestGraphUpdated("))
        assertTrue(manager.contains("StreamRuntimeTelemetry.repeatingRequestSubmitted("))
        assertTrue(manager.contains("StreamRuntimeTelemetry.captureResult("))
        assertTrue(manager.contains("\"streamRuntimeTelemetry\" to StreamRuntimeTelemetry.latestReport()"))
    }

    @Test
    fun `raw producer arrivals feed primary and support cadence independently`() {
        val diagnostics = source("src/main/java/com/bncam/ui/screens/capture/RawPreviewCadenceDiagnostics.kt")
        assertTrue(diagnostics.contains("StreamRuntimeTelemetry.producerFrameArrived("))
        assertTrue(diagnostics.contains("RawPreviewProducerKind.CANONICAL_RING -> BnCamStreamRoleIds.PRIMARY_BUFFER"))
        assertTrue(diagnostics.contains("RawPreviewProducerKind.CUSTOM_IMAGE_READER -> BnCamStreamRoleIds.RAW_PREVIEW_SUPPORT"))
    }

    @Test
    fun `telemetry does not merge fps into stream configuration authority`() {
        val model = source("src/main/java/com/bncam/core/engine/StreamArchitectureModel.kt")
        val telemetry = source("src/main/java/com/bncam/core/engine/StreamRuntimeTelemetry.kt")
        assertTrue(model.contains("FPS is intentionally absent"))
        assertTrue(telemetry.contains("requestedRepeatingFpsLower"))
        assertTrue(telemetry.contains("captureResultSensorFps"))
        assertTrue(telemetry.contains("producerCadence"))
    }
}
