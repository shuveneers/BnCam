package com.bncam.core.capture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrEnhancedProcessingPublicationTruthSourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/runners/MultiFrameRunner.kt").isFile }
        ?: error("Cannot locate app module")

    @Test fun `hdr enhanced isp telemetry reports measured native backend truth`() {
        val runner = File(appDir, "src/main/java/com/bncam/core/runners/MultiFrameRunner.kt").readText()

        assertTrue(
            runner.contains(
                "HDR_ENHANCED_ISP_STARTED: route=RAW_MASTER_ISP requestedGpuFirst=true actualBackend=PENDING"
            )
        )
        assertTrue(runner.contains("boolStat(hdrIspStats, \"rawResidentEntryUsed\", false)"))
        assertTrue(runner.contains("boolStat(hdrIspStats, \"rawResidentCpuFallbackUsed\", false)"))
        assertTrue(runner.contains("hdrIspStats[\"rawNormalizeBackend\"] ?: \"UNAVAILABLE\""))
        assertTrue(runner.contains("hdrIspStats, \"vulkanDemosaicUsedForOutput\""))
        assertTrue(runner.contains("hdrIspStats, \"vulkanAwbCcmExecutionSucceeded\""))
        assertTrue(runner.contains("hdrIspStats, \"vulkanToneUsedForOutput\""))
        assertFalse(runner.contains("HDR_ENHANCED_ISP_COMPLETE: ispComplete=true backend=VulkanIspCore"))
    }

    @Test fun `hdr enhanced success publication requires real merge isp jpeg and saved output`() {
        val runner = File(appDir, "src/main/java/com/bncam/core/runners/MultiFrameRunner.kt").readText()
        val qualification = runner.substringAfter("val hdrEnhancedPublicationQualified = hdrEnhancedActive &&")
            .substringBefore("if (hdrEnhancedActive) {")

        assertTrue(qualification.contains("jpegSaved"))
        assertTrue(qualification.contains("hdrEnhancedAlignmentCompletedActual"))
        assertTrue(qualification.contains("hdrEnhancedTemporalMergeCompletedActual"))
        assertTrue(qualification.contains("hdrEnhancedIspCompletedActual"))
        assertTrue(qualification.contains("hdrEnhancedJpegCompletedActual"))

        assertTrue(runner.contains("HDR_ENHANCED_FALLBACK_PUBLISHED: status=FALLBACK_OUTPUT"))
        val successLogIndex = runner.indexOf("HDR_ENHANCED_PUBLISHED: status=SUCCESS")
        val markPublishedIndex = runner.indexOf("workReservation.markPublished(stringOutputs)")
        assertTrue(markPublishedIndex >= 0)
        assertTrue(successLogIndex > markPublishedIndex)

        val successBranch = runner.substring(markPublishedIndex, successLogIndex + 256)
        assertTrue(successBranch.contains("if (hdrEnhancedPublicationQualified)"))
    }
}
