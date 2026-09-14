package com.bncam.ui.screens.capture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPreviewPhase3SourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/CMakeLists.txt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun camera2RepeatingCallbackNamesBufferLossTargetsAndPersistsFailureTruth() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        assertTrue(manager.contains("override fun onCaptureBufferLost("))
        assertTrue(manager.contains("CANONICAL_RAW_RING"))
        assertTrue(manager.contains("CUSTOM_RAW_PREVIEW"))
        assertTrue(manager.contains("event=CAPTURE_BUFFER_LOST"))
        assertTrue(manager.contains("CAMERA2 BUFFER LOST"))
        assertTrue(manager.contains("wasImageCaptured=${'$'}{failure.wasImageCaptured}"))
    }

    @Test
    fun customRawReaderIsNotPhysicallyClosedWhileActiveSessionMayOwnItsSurface() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val closeHelper = manager.substringAfter("private fun closeCustomRawPreviewReader")
            .substringBefore("private fun retireReaderAfterOwningSession")
        assertFalse(closeHelper.contains("reader.close()"))
        assertTrue(closeHelper.contains("retireReaderAfterOwningSession"))
        assertTrue(manager.contains("deferredReaderRetirements"))
        assertTrue(manager.contains("releaseDeferredReadersForSession(session)"))
        assertTrue(manager.contains("session_onClosed"))
    }

    @Test
    fun customRawPostStartStallFallsBackOnlyWhenCanonicalRawIsStillAlive() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        assertTrue(manager.contains("checkCustomRawPreviewLiveness"))
        assertTrue(manager.contains("maxOf(1_000_000_000L, expectedIntervalNs * 6L)"))
        assertTrue(manager.contains("canonicalAgeNs > stallThresholdNs"))
        assertTrue(manager.contains("disableCustomRawPreviewForGeneration"))
        assertTrue(manager.contains("customRawPreviewDisabledGeneration != generation"))
        assertTrue(manager.contains("lastCanonicalRawFrameElapsedNs"))
    }
}
