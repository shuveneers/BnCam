package com.bncam.core.debug

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Phase10CoreProducerCentralRoutingSourceContractTest {
    private val appDir = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .firstOrNull { File(it, "src/main/java/com/bncam/core/debug/BenchmarkWriter.kt").isFile }
        ?: error("Unable to locate app module")

    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun benchmark_performance_and_device_telemetry_route_only_through_aggregator() {
        val benchmark = source("src/main/java/com/bncam/core/debug/BenchmarkWriter.kt")
        val performance = source("src/main/java/com/bncam/core/debug/CapturePerformanceTracker.kt")
        val telemetry = source("src/main/java/com/bncam/core/debug/DeviceTelemetryLogger.kt")
        val application = source("src/main/java/com/bncam/BnCamApplication.kt")

        listOf(benchmark, performance, telemetry).forEach { producer ->
            assertTrue("DiagnosticsAggregator" in producer)
            assertFalse("FileWriter(" in producer)
            assertFalse("appendText(" in producer)
            assertFalse("writeText(" in producer)
        }
        assertTrue("DiagnosticsAggregator.initialize(applicationContext)" in application)
    }
}
