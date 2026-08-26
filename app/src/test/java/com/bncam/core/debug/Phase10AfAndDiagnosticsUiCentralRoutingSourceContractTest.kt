package com.bncam.core.debug

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Phase10AfAndDiagnosticsUiCentralRoutingSourceContractTest {
    private val appDir = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .firstOrNull { File(it, "src/debug/java/com/bncam/core/debug/AfGroundTruthTrace.kt").isFile }
        ?: error("Unable to locate app module")

    private fun read(path: String) = File(appDir, path).readText()

    @Test
    fun `af ground truth keeps debug producer but no standalone jsonl file`() {
        val trace = read("src/debug/java/com/bncam/core/debug/AfGroundTruthTrace.kt")
        assertTrue("DiagnosticsAggregator.Stream.CAMERA" in trace)
        assertTrue("AF GROUND TRUTH" in trace)
        assertFalse("af_ground_truth.jsonl" in trace)
        assertFalse(".appendText(" in trace)
        assertFalse(".writeText(" in trace)
    }

    @Test
    fun `settings describe diagnostic categories rather than obsolete filenames`() {
        val settings = read("src/main/java/com/bncam/ui/screens/settings/AppSettingsScreen.kt")
        assertTrue("Enable diagnostics" in settings)
        assertTrue("Documents/BnCamDebug" in settings)
        assertTrue("seven stable" in settings)
        listOf(
            "Summary.txt",
            "Active mode.txt",
            "Frame analysis.txt",
            "Warnings.txt",
            "Pipeline debug.txt",
            "Injection of tags.txt"
        ).forEach { obsolete -> assertFalse(obsolete in settings, "Obsolete diagnostics UI label: $obsolete") }
    }
}
