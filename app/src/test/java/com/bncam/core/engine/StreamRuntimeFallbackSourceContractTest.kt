package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamRuntimeFallbackSourceContractTest {
    private fun appRoot(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(path: String): String = File(appRoot(), path).readText()

    @Test
    fun `session configured is not treated as first frame readiness`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")

        assertTrue(manager.contains("event=FIRST_PRODUCER_FRAME_READY"))
        assertTrue(manager.contains("event=FIRST_PRODUCER_FRAME_TIMEOUT"))
        assertTrue(manager.contains("awaitFirstProducerFrameAfter("))
        assertTrue(manager.contains("imageAdvanced && metadataAdvanced"))
        assertTrue(manager.contains("pipelineTransitionState != PipelineTransitionState.PREVIEW_ATTACHED"))
    }

    @Test
    fun `runtime fallback is bounded and does not rewrite user preferences`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val resolver = source("src/main/java/com/bncam/core/engine/StreamConfigResolver.kt")
        val policy = source("src/main/java/com/bncam/core/engine/StreamRuntimeFallbackPolicy.kt")

        assertTrue(manager.contains("STREAM_RUNTIME_FALLBACK_ADVANCED"))
        assertTrue(manager.contains("STREAM_RUNTIME_FALLBACK_EXHAUSTED"))
        assertTrue(manager.contains("streamRuntimeFallbackByLens"))
        assertTrue(manager.contains("activeVendorOperationProbe != null"))
        assertTrue(resolver.contains("CONSERVATIVE_FULL_FOV"))
        assertTrue(resolver.contains("selectConservativeFullFovSize"))
        assertTrue(policy.contains("StreamRuntimeFallbackTier.CONSERVATIVE_FULL_FOV -> null"))

        // Runtime quarantine must never silently persist a different Auto/Validated/Manual choice.
        assertFalse(manager.contains("setPhotoStreamConfiguration"))
        assertFalse(manager.contains("setStreamConfigurationMode"))
    }

    @Test
    fun `failed Camera2 sessions are closed before fallback reuses outputs`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")

        assertTrue(manager.contains("SESSION_CONFIGURE_FAILED"))
        assertTrue(manager.contains("INITIAL_REPEATING_REQUEST_FAILED"))
        assertTrue(manager.contains("awaitCaptureSessionClosed(failedSessionTicket)"))
        assertTrue(manager.contains("FIRST_PRODUCER_FRAME_TIMEOUT:\$reason"))
    }

    @Test
    fun `manual custom raw output is removed by runtime fallback`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")

        assertTrue(
            manager.contains(
                "resolvedStreamPlan.runtimeFallbackTier == StreamRuntimeFallbackTier.NONE"
            )
        )
    }
}
