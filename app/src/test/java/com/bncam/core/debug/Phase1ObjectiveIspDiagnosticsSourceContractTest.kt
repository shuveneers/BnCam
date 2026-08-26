package com.bncam.core.debug

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class Phase1ObjectiveIspDiagnosticsSourceContractTest {
    private val appDir = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .firstOrNull { File(it, "src/main/java/com/bncam/core/debug/ShotLogger.kt").isFile }
        ?: error("Unable to locate app module")

    @Test
    fun `central ISP report contains objective single frame RAW and YUV truth`() {
        val logger = File(appDir, "src/main/java/com/bncam/core/debug/ShotLogger.kt").readText()
        assertTrue("appendSingleFrameObjectiveTruth(payload)" in logger)
        assertTrue("Single-Frame Objective Truth — RAW" in logger)
        assertTrue("Single-Frame Objective Truth — YUV" in logger)
        assertTrue("Measured residual reduction" in logger)
        assertTrue("Residual gain through sharpening" in logger)
        assertTrue("CCM metadata pre-normalization" in logger)
        assertTrue("Resolved Vulkan chroma NR blend" in logger)
        assertTrue("Neighbour delta semantics" in logger)
        assertTrue("Sampled Y variance" in logger)
        assertTrue("Sampled U / V variance" in logger)
        assertTrue("Demosaic analysis mean / p90 gradient" in logger)
        assertTrue("Presentation base vibrance" in logger)
        assertTrue("Highlight neutralization applied" in logger)
    }

    @Test
    fun `YUV conversion warning is evaluated before RAW metric early return`() {
        val logger = File(appDir, "src/main/java/com/bncam/core/debug/ShotLogger.kt").readText()
        val start = logger.indexOf("private fun automatedIspFindings")
        val body = logger.substring(start, logger.indexOf("private fun noiseModelMetrics", start))
        assertTrue(body.indexOf("yuvDefaultCamera2ColorContract") < body.indexOf("noiseModelMetrics() ?: return@buildList"))
    }
}
