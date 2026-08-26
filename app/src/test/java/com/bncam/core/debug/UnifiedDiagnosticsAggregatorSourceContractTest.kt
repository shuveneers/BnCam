package com.bncam.core.debug

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnifiedDiagnosticsAggregatorSourceContractTest {
    private val appDir = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .firstOrNull { File(it, "src/main/java/com/bncam/core/debug/DiagnosticsAggregator.kt").isFile }
        ?: error("Unable to locate app module")

    private fun read(path: String) = File(appDir, path).readText()

    @Test
    fun `central diagnostics bus stays bounded and does not persist root stream files`() {
        val source = read("src/main/java/com/bncam/core/debug/DiagnosticsAggregator.kt")
        assertTrue("MAX_RECENT_RECORDS = 256" in source)
        assertTrue("ArrayDeque<Record>" in source)
        assertTrue("fun recordCaptureTrace(trace: ArchitectureCaptureTrace)" in source)
        assertTrue("fun snapshotForCapture(captureId: String)" in source)
        assertFalse("appendText(" in source)
        assertFalse("FileWriter(" in source)
        assertFalse("MAX_FILE_BYTES" in source)
    }

    @Test
    fun `shot scoped publisher owns seven stable debug files`() {
        val logger = read("src/main/java/com/bncam/core/debug/ShotLogger.kt")
        listOf(
            "01_SUMMARY.txt",
            "02_CAPTURE.txt",
            "03_PROFILE_SETTINGS.txt",
            "04_ISP.txt",
            "05_WARNINGS_ERRORS.txt",
            "06_FRAME_ANALYSIS.txt",
            "07_VENDOR_TAG_INJECTION.txt"
        ).forEach { fileName -> assertTrue("\"$fileName\"" in logger, "Missing $fileName") }
        assertTrue("publishPublicFile" in logger)
        assertTrue("PublicShotDiagnosticsStorage.publish" in logger)
    }
}
