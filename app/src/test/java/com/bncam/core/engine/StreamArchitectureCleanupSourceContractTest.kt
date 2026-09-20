package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamArchitectureCleanupSourceContractTest {
    private fun appRoot(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(path: String): String = File(appRoot(), path).readText()

    @Test
    fun `operation mode runtime has no persisted probing or learned mapping authority`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val repository = source("src/main/java/com/bncam/data/settings/SettingsRepository.kt")
        val policy = source("src/main/java/com/bncam/core/engine/SessionOperationModePolicy.kt")

        listOf(
            "vendor_op_probe",
            "vendor_op_learned",
            "getLearnedVendorSessionType",
            "saveLearnedVendorSessionType",
            "getVendorOperationModeProbeIndex",
            "setVendorOperationModeProbeIndex",
            "isVendorOperationModeProbeActive",
            "setVendorOperationModeProbeActive"
        ).forEach { legacy -> assertFalse(repository.contains(legacy)) }

        assertFalse(manager.contains("learnedVendorSessionType"))
        assertFalse(manager.contains("vendorFeatureSignature"))
        assertFalse(manager.contains("activeVendorFeatureSignature"))
        assertFalse(manager.contains("vendorSessionType"))
        assertTrue(policy.contains("knownDeviceOperationMode"))
        assertFalse(policy.contains("learnedOperationMode"))
    }

    @Test
    fun `vendor feature UI no longer starts operation mode probing or hardcodes 0x8004`() {
        val ui = source("src/main/java/com/bncam/ui/screens/settings/VendorTagsScreen.kt")

        assertFalse(ui.contains("setVendorOperationModeProbeActive"))
        assertFalse(ui.contains("one-time probe"))
        assertFalse(ui.contains("learns the working mapping"))
        assertFalse(ui.contains("intArrayOf(0x8004)"))
        assertFalse(ui.contains("ReprocessableSessionModeTag"))
        assertFalse(ui.contains("sessionoperationmode"))
        assertTrue(ui.contains("explicit operation-mode SESSION tag"))
    }

    @Test
    fun `session failure diagnostics use operation mode terminology`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val recovery = source("src/main/java/com/bncam/core/engine/SessionOutputCombinationRecoveryPolicy.kt")

        assertTrue(manager.contains("sessionOperationMode"))
        assertFalse(manager.contains("vendorSessionType"))
        assertTrue(recovery.contains("NON_REGULAR_OPERATION_MODE"))
        assertTrue(recovery.contains("DEFER_TO_OPERATION_MODE_AUTHORITY"))
        assertFalse(recovery.contains("VENDOR_SESSION_MODE"))
    }

    @Test
    fun `legacy stored candidate is validation only and cannot override resolved primary authority`() {
        val resolver = source("src/main/java/com/bncam/core/engine/StreamConfigResolver.kt")
        val production = appRoot().resolve("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }

        // StreamConfigurationMode/settings are current user-facing configuration inputs.
        // A persisted legacy candidate ID may only validate the already-resolved primary
        // format/extent; it must never become a second stream-selection authority.
        assertTrue(resolver.contains("legacyCandidateId"))
        assertTrue(resolver.contains("StreamCandidateIdCodec.parse(legacyCandidateId)"))
        assertTrue(resolver.contains("does not match the resolved PRIMARY_BUFFER format"))
        assertTrue(resolver.contains("cannot override resolved PRIMARY_BUFFER extent"))
        assertTrue(resolver.contains("The already-resolved primary format/extent remains authoritative."))
        assertTrue(resolver.contains("captureFormat = effectiveFormatCode"))
        assertTrue(resolver.contains("captureSize = Size(primaryExtent.width, primaryExtent.height)"))

        val resolvedFormatIndex = resolver.indexOf("val effectiveFormatCode = requireNotNull(formatResolution.effectiveFormatCode)")
        val legacyParseIndex = resolver.indexOf("StreamCandidateIdCodec.parse(legacyCandidateId)")
        assertTrue(resolvedFormatIndex >= 0)
        assertTrue(legacyParseIndex > resolvedFormatIndex)

        assertFalse(production.contains("CameraStreamCandidateClass"))
    }
}
