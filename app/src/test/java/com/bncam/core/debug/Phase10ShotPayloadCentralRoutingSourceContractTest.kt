package com.bncam.core.debug

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Phase10ShotPayloadCentralRoutingSourceContractTest {
    private val appDir = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .firstOrNull { File(it, "src/main/java/com/bncam/core/debug/ShotLogger.kt").isFile }
        ?: error("Unable to locate app module")

    private fun read(path: String) = File(appDir, path).readText()

    @Test
    fun `legacy payloads are folded into stable per shot files`() {
        val logger = read("src/main/java/com/bncam/core/debug/ShotLogger.kt")
        assertTrue("captureRecipeJson = content" in logger)
        assertTrue("noiseModelTraceJson = content" in logger)
        assertTrue("publishPublicFile(PROFILE_FILE" in logger)
        assertTrue("publishPublicFile(ISP_FILE" in logger)
        assertTrue("DiagnosticsAggregator.recordNamedPayload" in logger)
        assertFalse("writeTextFile(\"capture_recipe.json\"" in logger)
        assertFalse("writeTextFile(\"noise_model_trace.json\"" in logger)
    }

    @Test
    fun `persistent export is restricted to seven named shot files`() {
        val logger = read("src/main/java/com/bncam/core/debug/ShotLogger.kt")
        assertTrue("publishInitialFileSet()" in logger)
        assertTrue("SUMMARY_FILE to \"SUMMARY\"" in logger)
        assertTrue("VENDOR_FILE to \"VENDOR TAG INJECTION\"" in logger)
        assertTrue("persistent output is restricted to the seven stable per-shot files" in logger)
    }
}
