package com.bncam.core.debug

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedRuntimeDiagnosticsTest {
    private fun snapshot() = UnifiedRuntimeDiagnosticsSnapshot(
        generation = 42,
        captureRoute = "SingleRaw10JpegPath",
        ae = RuntimeDiagnosticDomain.of("CONVERGED", "errorEv" to 0.02f, "owner" to "BNCAM_RAW"),
        rawPreview = RuntimeDiagnosticDomain.of("HEALTHY", "fastPath" to "DIRECT_AHB_GPU_RESIDENT"),
        calibration = RuntimeDiagnosticDomain.of("READY", "mandatoryRawReady" to true),
        noise = RuntimeDiagnosticDomain.of("PHYSICAL_SO", "modelConfidence" to 1.0f),
        capability = RuntimeDiagnosticDomain.of("SUPPORTED", "raw10" to true)
    )

    @Test
    fun `compact snapshot has deterministic subsystem order`() {
        val text = snapshot().compactText()
        val ae = text.indexOf("|AE{")
        val preview = text.indexOf("|RAW_PREVIEW{")
        val calibration = text.indexOf("|CALIBRATION{")
        val noise = text.indexOf("|NOISE{")
        val capability = text.indexOf("|CAPABILITY{")
        assertTrue(ae > 0)
        assertTrue(ae < preview && preview < calibration && calibration < noise && noise < capability)
    }

    @Test
    fun `outer exposure policy delimiters cannot leak from domain values`() {
        val text = UnifiedRuntimeDiagnosticsSnapshot(
            generation = 7,
            captureRoute = "RAW10;route\nnext",
            ae = RuntimeDiagnosticDomain.of("OK", "value" to "a;b|c\nd"),
            rawPreview = RuntimeDiagnosticDomain.unavailable("none;yet"),
            calibration = RuntimeDiagnosticDomain.unavailable("none"),
            noise = RuntimeDiagnosticDomain.unavailable("none"),
            capability = RuntimeDiagnosticDomain.unavailable("none")
        ).compactText()
        assertFalse(text.contains(';'))
        assertFalse(text.contains('\n'))
        assertTrue(text.contains("|AE{"))
    }

    @Test
    fun `unavailable state remains explicit instead of guessed`() {
        val text = UnifiedRuntimeDiagnosticsSnapshot(
            generation = 1,
            captureRoute = "route",
            ae = RuntimeDiagnosticDomain.unavailable("no_fresh_state"),
            rawPreview = RuntimeDiagnosticDomain.unavailable("not_raw"),
            calibration = RuntimeDiagnosticDomain.unavailable("metadata_missing"),
            noise = RuntimeDiagnosticDomain.unavailable("metadata_missing"),
            capability = RuntimeDiagnosticDomain.unavailable("facts_missing")
        ).compactText()
        assertTrue(text.contains("status=UNAVAILABLE"))
        assertTrue(text.contains("reason=no_fresh_state"))
        assertTrue(text.contains("reason=metadata_missing"))
    }

    @Test
    fun `oversized values are bounded`() {
        val huge = "x".repeat(2000)
        val cleaned = UnifiedRuntimeDiagnosticsSnapshot.cleanForTest(huge)
        assertTrue(cleaned.length <= 384)
    }
}
