package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectraMilestone4SourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/IspCore.cpp").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    @Test
    fun postToneVisibleChromaUsesPropagatedOpponentCovariance() {
        val app = appDir()
        val cpp = File(app, "src/main/cpp/IspCore.cpp").readText()
        val model = File(app, "src/main/cpp/SpectraVisibleChroma.h").readText()

        assertTrue(model.contains("OpponentCovariance2"))
        assertTrue(model.contains("opponentMahalanobisSquared"))
        assertTrue(model.contains("predictedVarianceRG"))
        assertTrue(model.contains("predictedVarianceBG"))
        assertTrue(model.contains("predictedCovarianceRgBg"))
        assertTrue(cpp.contains("applySpectraVisibleChromaPass"))
        val pixelBackend = File(app, "src/main/cpp/SpectraPixelBackend.h").readText()
        assertTrue(cpp.contains("centerRG"))
        assertTrue(cpp.contains("centerBG"))
        assertTrue(pixelBackend.contains("rg = r - g"))
        assertTrue(pixelBackend.contains("bg = b - g"))
        assertTrue(cpp.contains("POST_VISIBLE_CHROMA_PRE_QUANTIZATION"))
        val callIndex = cpp.lastIndexOf("applySpectraVisibleChromaPass(")
        val quantizePassIndex = cpp.indexOf("const auto quantizeStart", callIndex)
        assertTrue(callIndex > 0 && quantizePassIndex > callIndex)
    }

    @Test
    fun noRegretTwoProtectsLumaColourEdgesSaturationAndDrift() {
        val app = appDir()
        val model = File(app, "src/main/cpp/SpectraVisibleChroma.h").readText()
        val cpp = File(app, "src/main/cpp/IspCore.cpp").readText()

        assertTrue(model.contains("ROBUST_NOISE_NORMALIZED_CONTEXT_HARD_EDGE_GUARD"))
        assertTrue(model.contains("structureWeight"))
        assertTrue(model.contains("colourEdgeWeight"))
        assertTrue(model.contains("colourDriftWeight"))
        assertTrue(model.contains("oversmoothingWeight"))
        assertTrue(model.contains("maximumCorrection"))
        assertTrue(cpp.contains("fullyAcceptedTileCount"))
        assertTrue(cpp.contains("partiallyAcceptedTileCount"))
        assertTrue(cpp.contains("rejectedTileCount"))
        assertTrue(cpp.contains("edgePreservationScore"))
        assertTrue(cpp.contains("oversmoothingScore"))
    }

    @Test
    fun schemaEightOrLaterExportsPrePostResidualsAndNestedTiming() {
        val app = appDir()
        val trace = File(app, "src/main/java/com/bncam/core/quality/NoiseModelTrace.kt").readText()
        val contract = File(app, "src/main/java/com/bncam/core/quality/SpectraVisibleChroma.kt").readText()
        val residual = File(app, "src/main/java/com/bncam/core/quality/ResidualNoiseState.kt").readText()

        val schemaVersion = Regex("CURRENT_SCHEMA_VERSION\\s*=\\s*(\\d+)")
            .find(trace)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        assertTrue(schemaVersion >= 8)
        assertTrue(trace.contains("\"visibleChroma\" to visibleChroma.toTraceMap()"))
        assertTrue(trace.contains("spectraVisibleChromaTimingMode"))
        assertTrue(contract.contains("inputVarianceRG"))
        assertTrue(contract.contains("outputVarianceRG"))
        assertTrue(contract.contains("NESTED_IN_FINAL_OUTPUT_PASS_MS"))
        assertTrue(residual.contains("postVisibleChroma"))
        assertTrue(residual.contains("\"visibleChroma\" to transitionTraceMap"))
    }

    @Test
    fun spectraOffAndDngJniPublicationSurfacesRemainIsolated() {
        val app = appDir()
        val cpp = File(app, "src/main/cpp/IspCore.cpp").readText()
        val nativeLib = File(app, "src/main/cpp/native-lib.cpp").readText()
        val dngMerger = File(app, "src/main/cpp/DngMerger.cpp").readText()

        assertTrue(cpp.contains("plan.status = \"SPECTRA_OFF\"") ||
            File(app, "src/main/cpp/SpectraVisibleChroma.h").readText().contains(
                "plan.status = \"SPECTRA_OFF\""
            ))
        assertTrue(cpp.contains("profileNoiseReductionRequested"))
        assertTrue(cpp.contains("combineWithResidualHeadroom"))
        assertTrue(cpp.contains("profileNrPlan.lowFrequencyChromaAuthority"))
        assertFalse(nativeLib.contains("SpectraVisibleChroma"))
        assertFalse(dngMerger.contains("SpectraVisibleChroma"))
        assertFalse(cpp.contains("DNG_VISIBLE_CHROMA"))
    }
}
