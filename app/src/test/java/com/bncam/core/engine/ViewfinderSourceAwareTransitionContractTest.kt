package com.bncam.core.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ViewfinderSourceAwareTransitionContractTest {
    private val appDir = File(System.getProperty("user.dir"))

    @Test
    fun `producer readiness is source aware`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        assertTrue(manager.contains("pending.generation != generation || pending.source != source"))
        assertTrue(manager.contains("markViewfinderProducerFrameReady(\n                ViewfinderEffectiveSource.YUV"))
        assertTrue(manager.contains("ViewfinderEffectiveSource.fromImageFormat(requestedIdentity.bufferFormat)"))
    }

    @Test
    fun `raw completion cannot publish after target moved to yuv`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        assertTrue(manager.contains("frame.source != targetViewfinderSource"))
        assertTrue(manager.contains("return@rawPreviewFrame"))
    }

    @Test
    fun `config readiness immediately reoffers resident raw frame`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        assertTrue(manager.contains("offerLatestWarmRawFrameAfterConfig(source, generation, routeRevision)"))
        assertTrue(manager.contains("ringBuffer.latestImageTimestamp(generation) ?: return"))
        assertTrue(manager.contains("rawPreviewConfiguredGeneration == generation"))
    }

    private fun source(relative: String): String = File(appDir, relative).readText()
}
