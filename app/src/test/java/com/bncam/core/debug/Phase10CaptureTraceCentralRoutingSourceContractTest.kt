package com.bncam.core.debug

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Phase10CaptureTraceCentralRoutingSourceContractTest {
    private val appDir = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .firstOrNull { File(it, "src/main/java/com/bncam/core/debug/ShotLogger.kt").isFile }
        ?: error("Unable to locate app module")

    private fun read(path: String) = File(appDir, path).readText()

    @Test
    fun `shot logger publishes typed capture trace only through central aggregator`() {
        val logger = read("src/main/java/com/bncam/core/debug/ShotLogger.kt")
        assertTrue(logger.contains("DiagnosticsAggregator.recordCaptureTrace(trace)"))
        assertTrue(logger.contains("DiagnosticsAggregator.recordVulkanRuntime("))
        assertFalse(logger.contains("writeTextFile(\"capture_trace.json\""))
        assertFalse(logger.contains("writeTextFile(section.fileName"))
        assertFalse(logger.contains("writeTextFile(\"vulkan_runtime.json\""))
        assertFalse(logger.contains("writeTextFile(\"Vulkan Runtime.txt\""))
    }

    @Test
    fun `trace section model no longer carries obsolete per section filenames`() {
        val trace = read("src/main/java/com/bncam/core/tracing/ArchitectureCaptureTrace.kt")
        assertTrue(trace.contains("enum class CaptureTraceSection {"))
        assertFalse(trace.contains("00 Overview.txt"))
        assertFalse(trace.contains("14 Exceptions.txt"))
        assertFalse(trace.contains("val fileName: String"))
    }
}
