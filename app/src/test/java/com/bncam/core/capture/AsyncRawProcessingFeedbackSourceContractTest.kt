package com.bncam.core.capture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AsyncRawProcessingFeedbackSourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module")

    @Test fun `raw multi and hdr feedback is produced inside async processing ownership`() {
        val multi = File(appDir, "src/main/java/com/bncam/core/runners/MultiFrameRunner.kt").readText()
        val hdr = File(appDir, "src/main/java/com/bncam/core/runners/HdrEnhancedRunner.kt").readText()

        assertTrue(multi.contains("data class MultiRawProcessingFeedback"))
        assertTrue(multi.contains("onRawProcessingFeedback: (MultiRawProcessingFeedback) -> Unit = {}"))
        val workerIndex = multi.indexOf("CaptureProcessingQueue.submit(context, reservation)")
        val callbackIndex = multi.indexOf("onRawProcessingFeedback(", workerIndex)
        assertTrue(workerIndex >= 0)
        assertTrue(callbackIndex > workerIndex)
        assertTrue(multi.contains("nativeStats = masterIspStats"))
        assertTrue(hdr.contains("onRawProcessingFeedback = onRawProcessingFeedback"))
    }

    @Test fun `manager never reads stale global raw isp stats for async raw strategies`() {
        val manager = File(appDir, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()

        assertTrue(manager.contains("source = \"ASYNC_MULTI_RAW\""))
        assertTrue(manager.contains("source = \"ASYNC_HDR_ENHANCED_RAW\""))
        val deferred = manager.substringAfter("val rawProcessingFeedbackDeferred =")
            .substringBefore("if (!rawProcessingFeedbackDeferred)")
        assertTrue(deferred.contains("CaptureStrategy.SINGLE_FRAME_ZSL"))
        assertTrue(deferred.contains("CaptureStrategy.MULTI_FRAME_ZSL"))
        assertTrue(deferred.contains("CaptureStrategy.HDR_ENHANCED"))
        assertTrue(deferred.contains("FrameOrigin.RAW10"))
        assertTrue(deferred.contains("FrameOrigin.RAW_SENSOR"))
    }
}
