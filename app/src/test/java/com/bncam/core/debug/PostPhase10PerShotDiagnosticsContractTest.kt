package com.bncam.core.debug

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PostPhase10PerShotDiagnosticsContractTest {
    private val appDir = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .firstOrNull { File(it, "src/main/java/com/bncam/core/debug/ShotLogger.kt").isFile }
        ?: error("Unable to locate app module")

    private fun read(path: String) = File(appDir, path).readText()

    @Test
    fun `debug output is shot scoped phone readable and deduplicated`() {
        val logger = read("src/main/java/com/bncam/core/debug/ShotLogger.kt")
        assertTrue("PHONE_LINE_WIDTH = 54" in logger)
        assertTrue("phoneSectionTitle" in logger)
        assertTrue("PHONE_VALUE_COLUMN" in logger)
        assertTrue("eventsLatestFor" in logger)
        assertTrue("duplicates collapsed" in logger)
        assertTrue("Deterministic on-device diagnostic rules; no cloud model" in logger)
        assertFalse("\"=\".repeat(72)" in logger)
        assertFalse("\"-\".repeat(72)" in logger)
    }

    @Test
    fun `session telemetry remains in memory instead of filling shot files`() {
        val aggregator = read("src/main/java/com/bncam/core/debug/DiagnosticsAggregator.kt")
        assertTrue("Central in-memory diagnostics bus" in aggregator)
        assertTrue("MAX_RECENT_RECORDS" in aggregator)
        assertTrue("cleanContent" in aggregator)
        assertTrue("runtimeJson=" in aggregator)
        assertFalse("bufferedWriter" in aggregator)
    }
}
