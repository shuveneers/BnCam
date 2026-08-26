package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpticalStabilizationNoEisFallbackSourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/OisResolver.kt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    @Test
    fun opticalSettingNeverSelectsPreviewOrVideoStabilizationAsFallback() {
        val resolver = source("src/main/java/com/bncam/core/engine/OisResolver.kt")
        val decisionBlock = resolver.substringAfter("// 5. Resolve OIS application plan")
            .substringBefore("// Log OIS_APPLY_PLAN")

        assertFalse(decisionBlock.contains("appliedMethod = OisDecision.OisMethod.PREVIEW_STAB"))
        assertFalse(decisionBlock.contains("appliedMethod = OisDecision.OisMethod.VIDEO_STAB"))
        assertTrue(decisionBlock.contains("digital preview/video stabilization intentionally not substituted"))
    }

    @Test
    fun vendorFallbackAcceptsOnlyExplicitOpticalOisKeys() {
        val policy = source("src/main/java/com/bncam/core/engine/OisVendorKeyPolicy.kt")
        val approvedBlock = policy.substringAfter("private val knownOpticalKeys")
            .substringBefore("fun isOpticalOnlyKey")

        assertTrue(approvedBlock.contains("com.samsung.android.control.ois_mode"))
        assertTrue(approvedBlock.contains("org.codeaurora.qcamera3.optstabilization.enable"))
        assertTrue(approvedBlock.contains("xiaomi.device.opticalStabilization"))
        assertFalse(approvedBlock.contains("preview_stabilization"))
        assertFalse(approvedBlock.contains("com.oplus.stabilization.mode"))
    }

    @Test
    fun cameraRequestFailsClosedIfLegacyDigitalDecisionAppears() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val branch = manager.substringAfter("OisDecision.OisMethod.PREVIEW_STAB,")
            .substringBefore("OisDecision.OisMethod.VENDOR")

        assertTrue(branch.contains("CONTROL_VIDEO_STABILIZATION_MODE_OFF"))
        assertTrue(branch.contains("LENS_OPTICAL_STABILIZATION_MODE_OFF"))
        assertFalse(branch.contains("CONTROL_VIDEO_STABILIZATION_MODE_ON"))
        assertFalse(branch.contains("CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION"))
    }

    private fun source(relative: String): String = File(appDir, relative).readText()
}
