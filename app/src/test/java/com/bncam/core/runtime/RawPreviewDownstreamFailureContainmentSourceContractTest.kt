package com.bncam.core.runtime

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPreviewDownstreamFailureContainmentSourceContractTest {
    private val appDir = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: File(".")

    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun managerUsesProducerAwareDownstreamPolicy() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        assertTrue(manager.contains("RawPreviewDownstreamFailurePolicy.resolve"))
        assertTrue(manager.contains("stageFrameProducerKind = rawHealth.stageFrameHint?.producerKind"))
        assertTrue(manager.contains("exactDownstreamChainCoherent = rawHealth.exactDownstreamChainCoherent"))
    }

    @Test
    fun sharedDisplayRecoveryRevokesAuthorityWithoutSessionRebuild() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        assertTrue(manager.contains("CUSTOM_RAW_PREVIEW_DISPLAY_AUTHORITY_REVOKED"))
        assertTrue(manager.contains("rawPreviewProducerAuthorityTracker.revokeCustomPresentation("))
        assertTrue(manager.contains("revokedThroughSensorTimestampNs=${'$'}throughSensorTimestampNs"))
        assertTrue(manager.contains("requestTargetStillConfigured=true"))
        assertTrue(manager.contains("cameraSessionUntouched=true"))
    }

    @Test
    fun policyNeverIsolatesSupportForGlOrEglStages() {
        val policy = source("src/main/java/com/bncam/core/runtime/RawPreviewDownstreamFailurePolicy.kt")
        val glBlock = policy.substringAfter("RawPreviewDownstreamFailureStage.GL_ACCEPT,")
            .substringBefore("RawPreviewDownstreamFailureStage.EGL_PRESENTATION")
        val eglBlock = policy.substringAfter("RawPreviewDownstreamFailureStage.EGL_PRESENTATION ->")
            .substringBefore("RawPreviewDownstreamFailureStage.RGB_OUTPUT")
        assertTrue(glBlock.contains("isolateRawPreviewSupportRequestTarget = false"))
        assertTrue(eglBlock.contains("isolateRawPreviewSupportRequestTarget = false"))
        assertFalse(glBlock.contains("isolateRawPreviewSupportRequestTarget = true"))
        assertFalse(eglBlock.contains("isolateRawPreviewSupportRequestTarget = true"))
    }
}
