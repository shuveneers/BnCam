package com.bncam.core.performance

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-level guardrails for the Phase 0 duplicate-work fix. */
class Phase0ProcessingContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/runners/MultiFrameRunner.kt").isFile }
        ?: error("Cannot locate app module")

    @Test
    fun rawMultiFrameRendersJpegExactlyOnce() {
        val source = File(
            appDir,
            "src/main/java/com/bncam/core/runners/MultiFrameRunner.kt"
        ).readText()
        val renderCalls = Regex("""ImageUtils\.renderJpegFromMasterFrameSafe\(""")
            .findAll(source)
            .count()

        assertEquals(1, renderCalls)
        assertTrue(source.contains("val finalJpegBytesForPublication = finalJpegBytes"))
        assertTrue(source.contains("val jpegBytesToUse = finalJpegBytesForPublication"))

        val publicationSection = source.substringAfter(
            "CaptureProcessingQueue.submit(context, reservation)"
        )
        assertFalse(publicationSection.contains("ImageUtils.renderJpegFromMasterFrameSafe("))
    }

    @Test
    fun phase0InvocationCountersAndPersistentReportAreEnforced() {
        val multi = File(
            appDir,
            "src/main/java/com/bncam/core/runners/MultiFrameRunner.kt"
        ).readText()
        val single = File(
            appDir,
            "src/main/java/com/bncam/core/runners/SingleFrameRunner.kt"
        ).readText()
        val tracker = File(
            appDir,
            "src/main/java/com/bncam/core/debug/CapturePerformanceTracker.kt"
        ).readText()

        assertTrue(multi.contains("rawIspInvocationCount"))
        assertTrue(multi.contains("jpegEncodeInvocationCount"))
        assertTrue(multi.contains("rawMergeInvocationCount"))
        assertTrue(single.contains("rawIspInvocationCount"))
        assertTrue(single.contains("rawMergeInvocationCount"))
        assertTrue(tracker.contains("capture_performance.jsonl"))
        assertTrue(tracker.contains("SystemClock.elapsedRealtimeNanos"))
        assertTrue(tracker.contains("terminalReportPersisted"))
    }

    @Test
    fun fullRawIntegrityCrcIsNotRunForEveryDebugCapture() {
        val imageUtils = File(
            appDir,
            "src/main/java/com/bncam/core/engine/ImageUtils.kt"
        ).readText()
        assertTrue(imageUtils.contains("BuildConfig.DEBUG && rawJpegDebugDumpsEnabled"))
        assertFalse(imageUtils.contains("if (BuildConfig.DEBUG) RawMasterIntegrity.fingerprint"))
    }

    @Test
    fun misleadingConcurrentNativeClaimWasRemoved() {
        val source = File(
            appDir,
            "src/main/java/com/bncam/core/runners/MultiFrameRunner.kt"
        ).readText()
        assertFalse(source.contains("Start Concurrent C++ processing"))
        assertTrue(source.contains("Start synchronous native processing"))
    }
    @Test
    fun successReportIsWrittenOnlyAfterQueuePublicationTransition() {
        val multi = File(
            appDir,
            "src/main/java/com/bncam/core/runners/MultiFrameRunner.kt"
        ).readText()
        val single = File(
            appDir,
            "src/main/java/com/bncam/core/runners/SingleFrameRunner.kt"
        ).readText()

        val multiTransition = multi.indexOf("workReservation.markPublished(stringOutputs)")
        val multiReport = multi.indexOf(
            "performanceTracker.persistJsonLine(context, status = \"PUBLISHED\")",
            multiTransition
        )
        assertTrue(multiTransition >= 0)
        assertTrue(multiReport > multiTransition)

        val singleTransition = single.indexOf("processingWork?.markPublished(stringOutputs)")
        val singleReport = single.indexOf(
            "performanceTracker.persistJsonLine(context, status = \"PUBLISHED\")",
            singleTransition
        )
        assertTrue(singleTransition >= 0)
        assertTrue(singleReport > singleTransition)
    }

}
