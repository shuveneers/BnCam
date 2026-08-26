package com.bncam.core.debug

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Phase10UnifiedDiagnosticsFinalContractTest {
    private val appDir = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .firstOrNull { File(it, "src/main/java/com/bncam/core/debug/DiagnosticsAggregator.kt").isFile }
        ?: error("Unable to locate app module")

    private fun read(path: String) = File(appDir, path).readText()

    @Test
    fun `persistent diagnostics publish to Documents BnCamDebug with bounded io`() {
        val storage = read("src/main/java/com/bncam/core/debug/PublicShotDiagnosticsStorage.kt")
        assertTrue("Environment.DIRECTORY_DOCUMENTS" in storage)
        assertTrue("/BnCamDebug" in storage)
        assertTrue("MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)" in storage)
        assertTrue("MediaStore.MediaColumns.RELATIVE_PATH" in storage)
        assertTrue("MAX_PENDING_WRITES = 64" in storage)
        assertTrue("ArrayBlockingQueue<Runnable>" in storage)
        assertFalse("getExternalFilesDir(null)" in storage)
    }

    @Test
    fun `shot folder identity and transient crash state have separate ownership`() {
        val logger = read("src/main/java/com/bncam/core/debug/ShotLogger.kt")
        assertTrue("val folderName = \"${'$'}{safeSensorName}_${'$'}{modeToken}_${'$'}timeStamp\"" in logger)
        assertTrue("yyyyMMdd_HHmmss_SSS" in logger)
        assertTrue("TRANSIENT_STATE_PREFIX" in logger)
        assertTrue("currentStateDir" in logger)
        assertTrue("cleanupTerminalStateDirectories()" in logger)
        assertTrue("native_stage_heartbeat.json" in logger)
        assertTrue("status.json" in logger)
        assertTrue("deleteRecursively()" in logger)
    }

    @Test
    fun `runtime producers remain centralized rather than writing their own debug files`() {
        val roots = listOf(File(appDir, "src/main/java"), File(appDir, "src/debug/java"), File(appDir, "src/release/java"))
        val writerNeedles = listOf(".appendText(", ".writeText(", "FileWriter(")
        val directWriters = roots.asSequence()
            .filter { it.exists() }
            .flatMap { root -> root.walkTopDown().asSequence().filter { it.isFile && it.extension == "kt" } }
            .filter { file -> writerNeedles.any { it in file.readText() } }
            .map { it.relativeTo(appDir).invariantSeparatorsPath }
            .toSet()
        val allowed = setOf(
            "src/main/java/com/bncam/core/debug/ShotLogger.kt",
            "src/main/java/com/bncam/core/capture/HardwareQualificationRunner.kt"
        )
        assertTrue(directWriters.all { it in allowed }, "Unexpected direct diagnostic writers: ${'$'}{directWriters - allowed}")
    }
}
