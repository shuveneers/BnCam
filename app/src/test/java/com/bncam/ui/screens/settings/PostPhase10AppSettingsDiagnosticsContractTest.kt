package com.bncam.ui.screens.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PostPhase10AppSettingsDiagnosticsContractTest {
    private fun source(path: String): String = File(System.getProperty("user.dir"), path).readText()

    @Test
    fun privacyAdvancedOrderMatchesRuntimeSettingsContract() {
        val source = source("src/main/java/com/bncam/ui/screens/settings/AppSettingsScreen.kt")
        val privacy = source.substringAfter("SettingsCard(title = \"Privacy & Advanced\")")
            .substringBefore("Spacer(modifier = Modifier.height(32.dp))")
        val hdr = privacy.indexOf("title = \"Computational HDR\"")
        val assistance = privacy.indexOf("title = \"Phone assistance sensors\"")
        val location = privacy.indexOf("title = \"Save location data\"")
        val diagnostics = privacy.indexOf("title = \"Enable diagnostics\"")
        val configure = privacy.indexOf("title = \"Configure diagnostics\"")
        assertTrue(hdr >= 0)
        assertTrue(hdr < assistance && assistance < location && location < diagnostics && diagnostics < configure)

        val fileStorage = source.substringAfter("SettingsCard(title = \"File & Storage\")")
            .substringBefore("SettingsCard(title = \"Device & Interaction\")")
        assertFalse(fileStorage.contains("Computational HDR"))
    }

    @Test
    fun diagnosticsDialogControlsExactlySevenPhysicalShotFiles() {
        val ui = source("src/main/java/com/bncam/ui/screens/settings/AppSettingsScreen.kt")
        listOf(
            "01 · Summary",
            "02 · Capture",
            "03 · Profile settings",
            "04 · ISP",
            "05 · Warnings & errors",
            "06 · Frame analysis",
            "07 · Vendor tag injection"
        ).forEach { assertTrue(ui.contains(it)) }

        val logger = source("src/main/java/com/bncam/core/debug/ShotLogger.kt")
        listOf(
            "SUMMARY_FILE" to "lastLogSummary",
            "CAPTURE_FILE" to "lastLogActiveMode",
            "PROFILE_FILE" to "lastLogProfileSettings",
            "ISP_FILE" to "lastLogPipelineDebug",
            "WARNINGS_FILE" to "lastLogWarnings",
            "FRAME_FILE" to "lastLogFrameAnalysis",
            "VENDOR_FILE" to "lastLogVendorInjection"
        ).forEach { (fileConstant, flag) ->
            assertTrue(logger.contains("$fileConstant -> $flag"))
        }
        listOf(
            "01_SUMMARY.txt", "02_CAPTURE.txt", "03_PROFILE_SETTINGS.txt", "04_ISP.txt",
            "05_WARNINGS_ERRORS.txt", "06_FRAME_ANALYSIS.txt", "07_VENDOR_TAG_INJECTION.txt"
        ).forEach { assertTrue(logger.contains(it)) }
        assertTrue(logger.contains("if (!isPublicDiagnosticFileEnabled(fileName)) return"))
    }
}
