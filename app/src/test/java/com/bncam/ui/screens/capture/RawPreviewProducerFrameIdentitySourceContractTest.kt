package com.bncam.ui.screens.capture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPreviewProducerFrameIdentitySourceContractTest {
    private val appDir = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .firstOrNull { File(it, "src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt").isFile }
        ?: File(".")

    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun exactFrameIdentityIncludesGenerationTimestampAndProducer() {
        val identity = source("src/main/java/com/bncam/core/runtime/RawPreviewProducerFrameIdentity.kt")
        assertTrue(identity.contains("val generation: Int"))
        assertTrue(identity.contains("val sensorTimestampNs: Long"))
        assertTrue(identity.contains("val producerKind: RawPreviewProducerKind"))
    }

    @Test
    fun lifecycleLedgerCannotAliasCanonicalAndSupportAtSameTimestamp() {
        val ledger = source("src/main/java/com/bncam/ui/screens/capture/RawPreviewFrameLifecycleLedger.kt")
        assertTrue(ledger.contains("val producerKind: RawPreviewProducerKind"))
        assertTrue(ledger.contains("Key(generation, timestampNs, producerKind)"))
        assertFalse(ledger.contains("Key(generation, timestampNs)]"))
    }

    @Test
    fun rendererAndGlPropagateProducerThroughPublicationAndPresentation() {
        val renderer = source("src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt")
        val view = source("src/main/java/com/bncam/ui/screens/capture/FocusPeakingView.kt")
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")

        assertTrue(renderer.contains("producerKind = frame.producerKind"))
        assertTrue(view.contains("producerKind = pending.producerKind"))
        assertTrue(view.contains("producerKind = frame.producerKind"))
        assertTrue(manager.contains("producer=${'$'}{producerKind?.name ?: \"unknown\"}"))
    }

    @Test
    fun healthCarriesExactProducerFrameEvidenceButDoesNotGuessImageReaderOwner() {
        val health = source("src/main/java/com/bncam/ui/screens/capture/RawPreviewHealthMonitor.kt")
        assertTrue(health.contains("lastDisplayPresentedFrame: RawPreviewProducerFrameIdentity?"))
        assertTrue(health.contains("stageFrameHint: RawPreviewProducerFrameIdentity?"))
        assertTrue(health.contains("RawPreviewHealthStage.RAW_IMAGE_READER"))
        assertTrue(health.contains("else -> null"))
    }
}
