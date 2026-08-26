package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpticalStabilizationSessionContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    @Test
    fun hiddenPhysicalCanUseOpenedLogicalStandardOisWithoutPhysicalOverrideKey() {
        val policy = source("src/main/java/com/bncam/core/engine/OisRoutePolicy.kt")

        assertTrue(policy.contains("if (openedCameraSupportsStandardOis)"))
        assertTrue(policy.contains("return Route.HIDDEN_STANDARD"))
    }

    @Test
    fun sessionArmsMechanicalOisAndForcesDigitalStabilizationOffWhenAdvertised() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val sessionBlock = manager.substringAfter("private fun applyOpticalStabilizationSessionParameters(")
            .substringBefore("private fun applyViewfinderCam2ApiSettings(")

        assertTrue(sessionBlock.contains("characteristics.availableSessionKeys"))
        assertTrue(sessionBlock.contains("LENS_OPTICAL_STABILIZATION_MODE_ON"))
        assertTrue(sessionBlock.contains("CONTROL_VIDEO_STABILIZATION_MODE_OFF"))
        assertFalse(sessionBlock.contains("CONTROL_VIDEO_STABILIZATION_MODE_ON"))
        assertFalse(sessionBlock.contains("CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION"))
    }

    @Test
    fun standardOrVendorSessionParametersAreAttachedBeforeSessionCreation() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        assertTrue(manager.contains("standardStabilizationSessionApplied || sessionAttempts.any"))
        assertTrue(manager.contains("sessionConfig.setSessionParameters(params)"))
        assertTrue(manager.contains("OIS_SESSION_ARM"))
    }

    @Test
    fun previewDiagnosticsDoNotCallBuilderOnlyPhysicalGetterOnBuiltRequest() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")

        assertFalse(manager.contains("request.getPhysicalCameraKey("))
        assertTrue(manager.contains("decision.appliedMethod == OisDecision.OisMethod.PHYSICAL_OIS"))
        assertTrue(manager.contains("?.appliedValue"))
    }

    private fun source(relative: String): String = File(appDir, relative).readText()
}
