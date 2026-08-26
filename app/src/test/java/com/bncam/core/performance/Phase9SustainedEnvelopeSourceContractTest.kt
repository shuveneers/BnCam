package com.bncam.core.performance

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Phase9SustainedEnvelopeSourceContractTest {
    private val appDir = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .firstOrNull { File(it, "src/main/java/com/bncam/core/debug/CapturePerformanceTracker.kt").isFile }
        ?: error("Unable to locate app module")

    private fun read(path: String) = File(appDir, path).readText()

    @Test
    fun `capture performance tracker records start terminal thermal and memory envelope`() {
        val tracker = read("src/main/java/com/bncam/core/debug/CapturePerformanceTracker.kt")
        val single = read("src/main/java/com/bncam/core/runners/SingleFrameRunner.kt")
        val multi = read("src/main/java/com/bncam/core/runners/MultiFrameRunner.kt")
        assertTrue(tracker.contains("fun sampleSystemState(context: Context, stage: String)"))
        assertTrue(tracker.contains("currentThermalStatus"))
        assertTrue(tracker.contains("getThermalHeadroom(0)"))
        assertTrue(tracker.contains("systemAvailableMemoryBytes"))
        assertTrue(tracker.contains("sampleSystemState(context, \"terminal\")"))
        assertTrue(single.contains("sampleSystemState(context, \"processing_start\")"))
        assertTrue(multi.contains("sampleSystemState(context, \"processing_start\")"))
        assertTrue(single.contains("thermalStatusAtShutter"))
        assertTrue(multi.contains("thermalStatusAtShutter"))
    }

    @Test
    fun `thermal telemetry never becomes a quality adaptation path`() {
        val tracker = read("src/main/java/com/bncam/core/debug/CapturePerformanceTracker.kt")
        val resolution = read("src/main/java/com/bncam/core/runtime/RawPreviewResolutionPolicy.kt")
        assertTrue(tracker.contains("systemEnvelope.qualityAdaptationApplied\", false"))
        assertFalse(tracker.contains("setResolution"))
        assertFalse(tracker.contains("frameCount ="))
        assertTrue(resolution.contains("QUALITY_MAX_WIDTH = 1024"))
        assertTrue(resolution.contains("QUALITY_MAX_HEIGHT = 768"))
    }

    @Test
    fun `raw cadence remains timestamp based sustained measurement`() {
        val cadence = read("src/main/java/com/bncam/ui/screens/capture/RawPreviewCadenceDiagnostics.kt")
        assertTrue(cadence.contains("sensorTimestampNs"))
        assertTrue(cadence.contains("uniqueSourceFps"))
        assertTrue(cadence.contains("presentationFps"))
        assertTrue(cadence.contains("sensorToDisplayPresentMs"))
    }
}
