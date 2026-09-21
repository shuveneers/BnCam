package com.bncam.core.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ColdRawSingleCaptureContractTest {
    private val manager = File("src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()
    private val cold = manager.substringAfter("private suspend fun acquireColdRawSingle(")
        .substringBefore("private suspend fun executeDedicatedFlashCapture(")

    @Test fun coldSingleUsesExistingRawProducerWithoutRebuildOrGpuWork() {
        assertTrue(cold.contains("TEMPLATE_STILL_CAPTURE"))
        assertTrue(cold.contains("builder.addTarget(reader.surface)"))
        assertTrue(cold.contains("ImageFormat.RAW10 || format == ImageFormat.RAW_SENSOR"))
        for (forbidden in listOf("createCaptureSession", "ImageReader.newInstance", "waitForPipelineReady",
            "updatePreviewRepeatingRequest", "prewarm", "rawPreviewRenderer", "delay(", "sleep(")) {
            assertFalse(forbidden, cold.contains(forbidden))
        }
    }

    @Test fun rawSingleBypassesWarmReadinessButKeepsProducerValidation() {
        val gate = manager.substringAfter("val pipelineReady = if (rawSingle)")
            .substringBefore("else if (pinnedAnchorCanOwnAdmission)")
        assertTrue(gate.contains("requireWarmBuffer = false"))
        assertFalse(gate.contains("waitForPipelineReadyForCapture"))
        assertTrue(manager.contains("if (!rawSingle && effectiveCaptureStrategy == CaptureStrategy.SINGLE_FRAME_ZSL"))
        val readiness = manager.substringAfter("private fun pipelineReadinessReason(")
            .substringBefore("private suspend fun waitForPipelineReadyForCapture(")
        assertTrue(readiness.indexOf("sessionConfiguredGeneration != pipelineGeneration") <
            readiness.indexOf("if (!requireWarmBuffer) return"))
    }

    @Test fun warmAndColdSourcesRemainExplicitAndOnlySingleGetsFallback() {
        assertTrue(manager.contains("val rawSingle = effectiveCaptureStrategy == CaptureStrategy.SINGLE_FRAME_ZSL"))
        assertTrue(manager.contains("RAW_SINGLE_SOURCE=NEAR_ZSL_PRE_SHUTTER"))
        assertTrue(manager.contains("RAW_SINGLE_SOURCE=COLD_DIRECT_STILL"))
        assertTrue(manager.contains("if (preleasedSingleAnchor == null) {\n                    acquireColdRawSingle") ||
            manager.replace("\r\n", "\n").contains("if (preleasedSingleAnchor == null) {\n                    acquireColdRawSingle"))
        assertTrue(manager.contains("reservedAnchor = coldSingleAnchor ?: preleasedSingleAnchor?.lease?.takeIf { rawSingle }"))
    }

    @Test fun cancellationShutdownAndGenerationRetireOwnership() {
        assertTrue(cold.contains("!acquisitionJob.isActive || !resultReady.isActive"))
        assertTrue(cold.contains("pipelineGeneration != expectedGeneration"))
        assertTrue(cold.contains("FrameRingEventType.BUFFER_CLEARED"))
        assertTrue(cold.contains("lifecycle.cancel()"))
        assertTrue(cold.contains("resultReady.cancel()"))
        assertTrue(manager.contains("coldRawCaptureJob.get()?.cancel"))
        assertTrue(manager.contains("coldSingleAnchor?.release()"))
        assertTrue(cold.contains("onAcquired(lease)"))
    }

    @Test fun requestedFrameIsExactAndCannotBeReplacedByRepeatingCandidate() {
        assertTrue(cold.contains("awaitAndLeaseExactRequestFrame"))
        assertTrue(cold.contains("submission.prepared.tag.controlRequestEpoch"))
        assertTrue(cold.contains("if (!provenance.exact || timestamp == null || timestamp <= 0L)"))
        val collector = File("src/main/java/com/bncam/core/capture/ShutterCandidateCollector.kt").readText()
        assertTrue(collector.contains("frames = listOf(pair)"))
        assertTrue(collector.contains("pair.image!!.timestamp == pair.timestamp"))
        val runner = File("src/main/java/com/bncam/core/runners/SingleFrameRunner.kt").readText()
        assertTrue(runner.contains("reservedAnchor = reservedAnchor"))
        assertTrue(runner.contains("frame.timestamp == shutterTimestampNs"))
        assertTrue(runner.contains("frame.controlRequestEpoch == currentSubmittedControlRequestEpochAtShutter"))
    }
}
