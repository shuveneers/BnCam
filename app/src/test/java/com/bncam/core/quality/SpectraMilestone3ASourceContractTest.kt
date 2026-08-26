package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectraMilestone3ASourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/IspCore.cpp").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    @Test
    fun fineMidLowArchitectureAndObserverRemainAvailableAfterM3B() {
        val app = appDir()
        val cpp = File(app, "src/main/cpp/IspCore.cpp").readText()
        val header = File(app, "src/main/cpp/SpectraChromaBands.h").readText()

        assertTrue(header.contains("ChromaBandKind::Fine"))
        assertTrue(header.contains("ChromaBandKind::Mid"))
        assertTrue(header.contains("ChromaBandKind::Low"))
        assertTrue(header.contains("M3B_FINE_WIENER_DETAIL_RADIUS2"))
        assertTrue(header.contains("M3B_MID_ATROUS_RESIDUAL_RADIUS2_TO6"))
        assertTrue(header.contains("M3B_LOW_CONTINUOUS_FIELD_SOFT_THRESHOLD"))
        assertTrue(header.contains("plan.authorityScale"))
        assertTrue(header.contains("1.0f"))
        assertTrue(cpp.contains("measureSpectraChromaBandEnergies"))
        assertTrue(cpp.contains("constexpr int kSamplingStride = 16"))
        assertTrue(cpp.contains("for (int phaseY = 0; phaseY < 2; ++phaseY)"))
        assertTrue(cpp.contains("for (int phaseX = 0; phaseX < 2; ++phaseX)"))
        assertTrue(cpp.contains("redBlueSampleBalance"))
        assertTrue(cpp.contains("normalizedStructure > 0.28f"))
        assertTrue(cpp.contains("spectraChromaFine"))
        assertTrue(cpp.contains("spectraChromaMid"))
        assertTrue(cpp.contains("spectraChromaLow"))
        assertFalse(cpp.contains("POST_DEMOSAIC_VISIBLE_CHROMA_CORRECTION_M3A"))
    }

    @Test
    fun traceSchemaSevenRetainsPerBandObservabilityAndNestedTiming() {
        val app = appDir()
        val trace = File(
            app,
            "src/main/java/com/bncam/core/quality/NoiseModelTrace.kt"
        ).readText()
        val contract = File(
            app,
            "src/main/java/com/bncam/core/quality/SpectraChromaBands.kt"
        ).readText()

        val schemaVersion = Regex("CURRENT_SCHEMA_VERSION\\s*=\\s*(\\d+)")
            .find(trace)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        assertTrue(schemaVersion >= 7)
        assertTrue(trace.contains("\"multiscaleChroma\""))
        assertTrue(trace.contains("chromaFineProcessingMs"))
        assertTrue(contract.contains("BAND_SPLIT_AND_DEFAULT_AUTHORITY_PENDING_DEVICE_VALIDATION"))
        assertTrue(contract.contains("NESTED_IN_PASS2_PASS3_TOTALS"))
        assertTrue(contract.contains("noRegretMeanAcceptance"))
        assertTrue(contract.contains("maximumCorrectionScale"))
    }

    @Test
    fun lowBandHasCheapPreflightBeforeExpensivePassThreeAnalysis() {
        val app = appDir()
        val cpp = File(app, "src/main/cpp/IspCore.cpp").readText()
        val measure = cpp.indexOf("postPass2ChromaBands")
        val compute = cpp.indexOf("computePass3State(workingRaw")
        assertTrue(measure >= 0)
        assertTrue(compute > measure)
        assertTrue(cpp.contains("m3b_low_band_budget_reached_preflight"))
    }

    @Test
    fun spectraOffDoesNotRunNewBandMeasurements() {
        val app = appDir()
        val cpp = File(app, "src/main/cpp/IspCore.cpp").readText()
        assertTrue(cpp.contains("SPECTRA_OFF_NOT_MEASURED"))
        assertTrue(cpp.contains("if (budgetState.spectraMode != 0)"))
        assertTrue(cpp.contains("pass3State.lowBand.outputStage = \"SPECTRA_OFF_NOT_MEASURED\""))
    }
}
