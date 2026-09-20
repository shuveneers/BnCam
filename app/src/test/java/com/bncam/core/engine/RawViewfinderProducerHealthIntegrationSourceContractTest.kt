package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawViewfinderProducerHealthIntegrationSourceContractTest {
    private fun appRoot(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(path: String): String = File(appRoot(), path).readText()

    @Test
    fun `primary first frame gate is tied to exact repeating role`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")

        assertTrue(manager.contains("activeSessionRepeatingRoleTargeted(generation, BnCamStreamRoleIds.PRIMARY_BUFFER)"))
        assertTrue(manager.contains("FIRST_PRIMARY_PRODUCER_ROLE_NOT_REPEATING"))
        assertTrue(manager.contains("FIRST_PRIMARY_PRODUCER_FRAME_READY"))
        assertTrue(manager.contains("FIRST_PRIMARY_PRODUCER_FRAME_TIMEOUT"))
        assertTrue(manager.contains("val viewfinderRepeating = activeSessionRepeatingRoleTargeted("))
        assertFalse(manager.contains("private suspend fun awaitFirstProducerFrameAfter("))
    }

    @Test
    fun `optional raw support health uses request target truth`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")

        assertTrue(manager.contains("RawViewfinderProducerHealthPolicy.resolve("))
        assertTrue(manager.contains("BnCamStreamRoleIds.RAW_PREVIEW_SUPPORT"))
        assertTrue(manager.contains("rawPreviewSupportRepeating = activeSessionRepeatingRoleTargeted("))
        assertTrue(manager.contains("rawProducerHealth.disableRawPreviewSupport"))
        assertTrue(manager.contains("disableCustomRawPreviewForGeneration("))
    }

    @Test
    fun `aggregate raw image reader stage no longer guesses which producer failed`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")

        assertTrue(manager.contains("stageFrameProducerKind = rawHealth.stageFrameHint?.producerKind"))
        assertTrue(manager.contains("exactDownstreamChainCoherent = rawHealth.exactDownstreamChainCoherent"))
        assertFalse(manager.contains("\"health_raw_image_reader_stall\""))
    }

    @Test
    fun `producer policy cannot let custom support mark canonical capture transport healthy`() {
        val policy = source("src/main/java/com/bncam/core/engine/RawViewfinderProducerHealthPolicy.kt")

        assertTrue(policy.contains("canonicalFreshCompleteFrames"))
        assertTrue(policy.contains("rawPreviewSupportLastFrameElapsedNs"))
        assertTrue(policy.contains("RAW_PREVIEW_SUPPORT_NO_FIRST_FRAME"))
        assertTrue(policy.contains("RAW_PREVIEW_SUPPORT_INPUT_STALLED"))
        assertTrue(policy.contains("custom support can never") || policy.contains("may never mask"))
    }
}
