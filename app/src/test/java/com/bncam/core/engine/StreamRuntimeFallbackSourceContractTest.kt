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

        assertTrue(manager.contains("event=FIRST_PRIMARY_PRODUCER_FRAME_READY"))
        assertTrue(manager.contains("event=FIRST_PRIMARY_PRODUCER_FRAME_TIMEOUT"))
        assertTrue(manager.contains("awaitFirstPrimaryProducerFrameAfter("))
        assertTrue(manager.contains("imageAdvanced && metadataAdvanced"))
        assertTrue(manager.contains("pipelineTransitionState != PipelineTransitionState.PREVIEW_ATTACHED"))
    }

    @Test
    fun `runtime fallback is bounded and does not rewrite saved stream settings`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val resolver = source("src/main/java/com/bncam/core/engine/StreamConfigResolver.kt")
        val policy = source("src/main/java/com/bncam/core/engine/StreamRuntimeFallbackPolicy.kt")

        assertTrue(manager.contains("STREAM_RUNTIME_FALLBACK_ADVANCED"))
        assertTrue(manager.contains("STREAM_RUNTIME_FALLBACK_EXHAUSTED"))
        assertTrue(manager.contains("streamRuntimeFallbackByLens"))
        assertTrue(resolver.contains("autoResolution.conservativeSize"))
        assertTrue(policy.contains("StreamRuntimeFallbackTier.CONSERVATIVE_FULL_FOV -> null"))
        assertFalse(manager.contains("setSpecificRawSizeIndex("))
        assertFalse(manager.contains("setResolutionFixReferenceFormatCode("))
    }

    @Test
    fun `failed Camera2 sessions are closed before fallback reuses outputs`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")

        assertTrue(manager.contains("SESSION_CONFIGURE_FAILED"))
        assertTrue(manager.contains("INITIAL_REPEATING_REQUEST_FAILED"))
        assertTrue(manager.contains("awaitCaptureSessionClosed(failedSessionTicket)"))
        assertTrue(manager.contains("FIRST_PRIMARY_PRODUCER_FRAME_TIMEOUT:${'$'}reason"))
    }

    @Test
    fun `custom raw support output is suppressed by runtime fallback without deleting preference`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")

        assertTrue(
            manager.contains(
                "customBindingAllowed = photoStreamSelection.runtimeFallbackTier == StreamRuntimeFallbackTier.NONE"
            )
        )
        assertTrue(manager.contains("raw10BindingSetting"))
        assertTrue(manager.contains("rawSensorBindingSetting"))
    }
}
