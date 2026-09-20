package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionOutputCombinationRecoverySourceContractTest {
    private fun appRoot(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun manager(): String =
        File(appRoot(), "src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()

    private fun catalog(): String =
        File(appRoot(), "src/main/java/com/bncam/core/engine/CameraStreamCapabilityCatalog.kt").readText()

    @Test
    fun `runtime configure failure diagnoses preview capture and pair independently`() {
        val source = catalog()
        assertTrue(source.contains("diagnoseRegularPhotoCombination("))
        assertTrue(source.contains("val previewSupported = supported(listOf(previewOutput()))"))
        assertTrue(source.contains("val captureSupported = supported(listOf(captureOutput()))"))
        assertTrue(source.contains("val combinedSupported = supported(listOf(previewOutput(), captureOutput()))"))
    }

    @Test
    fun `preview-only rejection never triggers pointless capture geometry shrinking`() {
        val source = manager()
        assertTrue(source.contains("SessionOutputRecoveryAction.FAIL_WITHOUT_GEOMETRY_RETRY"))
        assertTrue(source.contains("STREAM_GEOMETRY_RETRY_SKIPPED"))
        assertTrue(source.contains("CAMERA_HARD_START_GEOMETRY_FALLBACK_SKIPPED"))
    }

    @Test
    fun `hard start first attempts in-place output recovery before CameraDevice reopen`() {
        val source = manager()
        val inPlace = source.indexOf("val recoveredInPlace = performSoftResetPipeline(")
        val inPlaceReason = source.indexOf("HARD_START_IN_PLACE_OUTPUT_RECOVERY_", inPlace)
        val hardClose = source.indexOf("closeCameraOwned(\"HARD_START_STREAM_FALLBACK_", inPlaceReason)
        assertTrue(inPlace >= 0)
        assertTrue(inPlaceReason > inPlace)
        assertTrue(hardClose > inPlaceReason)
    }

    @Test
    fun `core recovery never removes canonical ring or preview target`() {
        val source = manager()
        val diagnoseStart = source.indexOf("private fun diagnoseSessionConfigureFailure(")
        val diagnoseEnd = source.indexOf("private fun recordSessionOutputFailure(", diagnoseStart)
        assertTrue(diagnoseStart >= 0 && diagnoseEnd > diagnoseStart)
        val diagnosis = source.substring(diagnoseStart, diagnoseEnd)
        assertFalse(diagnosis.contains("removeTarget"))
        assertFalse(diagnosis.contains("imageReader = null"))
        assertFalse(diagnosis.contains("previewSurface = null"))
    }

    @Test
    fun `custom RAW and vendor session authorities remain separate`() {
        val source = manager()
        assertTrue(source.contains("SessionOutputFailureKind.OPTIONAL_CUSTOM_RAW_OUTPUT"))
        assertTrue(source.contains("SessionOutputFailureKind.NON_REGULAR_OPERATION_MODE"))
        assertTrue(source.contains("CUSTOM_RAW_PREVIEW_RETRY_CANONICAL"))
        assertTrue(source.contains("generic stream geometry must not compete with that authority"))
        assertFalse(source.contains("VENDOR_OPERATION_MODE_PROBE_CONFIG_FAILED_NEXT"))
    }
}
