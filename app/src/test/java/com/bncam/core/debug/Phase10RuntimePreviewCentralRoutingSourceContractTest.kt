package com.bncam.core.debug

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Phase10RuntimePreviewCentralRoutingSourceContractTest {
    private val appDir = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .firstOrNull { File(it, "src/main/java/com/bncam/core/debug/RawRecoveryTrace.kt").isFile }
        ?: error("Unable to locate app module")

    private fun read(path: String) = File(appDir, path).readText()

    @Test
    fun `runtime preview and recovery diagnostics publish only through central aggregator`() {
        val paths = listOf(
            "src/main/java/com/bncam/core/debug/RawRecoveryTrace.kt",
            "src/main/java/com/bncam/core/vulkan/VulkanRuntimeOwner.kt",
            "src/main/java/com/bncam/ui/screens/capture/RawPreviewInteropCapabilities.kt",
            "src/main/java/com/bncam/ui/screens/capture/RawPreviewCadenceDiagnostics.kt",
            "src/main/java/com/bncam/core/runtime/PortabilityDiagnosticsReport.kt"
        )
        paths.forEach { path ->
            val source = read(path)
            assertTrue("DiagnosticsAggregator" in source, "Missing central sink in $path")
            assertFalse("FileWriter(" in source, "Direct FileWriter remains in $path")
            assertFalse(".appendText(" in source, "Direct append remains in $path")
            assertFalse(".writeText(" in source, "Direct write remains in $path")
        }
    }

    @Test
    fun `camera runtime trace no longer owns capture runtime trace file`() {
        val cameraManager = read("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        assertTrue("DiagnosticsAggregator.Stream.CAPTURE" in cameraManager)
        assertFalse("capture_runtime_trace.txt" in cameraManager)
    }

    @Test
    fun `raw recovery preserves bounded producer queue before central publication`() {
        val recovery = read("src/main/java/com/bncam/core/debug/RawRecoveryTrace.kt")
        assertTrue("MAX_PENDING_LINES" in recovery)
        assertTrue("Semaphore(MAX_PENDING_LINES)" in recovery)
        assertTrue("TRACE_LINES_DROPPED" in recovery)
        assertTrue("MAX_DRAIN_BATCH_LINES" in recovery)
    }
}
